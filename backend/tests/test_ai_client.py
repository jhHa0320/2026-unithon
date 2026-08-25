"""LLM 프로바이더 선택과 응답 파싱 — 네트워크 호출 없이 검증한다."""

import pytest

import backend.routers.decide as decide_mod
from backend.config import Settings
from backend.schemas.response import DecideResponse
from backend.services.ai_client import (
    DECISION_JSON_SCHEMA,
    MockAIClient,
    _parse_decision,
)


@pytest.fixture
def use_settings(monkeypatch):
    def _apply(**kwargs):
        settings = Settings(**kwargs)
        monkeypatch.setattr(decide_mod, "get_settings", lambda: settings)
        return settings

    return _apply


# --- 프로바이더 선택 ----------------------------------------------------------


def test_no_key_falls_back_to_mock(use_settings) -> None:
    use_settings()
    assert isinstance(decide_mod.get_ai_client(), MockAIClient)


def test_gemini_key_selects_gemini(use_settings) -> None:
    pytest.importorskip("google.genai")
    use_settings(GEMINI_API_KEY="test-key")
    assert type(decide_mod.get_ai_client()).__name__ == "GeminiAIClient"


def test_gemini_wins_over_anthropic(use_settings) -> None:
    pytest.importorskip("google.genai")
    use_settings(GEMINI_API_KEY="g", ANTHROPIC_API_KEY="a")
    assert type(decide_mod.get_ai_client()).__name__ == "GeminiAIClient"


# --- 응답 파싱 ---------------------------------------------------------------

VALID = (
    '{"action":"CLICK","target_node_id":7,"value":null,'
    '"instruction":"누를게요.","confidence":0.9,"status":"CONTINUE","reason":null}'
)


def test_parses_clean_json() -> None:
    assert _parse_decision(VALID).target_node_id == 7


def test_parses_json_wrapped_in_prose() -> None:
    """structured output을 걸어도 드물게 설명이 붙어 온다 — 그 스텝을 날리지 않는다."""
    assert _parse_decision(f"물론이죠!\n```json\n{VALID}\n```").action == "CLICK"


def test_empty_response_raises() -> None:
    with pytest.raises(ValueError):
        _parse_decision("")


# --- Gemini 스키마 변환 회귀 방지 ---------------------------------------------


def test_gemini_converts_decide_response_schema() -> None:
    """Gemini는 OpenAPI 서브셋을 쓴다 — `int | None`이 anyOf로 새어나가면 400이 난다.

    SDK 업그레이드로 이 변환이 깨지면 실기기 데모에서야 발견하게 되므로 여기서 잡는다.
    """
    genai = pytest.importorskip("google.genai")
    from google.genai import _transformers as tr

    schema = tr.t_schema(genai.Client(api_key="x")._api_client, DecideResponse)
    props = schema.model_dump(exclude_none=True)["properties"]

    assert props["target_node_id"]["type"] == "INTEGER"
    assert props["target_node_id"]["nullable"] is True
    assert "anyOf" not in props["target_node_id"]
    assert set(props["action"]["enum"]) == set(DECISION_JSON_SCHEMA["properties"]["action"]["enum"])
    assert set(props["status"]["enum"]) == set(DECISION_JSON_SCHEMA["properties"]["status"]["enum"])
