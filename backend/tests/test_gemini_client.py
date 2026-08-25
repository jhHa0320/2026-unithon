"""GeminiAIClient 단위 테스트. SDK 호출부를 스텁으로 대체하므로 네트워크 호출이 없다."""

import json

import pytest

from backend.schemas.llm import LLMDecision
from backend.schemas.request import ElementDTO
from backend.services import prompt
from backend.services.ai_client import (
    GEMINI_MIN_DEADLINE_SECONDS,
    AIClientError,
    GeminiAIClient,
)

ELEMENTS = [
    ElementDTO(
        id=3,
        text="김엄마",
        content_description=None,
        class_name="android.widget.Button",
        clickable=True,
        bounds=[0, 0, 300, 120],
    )
]


class _StubResponse:
    def __init__(self, text: str | None) -> None:
        self.text = text
        self.usage_metadata = None


class _StubModels:
    def __init__(self, text: str | None = None, raises: Exception | None = None) -> None:
        self._text = text
        self._raises = raises
        self.calls: list[dict] = []

    def generate_content(self, **kwargs):
        self.calls.append(kwargs)
        if self._raises is not None:
            raise self._raises
        return _StubResponse(self._text)


class _StubGenaiClient:
    def __init__(self, models: _StubModels) -> None:
        self.models = models


def _make_client(monkeypatch, text=None, raises=None) -> tuple[GeminiAIClient, _StubModels]:
    """genai.Client만 스텁으로 바꾼다. genai_types는 실제 SDK 타입을 그대로 쓰므로
    GenerateContentConfig 구성이 실제로 유효한지도 함께 검증된다."""
    models = _StubModels(text=text, raises=raises)

    class _StubGenaiModule:
        @staticmethod
        def Client(api_key: str):  # noqa: N802 - SDK 이름을 그대로 흉내낸다
            return _StubGenaiClient(models)

    monkeypatch.setattr("backend.services.ai_client.genai", _StubGenaiModule)
    client = GeminiAIClient(api_key="test-key", model="gemini-3.6-flash", thinking_level="low")
    return client, models


def _decision_json(**overrides) -> str:
    payload = {
        "target_node_id": 3,
        "action_type": "CLICK",
        "input_value": "",
        "voice_message": "김엄마 님 대화방을 열게요.",
        "reasoning": "수신자가 김엄마로 확정되어 대화방을 연다",
        "confidence": 0.9,
        "status": "CONTINUE",
    }
    payload.update(overrides)
    return json.dumps(payload, ensure_ascii=False)


# --- 정상 변환 -------------------------------------------------------------


def test_click_decision_maps_to_contract(monkeypatch) -> None:
    client, _ = _make_client(monkeypatch, text=_decision_json())

    response = client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert response.target_node_id == 3
    assert response.action_type == "CLICK"
    assert response.input_value is None  # 빈 문자열은 None으로 정규화
    assert response.status == "CONTINUE"
    assert response.voice_message == "김엄마 님 대화방을 열게요."
    assert response.instruction == "수신자가 김엄마로 확정되어 대화방을 연다"


def test_set_text_decision_keeps_input_value(monkeypatch) -> None:
    client, _ = _make_client(
        monkeypatch, text=_decision_json(action_type="SET_TEXT", input_value="김엄마")
    )

    response = client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert response.action_type == "SET_TEXT"
    assert response.input_value == "김엄마"


def test_sentinel_target_becomes_none(monkeypatch) -> None:
    """target_node_id=-1은 계약상의 null로 되돌아가고 액션 필드가 비워진다."""
    client, _ = _make_client(
        monkeypatch,
        text=_decision_json(
            target_node_id=-1,
            action_type="NONE",
            status="ASK_USER",
            voice_message="어느 분에게 보낼까요?",
        ),
    )

    response = client.decide("사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert response.target_node_id is None
    assert response.action_type is None
    assert response.input_value is None
    assert response.status == "ASK_USER"


def test_stale_action_fields_are_cleared_when_no_target(monkeypatch) -> None:
    """target은 없는데 action_type/input_value가 남아 온 경우에도 비워야 한다."""
    client, _ = _make_client(
        monkeypatch,
        text=_decision_json(target_node_id=-1, action_type="SET_TEXT", input_value="김엄마"),
    )

    response = client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert response.target_node_id is None
    assert response.action_type is None
    assert response.input_value is None


# --- 요청 구성 -------------------------------------------------------------


def test_request_carries_model_and_config(monkeypatch) -> None:
    client, models = _make_client(monkeypatch, text=_decision_json())

    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    call = models.calls[0]
    config = call["config"]
    assert call["model"] == "gemini-3.6-flash"
    assert config.system_instruction == prompt.SYSTEM_INSTRUCTION
    assert config.response_mime_type == "application/json"
    assert config.response_schema is LLMDecision
    # SDK가 "low"를 ThinkingLevel.LOW enum으로 정규화하므로 값으로 비교한다.
    assert str(config.thinking_config.thinking_level.value).lower() == "low"


def test_timeout_is_milliseconds_and_below_router_budget(monkeypatch) -> None:
    """http_options.timeout은 밀리초 단위다. 초로 넣으면 타임아웃이 사실상 사라진다."""
    from backend.routers.decide import AI_CLIENT_TIMEOUT_SECONDS

    client, models = _make_client(monkeypatch, text=_decision_json())
    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    timeout_ms = models.calls[0]["config"].http_options.timeout
    assert timeout_ms >= GEMINI_MIN_DEADLINE_SECONDS * 1000
    # SDK가 라우터보다 먼저 끊겨야 to_thread 스레드가 풀린다.
    assert timeout_ms / 1000 < AI_CLIENT_TIMEOUT_SECONDS


def test_timeout_below_gemini_minimum_is_clamped(monkeypatch) -> None:
    """10초 미만이면 Gemini가 모든 요청을 400으로 거부한다.

    2026-08-25에 4.5초로 설정했다가 전 요청이 'Minimum allowed deadline is 10s'로
    죽은 적이 있다. 설정 실수로 다시 전멸하지 않도록 하한으로 올린다.
    """
    models = _StubModels(text=_decision_json())

    class _StubGenaiModule:
        @staticmethod
        def Client(api_key: str):  # noqa: N802
            return _StubGenaiClient(models)

    monkeypatch.setattr("backend.services.ai_client.genai", _StubGenaiModule)
    client = GeminiAIClient(
        api_key="k", model="gemini-3.6-flash", thinking_level="low", timeout_seconds=4.5
    )
    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert models.calls[0]["config"].http_options.timeout == GEMINI_MIN_DEADLINE_SECONDS * 1000


def test_automatic_function_calling_is_disabled(monkeypatch) -> None:
    """툴을 안 쓰므로 AFC를 끈다. 켜져 있으면 호출마다 경고가 찍힌다."""
    client, models = _make_client(monkeypatch, text=_decision_json())
    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    assert models.calls[0]["config"].automatic_function_calling.disable is True


def test_input_includes_elements_and_user_speech(monkeypatch) -> None:
    client, models = _make_client(monkeypatch, text=_decision_json())

    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, "응 그래")

    payload = json.loads(models.calls[0]["contents"])
    assert payload["goal"] == "엄마한테 사진 보내줘"
    assert payload["app"] == "com.kakao.talk"
    assert payload["elements"][0]["id"] == 3
    assert payload["elements"][0]["class"] == "Button"  # 패키지 접두사 제거
    assert payload["user_reply"] == "응 그래"


def test_input_omits_empty_optional_fields(monkeypatch) -> None:
    client, models = _make_client(monkeypatch, text=_decision_json())

    client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)

    payload = json.loads(models.calls[0]["contents"])
    assert "user_reply" not in payload
    assert "history" not in payload
    assert "desc" not in payload["elements"][0]  # content_description이 None이므로


# --- 실패 처리 -------------------------------------------------------------


def test_api_exception_becomes_ai_client_error(monkeypatch) -> None:
    client, _ = _make_client(monkeypatch, raises=RuntimeError("boom"))

    with pytest.raises(AIClientError):
        client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)


def test_empty_output_becomes_ai_client_error(monkeypatch) -> None:
    client, _ = _make_client(monkeypatch, text="")

    with pytest.raises(AIClientError):
        client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)


def test_none_output_becomes_ai_client_error(monkeypatch) -> None:
    """안전 필터에 막히면 response.text가 None으로 온다."""
    client, _ = _make_client(monkeypatch, text=None)

    with pytest.raises(AIClientError):
        client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)


def test_malformed_json_becomes_ai_client_error(monkeypatch) -> None:
    client, _ = _make_client(monkeypatch, text="이것은 JSON이 아닙니다")

    with pytest.raises(AIClientError):
        client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)


def test_schema_mismatch_becomes_ai_client_error(monkeypatch) -> None:
    client, _ = _make_client(monkeypatch, text=json.dumps({"target_node_id": 3}))

    with pytest.raises(AIClientError):
        client.decide("엄마한테 사진 보내줘", "com.kakao.talk", ELEMENTS, None, None)


def test_missing_sdk_raises_clear_error(monkeypatch) -> None:
    monkeypatch.setattr("backend.services.ai_client.genai", None)

    with pytest.raises(AIClientError, match="google-genai"):
        GeminiAIClient(api_key="k", model="m", thinking_level="low")


# --- LLM은 UNSUPPORTED를 선택할 수 없다 -------------------------------------


def test_llm_cannot_emit_unsupported() -> None:
    """UNSUPPORTED는 서버 판정이므로 LLM 스키마에 없어야 한다."""
    with pytest.raises(Exception):
        LLMDecision.model_validate_json(_decision_json(status="UNSUPPORTED"))


# --- 스키마 호환성 ----------------------------------------------------------


def test_schema_stays_flat_for_provider_compatibility() -> None:
    """nullable(anyOf)이나 $ref가 생기면 프로바이더가 400을 낼 수 있다."""
    schema = json.dumps(LLMDecision.model_json_schema())

    assert "anyOf" not in schema
    assert "$ref" not in schema
    assert "$defs" not in schema
