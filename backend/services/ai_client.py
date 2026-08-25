"""LLM 호출 계층.

- `AIClient`: 라우터가 의존하는 인터페이스(Protocol).
- `MockAIClient`: LLM 없이 Android 팀이 통신을 개발할 수 있게 하는 결정론적 구현체.
- `GeminiAIClient`: Google Gemini 연동 (structured output).
- `ClaudeAIClient`: Anthropic Claude 연동 (structured outputs + prompt caching).

프로바이더는 `routers/decide.py`의 `get_ai_client()`가 **설정된 키를 보고 고른다**.
새 프로바이더를 붙이려면 `AIClient` Protocol만 만족하면 되고 라우터·스키마는 건드리지 않는다.
"""

import json
from pathlib import Path
from typing import Any, Protocol

from backend.config import Settings
from backend.schemas.request import ElementDTO, HistoryEntry, InstalledApp
from backend.services import rules
from pydantic import ValidationError

from backend.schemas.response import DecideResponse

PROMPT_PATH = Path(__file__).resolve().parents[1] / "prompts" / "decide_system.md"

# structured outputs로 강제할 응답 스키마. DecideResponse와 반드시 동기화할 것.
DECISION_JSON_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "action": {
            "type": "string",
            "enum": [
                "CLICK",
                "SET_TEXT",
                "LAUNCH_APP",
                "SCROLL_FORWARD",
                "SCROLL_BACKWARD",
                "BACK",
                "NONE",
            ],
        },
        "target_node_id": {"type": ["integer", "null"]},
        "value": {"type": ["string", "null"]},
        "instruction": {"type": "string"},
        "confidence": {"type": "number"},
        "status": {
            "type": "string",
            "enum": [
                "CONTINUE",
                "DONE",
                "ASK_USER",
                "CONFIRM_REQUIRED",
                "UNSUPPORTED",
            ],
        },
        "reason": {"type": ["string", "null"]},
    },
    "required": [
        "action",
        "target_node_id",
        "value",
        "instruction",
        "confidence",
        "status",
        "reason",
    ],
    "additionalProperties": False,
}


def _parse_decision(raw: str | None) -> DecideResponse:
    """모델 응답 문자열을 DecideResponse로 만든다.

    structured output을 걸어도 앞뒤에 설명이 붙어 오는 경우가 드물게 있어,
    실패하면 첫 JSON 객체만 잘라내 한 번 더 시도한다. 여기서 죽으면 그 스텝이 통째로
    날아가므로 방어적으로 간다.
    """
    if not raw:
        raise ValueError("empty response from model")
    try:
        return DecideResponse.model_validate_json(raw)
    except ValidationError:
        start, end = raw.find("{"), raw.rfind("}")
        if start == -1 or end <= start:
            raise
        return DecideResponse.model_validate_json(raw[start : end + 1])


class AIClient(Protocol):
    def decide(
        self,
        *,
        goal: str,
        app_package: str | None,
        elements: list[ElementDTO],
        installed_apps: list[InstalledApp] | None,
        history: list[HistoryEntry],
    ) -> DecideResponse: ...


def _build_payload(
    goal: str,
    app_package: str | None,
    elements: list[ElementDTO],
    installed_apps: list[InstalledApp] | None,
    history: list[HistoryEntry],
) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "goal": goal,
        "app_package": app_package,
        "history": [h.model_dump() for h in history],
        # 압축 표현 — null 제거, 클래스명 축약, 플래그 압축, 위치 힌트 (services/rules.py)
        "elements": rules.build_llm_payload(elements),
    }
    if app_package is None and installed_apps:
        payload["installed_apps"] = [a.model_dump() for a in installed_apps]
    return payload


class MockAIClient:
    """LLM 없이 동작하는 결정론적 Mock. Android 팀의 통신/루프 개발용.

    실제 판단을 흉내내지 않는다 — 앱이 없으면 첫 앱을 열고, 있으면 첫 clickable을 누른다.
    프롬프트 품질 검증에는 절대 쓰지 말 것.
    """

    def decide(
        self,
        *,
        goal: str,
        app_package: str | None,
        elements: list[ElementDTO],
        installed_apps: list[InstalledApp] | None,
        history: list[HistoryEntry],
    ) -> DecideResponse:
        if app_package is None:
            target_app = installed_apps[0] if installed_apps else None
            if target_app is None:
                return DecideResponse(
                    action="NONE",
                    target_node_id=None,
                    value=None,
                    instruction="어떤 앱으로 해드릴까요?",
                    confidence=1.0,
                    status="ASK_USER",
                    reason="no installed_apps provided",
                )
            return DecideResponse(
                action="LAUNCH_APP",
                target_node_id=None,
                value=target_app.package,
                instruction=f"{target_app.label}을(를) 열게요.",
                confidence=0.99,
                status="CONTINUE",
                reason=None,
            )

        target = next((e for e in elements if e.clickable), None)
        if target is None:
            return DecideResponse(
                action="NONE",
                target_node_id=None,
                value=None,
                instruction="이 화면에서는 더 진행할 수 없어요.",
                confidence=0.99,
                status="UNSUPPORTED",
                reason="no clickable element",
            )
        return DecideResponse(
            action="CLICK",
            target_node_id=target.id,
            value=None,
            instruction="다음 단계로 넘어갈게요.",
            confidence=0.9,
            status="CONTINUE",
            reason=None,
        )


class ClaudeAIClient:
    """Anthropic Claude 연동.

    - structured outputs(`output_config.format`)로 응답 JSON 스키마를 강제한다.
      프롬프트로 "JSON만 반환해"라고 부탁하지 않는다.
    - 시스템 프롬프트는 화면마다 반복되므로 prompt caching에 올린다.
    """

    def __init__(self, settings: Settings) -> None:
        import anthropic  # 지연 import — Mock만 쓰는 개발 환경에서는 설치가 필요 없다

        self._settings = settings
        self._client = anthropic.Anthropic(api_key=settings.ANTHROPIC_API_KEY)
        self._system_prompt = PROMPT_PATH.read_text(encoding="utf-8")

    def decide(
        self,
        *,
        goal: str,
        app_package: str | None,
        elements: list[ElementDTO],
        installed_apps: list[InstalledApp] | None,
        history: list[HistoryEntry],
    ) -> DecideResponse:
        payload = _build_payload(goal, app_package, elements, installed_apps, history)
        message = self._client.messages.create(
            model=self._settings.ANTHROPIC_MODEL,
            max_tokens=self._settings.ANTHROPIC_MAX_TOKENS,
            system=[
                {
                    "type": "text",
                    "text": self._system_prompt,
                    "cache_control": {"type": "ephemeral"},
                }
            ],
            messages=[
                {
                    "role": "user",
                    "content": json.dumps(payload, ensure_ascii=False),
                }
            ],
            output_config={
                "effort": self._settings.ANTHROPIC_EFFORT,
                "format": {"type": "json_schema", "schema": DECISION_JSON_SCHEMA},
            },
        )
        raw = "".join(block.text for block in message.content if block.type == "text")
        return _parse_decision(raw)


class GeminiAIClient:
    """Google Gemini 연동.

    - `response_mime_type="application/json"` + `response_schema`로 응답 형식을 강제한다.
      프롬프트로 "JSON만 반환해"라고 부탁하지 않는다.
    - 시스템 프롬프트는 `system_instruction`으로 분리해 넣는다 (Gemini 2.5+는 동일 접두어에
      대해 암묵적 컨텍스트 캐싱이 적용될 수 있다 — 화면마다 반복되는 구조라 유리하다).
    - `temperature=0` — 같은 화면에서 매번 다른 판단이 나오면 디버깅이 불가능해진다.
    """

    def __init__(self, settings: Settings) -> None:
        from google import genai  # 지연 import — 이 프로바이더를 쓸 때만 필요
        from google.genai import types

        self._settings = settings
        self._types = types
        self._client = genai.Client(api_key=settings.GEMINI_API_KEY)
        self._system_prompt = PROMPT_PATH.read_text(encoding="utf-8")

    def decide(
        self,
        *,
        goal: str,
        app_package: str | None,
        elements: list[ElementDTO],
        installed_apps: list[InstalledApp] | None,
        history: list[HistoryEntry],
    ) -> DecideResponse:
        types = self._types
        payload = _build_payload(goal, app_package, elements, installed_apps, history)

        config: dict[str, Any] = {
            "system_instruction": self._system_prompt,
            "response_mime_type": "application/json",
            "response_schema": DecideResponse,
            "temperature": 0,
        }
        if self._settings.GEMINI_THINKING_BUDGET is not None:
            config["thinking_config"] = types.ThinkingConfig(
                thinking_budget=self._settings.GEMINI_THINKING_BUDGET
            )

        response = self._client.models.generate_content(
            model=self._settings.GEMINI_MODEL,
            contents=json.dumps(payload, ensure_ascii=False),
            config=types.GenerateContentConfig(**config),
        )
        return _parse_decision(response.text)
