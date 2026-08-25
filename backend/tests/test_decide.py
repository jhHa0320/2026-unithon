import logging
import time

import pytest
from fastapi.testclient import TestClient

from backend.main import app
from backend.routers.decide import get_ai_client
from backend.schemas.response import DecideResponse
from backend.services.ai_client import AIClientError, MockAIClient

client = TestClient(app)

BASE_ELEMENT = {
    "id": 1,
    "text": "조회",
    "content_description": None,
    "class_name": "android.widget.TextView",
    "clickable": True,
    "bounds": [0, 0, 100, 50],
}


def _payload(
    elements: list[dict] | None = None,
    session_id: str = "test-session",
    user_speech: str | None = None,
) -> dict:
    return {
        "session_id": session_id,
        "goal": "엄마한테 사진 보내줘",
        "app_package": "com.kakao.talk",
        "elements": elements if elements is not None else [dict(BASE_ELEMENT)],
        "user_speech": user_speech,
        "history": None,
    }


def _stub_client(response: DecideResponse):
    """고정 응답을 돌려주는 AI 클라이언트 스텁을 주입한다."""

    class StubAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            return response

    app.dependency_overrides[get_ai_client] = lambda: StubAIClient()


def _response(**overrides) -> DecideResponse:
    defaults = {
        "target_node_id": 1,
        "action_type": "CLICK",
        "input_value": None,
        "instruction": "node 1 클릭",
        "voice_message": "누를게요.",
        "confidence": 0.9,
        "status": "CONTINUE",
        "reason": None,
    }
    return DecideResponse(**{**defaults, **overrides})


@pytest.fixture(autouse=True)
def _isolate_ai_client():
    """기본 클라이언트를 Mock으로 고정한다.

    이게 없으면 개발자 .env에 GEMINI_API_KEY가 있을 때 테스트가 실제 API를 호출해
    과금되고, 응답이 비결정적이라 테스트가 흔들린다. 개별 테스트는 이 위에 덮어쓴다.
    """
    app.dependency_overrides[get_ai_client] = lambda: MockAIClient()
    yield
    app.dependency_overrides.pop(get_ai_client, None)


# --- 정상 경로 -------------------------------------------------------------


def test_decide_returns_continue_on_normal_case() -> None:
    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CONTINUE"
    assert body["target_node_id"] == 1
    assert body["action_type"] == "CLICK"
    assert body["voice_message"]


def test_edit_text_element_returns_set_text_with_input_value() -> None:
    elements = [{**BASE_ELEMENT, "class_name": "android.widget.EditText", "text": None}]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    body = response.json()
    assert body["action_type"] == "SET_TEXT"
    assert body["input_value"]


def test_no_clickable_element_returns_ask_user() -> None:
    elements = [{**BASE_ELEMENT, "clickable": False}]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    assert response.json()["status"] == "ASK_USER"


# --- 요청 검증 -------------------------------------------------------------


def test_empty_elements_returns_422() -> None:
    response = client.post("/api/v1/decide", json=_payload(elements=[]))

    assert response.status_code == 422


def test_invalid_bounds_returns_422() -> None:
    elements = [{**BASE_ELEMENT, "bounds": [100, 0, 0, 50]}]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    assert response.status_code == 422


def test_bounds_with_wrong_length_returns_422() -> None:
    elements = [{**BASE_ELEMENT, "bounds": [0, 0, 100]}]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    assert response.status_code == 422


def test_validation_error_uses_common_error_format() -> None:
    response = client.post("/api/v1/decide", json=_payload(elements=[]))

    body = response.json()
    assert body["error_code"] == "VALIDATION_ERROR"
    assert "message" in body


# --- 안전 게이트 -----------------------------------------------------------


def test_low_confidence_is_overridden_to_ask_user() -> None:
    _stub_client(_response(confidence=0.1))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "ASK_USER"
    assert body["target_node_id"] is None
    assert body["action_type"] is None


def test_hallucinated_target_node_id_returns_unsupported() -> None:
    _stub_client(_response(target_node_id=999))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"
    assert body["target_node_id"] is None


def test_set_text_without_input_value_returns_unsupported() -> None:
    _stub_client(_response(action_type="SET_TEXT", input_value=None))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"


def test_target_without_action_type_returns_unsupported() -> None:
    _stub_client(_response(action_type=None))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"


def test_ai_client_error_returns_unsupported_not_500() -> None:
    """LLM 호출 실패에도 서버는 계약 스키마로 응답해야 한다 (CLAUDE.md 12장)."""

    class FailingAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            raise AIClientError("Gemini API error 503: unavailable")

    app.dependency_overrides[get_ai_client] = lambda: FailingAIClient()

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UNSUPPORTED"
    assert body["target_node_id"] is None
    assert body["voice_message"]


def test_ai_client_error_detail_is_not_leaked_to_client() -> None:
    """API 키나 내부 오류 문자열이 응답 본문으로 새면 안 된다."""

    class FailingAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            raise AIClientError("Gemini API error 401: bad key AIzaSyTOPSECRET")

    app.dependency_overrides[get_ai_client] = lambda: FailingAIClient()

    raw = client.post("/api/v1/decide", json=_payload()).text

    assert "AIzaSyTOPSECRET" not in raw
    assert "401" not in raw


def test_ai_client_timeout_returns_unsupported(monkeypatch) -> None:
    monkeypatch.setattr("backend.routers.decide.AI_CLIENT_TIMEOUT_SECONDS", 0.1)

    class SlowAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            time.sleep(0.5)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SlowAIClient()

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UNSUPPORTED"
    assert body["reason"] == "AI 응답 지연"


# --- 전송 자동 진행 (차단하지 않아야 함) -----------------------------------


def test_send_element_is_not_filtered_from_ai_input() -> None:
    """전송 버튼은 LLM 입력에서 제외되지 않는다. 에이전트가 전송까지 완결해야 하므로."""
    captured: list = []

    class SpyAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            captured.extend(elements)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SpyAIClient()

    elements = [{**BASE_ELEMENT, "text": "전송"}]
    client.post("/api/v1/decide", json=_payload(elements=elements))

    assert [element.id for element in captured] == [1]
    assert captured[0].text == "전송"


def test_send_element_can_be_clicked() -> None:
    """전송 버튼을 target으로 지목한 응답이 게이트에서 걸리지 않고 그대로 통과한다."""
    _stub_client(_response(instruction="전송 버튼 클릭"))

    elements = [{**BASE_ELEMENT, "text": "전송"}]
    body = client.post("/api/v1/decide", json=_payload(elements=elements)).json()

    assert body["status"] == "CONTINUE"
    assert body["target_node_id"] == 1
    assert body["action_type"] == "CLICK"


# --- 마스킹 / 로깅 ---------------------------------------------------------


def test_personal_data_is_masked_before_ai_call() -> None:
    captured: list = []

    class SpyAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            captured.extend(elements)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SpyAIClient()

    elements = [{**BASE_ELEMENT, "text": "010-1234-5678", "content_description": "901231-1234567"}]
    client.post("/api/v1/decide", json=_payload(elements=elements))

    assert "1234-5678" not in (captured[0].text or "")
    assert "901231-1234567" not in (captured[0].content_description or "")


def test_logs_do_not_contain_sensitive_text(caplog) -> None:
    with caplog.at_level(logging.INFO):
        client.post("/api/v1/decide", json=_payload())

    for record in caplog.records:
        assert "조회" not in record.getMessage()
        assert not hasattr(record, "text")
        assert not hasattr(record, "content_description")


# --- 사용자 응답 (user_speech) ---------------------------------------------


def test_negative_user_speech_stops_the_flow() -> None:
    body = client.post("/api/v1/decide", json=_payload(user_speech="아니 취소해줘")).json()

    assert body["status"] == "DONE"
    assert body["target_node_id"] is None


def test_affirmative_user_speech_continues() -> None:
    body = client.post("/api/v1/decide", json=_payload(user_speech="응 그래")).json()

    assert body["status"] == "CONTINUE"
    assert body["target_node_id"] == 1


def test_unrecognized_user_speech_asks_again() -> None:
    body = client.post("/api/v1/decide", json=_payload(user_speech="음 글쎄요")).json()

    assert body["status"] == "ASK_USER"


# --- 세션 ------------------------------------------------------------------


def test_session_history_is_passed_to_ai_on_next_turn() -> None:
    captured: list = []

    class SpyAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            captured.append(history)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SpyAIClient()

    client.post("/api/v1/decide", json=_payload(session_id="session-history"))
    client.post("/api/v1/decide", json=_payload(session_id="session-history"))

    assert captured[0] == []
    assert len(captured[1]) == 1
    assert captured[1][0].step == 1


def test_sessions_are_isolated_by_session_id() -> None:
    captured: list = []

    class SpyAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            captured.append(history)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SpyAIClient()

    client.post("/api/v1/decide", json=_payload(session_id="session-a"))
    client.post("/api/v1/decide", json=_payload(session_id="session-b"))

    assert captured[1] == []


def test_health_endpoint() -> None:
    assert client.get("/health").json()["status"] == "ok"


# --- /health가 지금 어느 AI 클라이언트로 도는지 -----------------------------
#
# 키가 없으면 서버는 조용히 Mock으로 폴백한다(로그 warning 한 줄뿐). Mock은 첫 clickable
# 요소를 무조건 누르므로, 눈치채지 못하면 무대 위에서 아무 버튼이나 누르는 그림이 나온다.
# 시연 직전에 눈으로 확인할 수 있게 노출한다.


def test_health_reports_mock_when_key_is_missing() -> None:
    # autouse fixture가 MockAIClient를 주입한 상태 = 키가 없는 상황과 같다.
    assert client.get("/health").json()["ai_client"] == "mock"


def test_health_reports_gemini_when_key_is_configured() -> None:
    class StubGeminiClient:
        PROVIDER = "gemini"

        def decide(self, goal, app_package, elements, history, user_speech=None):
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: StubGeminiClient()

    assert client.get("/health").json()["ai_client"] == "gemini"


def test_real_client_classes_declare_their_provider() -> None:
    """health의 판정 근거가 실제 구현체와 어긋나지 않는지 고정한다."""
    from backend.services.ai_client import GeminiAIClient, MockAIClient

    assert MockAIClient.PROVIDER == "mock"
    assert GeminiAIClient.PROVIDER == "gemini"


# --- TTS 문구 ---------------------------------------------------------------


@pytest.mark.parametrize(
    ("word", "expected"),
    [
        ("사진 보내기", "를"),  # 받침 없음
        ("보내기", "를"),
        ("조회", "를"),
        ("서울", "을"),  # 받침 있음
        ("부산행", "을"),
        ("확인", "을"),
        ("PDF", ""),  # 한글이 아니면 조사 생략
    ],
)
def test_object_particle_matches_final_consonant(word: str, expected: str) -> None:
    from backend.services.ai_client import _object_particle

    assert _object_particle(word) == expected


# --- 일시적 AI 오류의 재시도 가능 표시 --------------------------------------
#
# 무료 티어 소진(429)이나 일시적 5xx로 나온 UNSUPPORTED에 클라이언트가 세션을 끝내버리면
# 무대 위에서 복구가 불가능하다. "화면을 이해 못 함"과 "잠깐 실패함"을 클라이언트가 구분할 수
# 있도록 retryable 플래그로 알린다.


def test_ai_client_error_is_marked_retryable() -> None:
    class FailingAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            raise AIClientError("Gemini API error 429: quota exceeded")

    app.dependency_overrides[get_ai_client] = lambda: FailingAIClient()

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"
    assert body["retryable"] is True


def test_ai_timeout_is_marked_retryable(monkeypatch) -> None:
    monkeypatch.setattr("backend.routers.decide.AI_CLIENT_TIMEOUT_SECONDS", 0.1)

    class SlowAIClient:
        def decide(self, goal, app_package, elements, history, user_speech=None):
            time.sleep(0.5)
            return _response()

    app.dependency_overrides[get_ai_client] = lambda: SlowAIClient()

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["retryable"] is True


def test_validation_failure_is_not_retryable() -> None:
    """지어낸 node_id처럼 '다시 해도 똑같은' 실패는 재시도 대상이 아니다."""
    _stub_client(_response(target_node_id=999))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"
    assert body["retryable"] is False


# --- confidence 게이트가 override할 때의 문구 -------------------------------


def test_confidence_override_replaces_statement_with_question() -> None:
    """평서문인 채로 ASK_USER가 되면 사용자는 안내를 듣고 앱은 답변을 기다린다(엇갈림)."""
    _stub_client(_response(confidence=0.1, status="CONTINUE", voice_message="사진첩을 열게요."))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "ASK_USER"
    assert body["voice_message"] != "사진첩을 열게요."
    assert body["voice_message"].endswith("?")


def test_confidence_override_keeps_llm_question_when_already_asking() -> None:
    """LLM이 이미 되묻고 있었다면 그 질문이 화면 맥락을 담고 있으므로 살린다."""
    question = "김엄마 님과 엄마♥ 님 중 어느 분에게 보낼까요?"
    _stub_client(
        _response(
            confidence=0.1,
            status="ASK_USER",
            target_node_id=None,
            action_type=None,
            voice_message=question,
        )
    )

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["voice_message"] == question


# --- history 기록 대상 ------------------------------------------------------


def test_ask_user_turns_are_not_recorded_in_history() -> None:
    """되묻기가 연속되면 '전송 버튼을 눌렀다'는 진짜 근거가 창 밖으로 밀린다(프롬프트 v3 6번 규칙)."""
    captured: list = []

    class SpyAIClient:
        def __init__(self) -> None:
            self.calls = 0

        def decide(self, goal, app_package, elements, history, user_speech=None):
            captured.append(list(history))
            self.calls += 1
            if self.calls == 1:
                return _response(instruction="전송 버튼 클릭")
            return _response(
                status="ASK_USER", target_node_id=None, action_type=None, instruction="되물음"
            )

    spy = SpyAIClient()
    app.dependency_overrides[get_ai_client] = lambda: spy

    for _ in range(4):
        client.post("/api/v1/decide", json=_payload(session_id="session-ask-flood"))

    # 1번째(CONTINUE)만 기록되고 이후 ASK_USER 3번은 기록되지 않아야 한다.
    assert [entry.selected_text for entry in captured[-1]] == ["[CONTINUE] 전송 버튼 클릭"]


# --- SCROLL 액션 ------------------------------------------------------------


def test_scroll_action_passes_validation() -> None:
    _stub_client(_response(action_type="SCROLL", input_value=None, instruction="목록 스크롤"))

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "CONTINUE"
    assert body["action_type"] == "SCROLL"
    assert body["target_node_id"] == 1


def test_scroll_without_target_node_returns_unsupported() -> None:
    """무엇을 스크롤할지 모르면 클라이언트가 실행할 수 없다."""
    _stub_client(
        _response(action_type="SCROLL", target_node_id=None, instruction="스크롤 대상 없음")
    )

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"


def test_scrollable_flag_reaches_the_prompt() -> None:
    """LLM이 '이 컨테이너를 스크롤하면 된다'를 알려면 scrollable이 프롬프트에 실려야 한다."""
    from backend.services import prompt
    from backend.schemas.request import ElementDTO

    element = ElementDTO(
        id=1,
        text="열차 목록",
        class_name="androidx.recyclerview.widget.RecyclerView",
        clickable=False,
        scrollable=True,
        bounds=[0, 0, 100, 50],
    )
    payload = prompt.build_input("목표", "com.korail.talk", [element], None, None)

    assert '"scrollable":true' in payload


# --- resource-id ------------------------------------------------------------


def test_view_id_reaches_the_prompt_without_package_prefix() -> None:
    """resource-id는 버튼 식별에 가장 강한 신호다. 단, 패키지 접두어는 매 노드 반복돼 토큰만 먹는다."""
    from backend.schemas.request import ElementDTO
    from backend.services import prompt

    element = ElementDTO(
        id=1,
        text=None,
        class_name="android.widget.ImageView",
        clickable=True,
        view_id="com.kakao.talk:id/btn_send",
        bounds=[0, 0, 100, 50],
    )
    payload = prompt.build_input("목표", "com.kakao.talk", [element], None, None)

    assert '"vid":"btn_send"' in payload
    assert "com.kakao.talk:id/" not in payload


def test_missing_view_id_is_omitted_from_prompt() -> None:
    from backend.schemas.request import ElementDTO
    from backend.services import prompt

    element = ElementDTO(
        id=1, text="조회", class_name="android.widget.TextView", clickable=True, bounds=[0, 0, 100, 50]
    )
    payload = prompt.build_input("목표", "com.kakao.talk", [element], None, None)

    assert "vid" not in payload
