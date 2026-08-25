"""LLM 호출 인터페이스와 구현체 두 개.

- MockAIClient: 규칙 기반. API 키 없이 서버를 띄워 통신·자동화 루프를 붙여볼 때 사용.
- GeminiAIClient: 실제 추론. Gemini Interactions API를 호출한다.

어느 쪽을 쓸지는 routers/decide.py의 get_ai_client()가 GEMINI_API_KEY 유무로 결정한다.
"""

import json
from typing import Protocol

from pydantic import ValidationError

from backend.core.logging import get_logger
from backend.schemas.llm import NO_ACTION, NO_TARGET_NODE_ID, LLMDecision
from backend.schemas.request import ElementDTO, HistoryEntry
from backend.schemas.response import DecideResponse
from backend.services import prompt

logger = get_logger(__name__)

# google-genai가 설치되지 않은 환경에서도 Mock으로 서버가 뜨도록 선택적 import로 둔다.
try:
    from google import genai
    from google.genai import errors as genai_errors
    from google.genai import types as genai_types
except ImportError:  # pragma: no cover - 패키지 미설치 환경
    genai = None
    genai_errors = None
    genai_types = None


class AIClientError(Exception):
    """LLM 호출/파싱 실패. 라우터가 잡아 UNSUPPORTED로 응답한다."""


# Gemini는 이보다 짧은 deadline을 400으로 거부한다.
# ("Manually set deadline Ns is too short. Minimum allowed deadline is 10s.")
GEMINI_MIN_DEADLINE_SECONDS = 10.0

# 사용자의 확인 응답 판정용 임시 사전. 정식 구현은 작업 B-4에서 대체한다.
_AFFIRMATIVE_WORDS = ("응", "어", "네", "예", "그래", "좋아", "해줘", "진행", "확인", "맞아")
_NEGATIVE_WORDS = ("아니", "안돼", "안 돼", "취소", "그만", "싫어", "하지마", "중단")

# 입력 필드로 간주할 클래스명 조각
_EDITABLE_CLASS_HINTS = ("EditText", "AutoCompleteTextView", "SearchView")

_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3


def _object_particle(word: str) -> str:
    """목적격 조사를 받침 유무에 따라 고른다. TTS 문구가 어색해지지 않게 하기 위함."""
    last_char = word.strip()[-1:]
    if not last_char:
        return "를"
    code = ord(last_char)
    if not _HANGUL_BASE <= code <= _HANGUL_LAST:
        # 한글이 아니면(영문·숫자·기호) 판정이 불가능하므로 조사를 붙이지 않는다.
        return ""
    has_final_consonant = (code - _HANGUL_BASE) % 28 != 0
    return "을" if has_final_consonant else "를"


class AIClient(Protocol):
    """LLM 호출 인터페이스. 구현체는 동기 함수로 두고 라우터가 스레드로 offload한다."""

    # 지금 어느 구현체로 돌고 있는지 /health가 노출하는 데 쓴다. 키가 없으면 서버가 조용히
    # Mock으로 폴백하는데, Mock은 첫 clickable 요소를 무조건 누르므로 눈치채지 못하면
    # 시연 중 아무 버튼이나 누르는 그림이 나온다.
    PROVIDER: str

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
        user_speech: str | None,
    ) -> DecideResponse: ...


class MockAIClient:
    """규칙 기반 개발용 Mock 구현체. 실제 LLM 연동(B-2) 전까지 사용."""

    PROVIDER = "mock"

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
        user_speech: str | None = None,
    ) -> DecideResponse:
        if user_speech:
            declined = self._check_declined(user_speech)
            if declined is not None:
                return declined

        target = next((element for element in elements if element.clickable), None)
        if target is None:
            return DecideResponse(
                target_node_id=None,
                action_type=None,
                input_value=None,
                instruction="클릭 가능한 요소가 없어 다음 행동을 결정할 수 없음",
                voice_message="화면에서 누를 수 있는 것을 찾지 못했어요. 어떻게 할까요?",
                confidence=1.0,
                status="ASK_USER",
                reason="no clickable element in elements",
            )

        if self._is_editable(target):
            # Mock은 목표를 이해하지 못하므로 goal 앞부분을 그대로 넣는다.
            # 실제 입력값 판단은 GeminiAIClient의 몫이다.
            return DecideResponse(
                target_node_id=target.id,
                action_type="SET_TEXT",
                input_value=goal[:20] or "테스트",
                instruction=f"입력 필드(node {target.id})에 텍스트 입력",
                voice_message="입력할게요.",
                confidence=0.9,
                status="CONTINUE",
                reason=None,
            )

        label = self._label_of(target)
        return DecideResponse(
            target_node_id=target.id,
            action_type="CLICK",
            input_value=None,
            instruction=f"node {target.id} 클릭",
            voice_message=(
                f"{label}{_object_particle(label)} 누를게요."
                if label
                else "다음 단계로 넘어갈게요."
            ),
            confidence=0.9,
            status="CONTINUE",
            reason=None,
        )

    def _check_declined(self, user_speech: str) -> DecideResponse | None:
        """사용자가 거절했으면 흐름을 종료하는 응답을, 그 외에는 None을 반환한다.

        부정어를 긍정어보다 먼저 본다. '아니 그래'처럼 둘 다 섞인 발화에서는
        중단하는 쪽이 안전하기 때문이다.
        """
        if any(word in user_speech for word in _NEGATIVE_WORDS):
            return DecideResponse(
                target_node_id=None,
                action_type=None,
                input_value=None,
                instruction="사용자가 진행을 거절하여 흐름 종료",
                voice_message="알겠습니다. 여기서 멈출게요.",
                confidence=1.0,
                status="DONE",
                reason="user declined",
            )
        if any(word in user_speech for word in _AFFIRMATIVE_WORDS):
            return None
        return DecideResponse(
            target_node_id=None,
            action_type=None,
            input_value=None,
            instruction="사용자 응답을 긍정/부정으로 판정하지 못함",
            voice_message="죄송해요, 다시 한번 말씀해 주시겠어요?",
            confidence=1.0,
            status="ASK_USER",
            reason="user_speech not recognized as yes or no",
        )

    def _is_editable(self, element: ElementDTO) -> bool:
        return any(hint in element.class_name for hint in _EDITABLE_CLASS_HINTS)

    def _label_of(self, element: ElementDTO) -> str | None:
        return element.text or element.content_description


class GeminiAIClient:
    """Gemini generateContent API 구현체.

    Interactions API(client.interactions)를 쓰지 않는다. 요청은 정상적으로 나가지만
    서버가 응답 헤더를 보내지 않고 무한 대기하는 현상이 확인됐다(2026-08-25 측정, 150초 무응답).
    generateContent는 같은 키·같은 모델로 2초대에 응답하고 공식 문서상 계속 지원된다.

    동기 호출이다. 라우터가 asyncio.to_thread로 offload하므로 async 처리를 하지 않는다.
    """

    PROVIDER = "gemini"

    def __init__(
        self,
        api_key: str,
        model: str,
        thinking_level: str,
        timeout_seconds: float = GEMINI_MIN_DEADLINE_SECONDS,
    ) -> None:
        if genai is None:
            raise AIClientError(
                "google-genai가 설치되지 않았습니다. `pip install -r requirements.txt`를 실행하세요."
            )

        # 10초 미만이면 Gemini가 모든 요청을 400으로 거부한다. 조용히 전멸하는 대신
        # 하한으로 올리고 경고를 남긴다 — 설정 실수로 서버가 죽지 않게 하기 위함이다.
        if timeout_seconds < GEMINI_MIN_DEADLINE_SECONDS:
            logger.warning(
                "GEMINI_TIMEOUT_SECONDS too low — clamping",
                extra={"requested": timeout_seconds, "minimum": GEMINI_MIN_DEADLINE_SECONDS},
            )
            timeout_seconds = GEMINI_MIN_DEADLINE_SECONDS

        self._client = genai.Client(api_key=api_key)
        self._model = model
        self._timeout_seconds = timeout_seconds
        # 요청마다 동일하므로 config를 한 번만 만들어 재사용한다.
        self._config = genai_types.GenerateContentConfig(
            system_instruction=prompt.SYSTEM_INSTRUCTION,
            response_mime_type="application/json",
            response_schema=LLMDecision,
            thinking_config=genai_types.ThinkingConfig(thinking_level=thinking_level),
            # 툴을 쓰지 않으므로 자동 함수 호출을 끈다. 켜져 있으면 매 호출마다
            # "AFC is not recommended" 경고가 찍혀 로그가 지저분해진다.
            automatic_function_calling=genai_types.AutomaticFunctionCallingConfig(disable=True),
            # http_options.timeout은 '밀리초'다. 초 단위로 넣으면 타임아웃이 사실상 사라진다.
            http_options=genai_types.HttpOptions(timeout=int(timeout_seconds * 1000)),
        )

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
        user_speech: str | None = None,
    ) -> DecideResponse:
        user_input = prompt.build_input(
            goal=goal,
            app_package=app_package,
            elements=elements,
            history=history,
            user_speech=user_speech,
        )

        try:
            response = self._client.models.generate_content(
                model=self._model,
                contents=user_input,
                config=self._config,
            )
        except Exception as exc:  # SDK 예외 계층이 넓어 광범위하게 잡고 변환한다
            raise AIClientError(self._describe_api_error(exc)) from exc

        self._log_usage(response)
        return self._to_decide_response(response.text)

    def _log_usage(self, response) -> None:
        """토큰 사용량을 남긴다. 화면 원문은 포함되지 않으므로 기록해도 안전하다."""
        usage = getattr(response, "usage_metadata", None)
        if usage is None:
            return
        logger.info(
            "gemini usage",
            extra={
                "model": self._model,
                "input_tokens": getattr(usage, "prompt_token_count", None),
                "output_tokens": getattr(usage, "candidates_token_count", None),
                "thought_tokens": getattr(usage, "thoughts_token_count", None),
            },
        )

    def _describe_api_error(self, exc: Exception) -> str:
        """SDK 예외를 로그용 문자열로 바꾼다. 응답 본문에는 노출하지 않는다."""
        if genai_errors is not None and isinstance(exc, genai_errors.APIError):
            return f"Gemini API error {getattr(exc, 'code', '?')}: {getattr(exc, 'message', exc)}"
        return f"{type(exc).__name__}: {exc}"

    def _to_decide_response(self, output_text: str | None) -> DecideResponse:
        """LLM 원시 출력(JSON 문자열)을 계약 스키마로 변환한다.

        스키마를 지정해도 빈 응답이나 깨진 JSON이 올 수 있으므로 방어한다.
        여기서 status를 UNSUPPORTED로 만들지 않는다 — 그 판정은 safety 게이트의 몫이다.
        """
        if not output_text:
            raise AIClientError("LLM이 빈 응답을 반환했습니다.")

        try:
            decision = LLMDecision.model_validate_json(output_text)
        except ValidationError as exc:
            raise AIClientError(f"LLM 응답 스키마 불일치: {exc.error_count()}건") from exc
        except json.JSONDecodeError as exc:
            raise AIClientError("LLM 응답이 유효한 JSON이 아닙니다.") from exc

        # 센티널을 계약상의 None으로 되돌린다.
        target_node_id = (
            None if decision.target_node_id == NO_TARGET_NODE_ID else decision.target_node_id
        )
        action_type = None if decision.action_type == NO_ACTION else decision.action_type
        input_value = decision.input_value or None

        # 조작 대상이 없으면 액션 관련 필드를 모두 비운다.
        if target_node_id is None:
            action_type = None
            input_value = None

        return DecideResponse(
            target_node_id=target_node_id,
            action_type=action_type,
            input_value=input_value,
            instruction=decision.reasoning,
            voice_message=decision.voice_message,
            confidence=decision.confidence,
            status=decision.status,
            reason=None,
        )
