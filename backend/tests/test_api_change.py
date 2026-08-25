"""api-change 브랜치 실험 경로 테스트 — Claude 추론 + Whisper STT.

실제 API는 호출하지 않는다(과금·비결정성). 스텁을 주입해 배선과 계약만 고정한다:
제공자를 바꿔도 응답 스키마와 안전 게이트가 그대로인지, 키가 없을 때 어떻게 되는지.
"""

import io

import pytest
from fastapi.testclient import TestClient

from backend.main import app
from backend.routers.decide import get_ai_client
from backend.routers.transcribe import get_stt_client
from backend.schemas.llm import LLMDecision
from backend.services.ai_client import MockAIClient, decision_to_response
from backend.services.stt_client import STTClientError

client = TestClient(app)

BASE_ELEMENT = {
    "id": 1,
    "text": "조회",
    "content_description": None,
    "class_name": "android.widget.TextView",
    "clickable": True,
    "bounds": [0, 0, 100, 50],
}


def _payload() -> dict:
    return {
        "session_id": "api-change-session",
        "goal": "엄마한테 사진 보내줘",
        "app_package": "com.kakao.talk",
        "elements": [dict(BASE_ELEMENT)],
        "user_speech": None,
        "history": None,
    }


@pytest.fixture(autouse=True)
def _isolate_clients():
    """실제 API를 때리지 않도록 두 의존성을 모두 고정한다."""
    app.dependency_overrides[get_ai_client] = lambda: MockAIClient()
    app.dependency_overrides[get_stt_client] = lambda: None
    yield
    app.dependency_overrides.pop(get_ai_client, None)
    app.dependency_overrides.pop(get_stt_client, None)


# --- 제공자 선택 -------------------------------------------------------------


def test_provider_defaults_to_gemini() -> None:
    from backend.config import Settings

    assert Settings().AI_PROVIDER == "gemini"


def test_claude_provider_falls_back_to_mock_without_key() -> None:
    """키 없는 팀원도 서버를 띄울 수 있어야 한다 — Gemini 경로와 같은 규칙."""
    from backend.routers.decide import _build_claude_client

    build = _build_claude_client.__wrapped__  # lru_cache 우회(테스트 간 캐시 오염 방지)
    assert isinstance(build(None, "claude-opus-5", "low", 4096, 10.0), MockAIClient)


def test_claude_client_declares_its_provider() -> None:
    """/health의 ai_client 표시가 실제 구현체와 어긋나지 않는지 고정한다."""
    from backend.services.ai_client import ClaudeAIClient

    assert ClaudeAIClient.PROVIDER == "claude"


# --- 제공자 간 응답 변환이 동일한지 ------------------------------------------


def test_stray_node_id_with_none_action_is_cleared_not_demoted() -> None:
    """LLM이 action_type="NONE"인데 target_node_id를 남겨 보내는 일이 실기기에서 관찰됐다.

    NONE은 스키마 정의상 '조작 없음'이므로 남은 node id는 무시하는 게 맞다. 예전에는 이
    불일치가 validate_action에서 UNSUPPORTED로 강등돼 세션이 통째로 끝났다 — DONE 판정에
    잡동사니 id 하나 섞였다고 시연이 중단되는 사고.
    """
    decision = LLMDecision(
        target_node_id=7,        # 잡동사니 — action이 NONE이므로 의미 없음
        action_type="NONE",
        input_value="",
        voice_message="사진을 보냈어요.",
        reasoning="전송 완료 확인",
        confidence=0.9,
        status="DONE",
    )
    response = decision_to_response(decision)

    assert response.target_node_id is None
    assert response.action_type is None
    assert response.status == "DONE"


def test_decision_conversion_is_shared_across_providers() -> None:
    """센티널 처리가 제공자마다 다르면 비교 실험이 성립하지 않는다."""
    decision = LLMDecision(
        target_node_id=-1,  # 센티널 = 조작 대상 없음
        action_type="NONE",
        input_value="",
        voice_message="누구에게 보낼까요?",
        reasoning="수신자가 확정되지 않음",
        confidence=0.4,
        status="ASK_USER",
    )
    response = decision_to_response(decision)

    assert response.target_node_id is None
    assert response.action_type is None
    assert response.input_value is None
    assert response.instruction == "수신자가 확정되지 않음"
    assert response.status == "ASK_USER"


def test_claude_response_passes_through_safety_gates() -> None:
    """제공자가 바뀌어도 confidence 게이트는 그대로 적용돼야 한다."""

    class StubClaudeClient:
        PROVIDER = "claude"

        def decide(self, goal, app_package, elements, history, user_speech=None):
            return decision_to_response(
                LLMDecision(
                    target_node_id=1,
                    action_type="CLICK",
                    input_value="",
                    voice_message="사진첩을 열게요.",
                    reasoning="첫 단계",
                    confidence=0.1,  # 임계값 미만
                    status="CONTINUE",
                )
            )

    app.dependency_overrides[get_ai_client] = lambda: StubClaudeClient()
    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "ASK_USER"
    assert body["target_node_id"] is None
    assert body["voice_message"].endswith("?")


# --- Whisper STT -------------------------------------------------------------


def test_transcribe_returns_503_without_key() -> None:
    """STT는 Mock 폴백이 없다 — 흉내 낸 받아쓰기로 자동화가 실제 조작을 하면 안 되므로."""
    response = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.m4a", io.BytesIO(b"fake audio"), "audio/mp4")},
    )

    assert response.status_code == 503
    assert response.json()["error_code"] == "STT_NOT_CONFIGURED"


def test_transcribe_returns_text() -> None:
    class StubSTT:
        PROVIDER = "whisper"

        def transcribe(self, audio, filename, language):
            assert audio == b"fake audio"
            assert filename == "a.m4a"
            assert language == "ko"
            return "엄마한테 사진 보내줘"

    app.dependency_overrides[get_stt_client] = lambda: StubSTT()
    response = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.m4a", io.BytesIO(b"fake audio"), "audio/mp4")},
        data={"language": "ko"},
    )

    assert response.status_code == 200
    assert response.json() == {"text": "엄마한테 사진 보내줘"}


def test_transcribe_rejects_empty_audio() -> None:
    class StubSTT:
        def transcribe(self, audio, filename, language):
            raise AssertionError("빈 오디오는 STT까지 가면 안 된다")

    app.dependency_overrides[get_stt_client] = lambda: StubSTT()
    response = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.m4a", io.BytesIO(b""), "audio/mp4")},
    )

    assert response.status_code == 422
    assert response.json()["error_code"] == "EMPTY_AUDIO"


def test_transcribe_rejects_oversized_audio() -> None:
    from backend.config import get_settings

    class StubSTT:
        def transcribe(self, audio, filename, language):
            raise AssertionError("상한 초과 오디오는 STT까지 가면 안 된다")

    app.dependency_overrides[get_stt_client] = lambda: StubSTT()
    oversized = b"x" * (get_settings().WHISPER_MAX_UPLOAD_BYTES + 1)
    response = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.m4a", io.BytesIO(oversized), "audio/mp4")},
    )

    assert response.status_code == 413
    assert response.json()["error_code"] == "AUDIO_TOO_LARGE"


def test_transcribe_does_not_leak_provider_error_detail() -> None:
    """API 키나 내부 오류 문자열이 응답 본문으로 새면 안 된다."""

    class FailingSTT:
        def transcribe(self, audio, filename, language):
            raise STTClientError("Whisper API error 401: bad key sk-SECRET123")

    app.dependency_overrides[get_stt_client] = lambda: FailingSTT()
    response = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.m4a", io.BytesIO(b"fake audio"), "audio/mp4")},
    )

    assert response.status_code == 502
    assert "sk-SECRET123" not in response.text
    assert "401" not in response.text
