import logging
import time

import pytest
from fastapi.testclient import TestClient

from backend.config import Settings, get_settings
from backend.main import app
from backend.routers.decide import get_ai_client
from backend.schemas.response import DecideResponse
from backend.services.session import session_manager

client = TestClient(app)

BASE_ELEMENT = {
    "id": 1,
    "text": "검색",
    "content_description": None,
    "class_name": "android.widget.TextView",
    "clickable": True,
    "editable": False,
    "scrollable": False,
    "password": False,
    "bounds": [0, 0, 100, 50],
}


def _element(**overrides) -> dict:
    return {**BASE_ELEMENT, **overrides}


def _payload(
    elements: list[dict] | None = None,
    session_id: str = "test-session",
    app_package: str | None = "com.kakao.talk",
    goal: str = "영희한테 카톡으로 방금 찍은 사진 보내줘",
    installed_apps: list[dict] | None = None,
) -> dict:
    return {
        "session_id": session_id,
        "goal": goal,
        "app_package": app_package,
        "elements": elements if elements is not None else [_element()],
        "installed_apps": installed_apps,
        "history": None,
    }


class StubAIClient:
    """지정한 응답을 그대로 반환하고, 마지막으로 받은 elements를 기록하는 스텁."""

    def __init__(self, response: DecideResponse) -> None:
        self.response = response
        self.received_elements = None

    def decide(self, *, goal, app_package, elements, installed_apps, history):
        self.received_elements = elements
        return self.response


def _use(ai_client) -> None:
    app.dependency_overrides[get_ai_client] = lambda: ai_client


def _use_settings(**overrides) -> None:
    settings = Settings(**overrides)
    app.dependency_overrides[get_settings] = lambda: settings


@pytest.fixture(autouse=True)
def _clean_state():
    session_manager.reset()
    yield
    app.dependency_overrides.clear()
    session_manager.reset()


# --- 앱 선택 (첫 스텝) -------------------------------------------------------


def test_launches_app_when_no_app_package() -> None:
    response = client.post(
        "/api/v1/decide",
        json=_payload(
            elements=[],
            app_package=None,
            installed_apps=[{"package": "com.kakao.talk", "label": "카카오톡"}],
        ),
    )

    assert response.status_code == 200
    body = response.json()
    assert body["action"] == "LAUNCH_APP"
    assert body["value"] == "com.kakao.talk"
    assert body["status"] == "CONTINUE"


def test_empty_elements_allowed_without_app_package() -> None:
    response = client.post(
        "/api/v1/decide", json=_payload(elements=[], app_package=None)
    )

    assert response.status_code == 200


def test_empty_elements_rejected_with_app_package() -> None:
    response = client.post("/api/v1/decide", json=_payload(elements=[]))

    assert response.status_code == 422


def test_invalid_bounds_returns_422() -> None:
    response = client.post(
        "/api/v1/decide", json=_payload(elements=[_element(bounds=[100, 0, 0, 50])])
    )

    assert response.status_code == 422


# --- 정상 진행 ---------------------------------------------------------------


def test_decide_returns_continue_on_normal_case() -> None:
    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CONTINUE"
    assert body["action"] == "CLICK"
    assert body["target_node_id"] == 1


def test_ai_client_timeout_returns_unsupported() -> None:
    _use_settings(AI_CLIENT_TIMEOUT_SECONDS=0.1)

    class SlowAIClient:
        def decide(self, *, goal, app_package, elements, installed_apps, history):
            time.sleep(0.5)
            raise AssertionError("should have timed out")

    _use(SlowAIClient())

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UNSUPPORTED"
    assert body["reason"] == "AI 응답 지연"


# --- 신뢰도 게이트 (CLAUDE.md §4-3) -----------------------------------------


def test_low_confidence_forces_ask_user() -> None:
    _use(
        StubAIClient(
            DecideResponse(
                action="CLICK",
                target_node_id=1,
                value=None,
                instruction="검색을 누를게요.",
                confidence=0.2,
                status="CONTINUE",
            )
        )
    )

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "ASK_USER"
    assert body["action"] == "NONE"
    assert body["target_node_id"] is None
    assert "confidence" in body["reason"]


# --- 되돌릴 수 없는 행동 게이트 (CLAUDE.md §5-2) -----------------------------


def test_irreversible_click_forces_confirm_required() -> None:
    send_button = _element(id=42, text="전송")
    _use(
        StubAIClient(
            DecideResponse(
                action="CLICK",
                target_node_id=42,
                value=None,
                instruction="전송할게요.",
                confidence=0.95,
                status="CONTINUE",
            )
        )
    )

    body = client.post("/api/v1/decide", json=_payload(elements=[send_button])).json()

    assert body["status"] == "CONFIRM_REQUIRED"
    assert body["action"] == "NONE"
    assert "전송" in body["instruction"]


def test_click_passes_after_confirmation_was_requested() -> None:
    send_button = _element(id=42, text="전송")
    stub = StubAIClient(
        DecideResponse(
            action="CLICK",
            target_node_id=42,
            value=None,
            instruction="전송할게요.",
            confidence=0.95,
            status="CONTINUE",
        )
    )
    _use(stub)

    first = client.post("/api/v1/decide", json=_payload(elements=[send_button])).json()
    assert first["status"] == "CONFIRM_REQUIRED"

    # 사용자가 동의해 goal이 누적된 뒤 LLM이 다시 CONTINUE를 반환하는 상황
    second = client.post(
        "/api/v1/decide",
        json=_payload(
            elements=[send_button],
            goal="영희한테 카톡으로 방금 찍은 사진 보내줘. 네, 보내주세요.",
        ),
    ).json()

    assert second["status"] == "CONTINUE"
    assert second["action"] == "CLICK"
    assert second["target_node_id"] == 42


def test_llm_confirm_required_is_passed_through() -> None:
    send_button = _element(id=42, text="전송")
    _use(
        StubAIClient(
            DecideResponse(
                action="NONE",
                target_node_id=42,
                value=None,
                instruction="영희님께 사진을 보낼까요?",
                confidence=0.95,
                status="CONFIRM_REQUIRED",
            )
        )
    )

    body = client.post("/api/v1/decide", json=_payload(elements=[send_button])).json()

    assert body["status"] == "CONFIRM_REQUIRED"
    assert body["instruction"] == "영희님께 사진을 보낼까요?"


def test_ordinary_click_is_not_gated() -> None:
    body = client.post(
        "/api/v1/decide", json=_payload(elements=[_element(id=7, text="친구")])
    ).json()

    assert body["status"] == "CONTINUE"


# --- 응답 검증 ---------------------------------------------------------------


def test_unknown_target_node_returns_unsupported() -> None:
    _use(
        StubAIClient(
            DecideResponse(
                action="CLICK",
                target_node_id=999,
                value=None,
                instruction="누를게요.",
                confidence=0.95,
                status="CONTINUE",
            )
        )
    )

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"
    assert "999" in body["reason"]


def test_set_text_without_value_returns_unsupported() -> None:
    _use(
        StubAIClient(
            DecideResponse(
                action="SET_TEXT",
                target_node_id=1,
                value=None,
                instruction="입력할게요.",
                confidence=0.95,
                status="CONTINUE",
            )
        )
    )

    body = client.post("/api/v1/decide", json=_payload()).json()

    assert body["status"] == "UNSUPPORTED"


# --- 민감정보 마스킹 (CLAUDE.md §4-4) ---------------------------------------


def test_password_field_text_is_not_sent_to_llm() -> None:
    stub = StubAIClient(
        DecideResponse(
            action="NONE",
            target_node_id=None,
            value=None,
            instruction="확인했어요.",
            confidence=0.9,
            status="DONE",
        )
    )
    _use(stub)

    # 실제 비밀번호 필드는 EditText다 — editable이어야 규칙 필터를 통과한다
    secret = _element(
        id=5, text="hunter2", password=True, clickable=True, editable=True,
        class_name="android.widget.EditText",
    )
    client.post("/api/v1/decide", json=_payload(elements=[secret]))

    assert stub.received_elements[0].text is None


def test_resident_number_is_masked_before_llm() -> None:
    stub = StubAIClient(
        DecideResponse(
            action="NONE",
            target_node_id=None,
            value=None,
            instruction="확인했어요.",
            confidence=0.9,
            status="DONE",
        )
    )
    _use(stub)

    node = _element(id=6, text="주민번호 900101-1234567")
    client.post("/api/v1/decide", json=_payload(elements=[node]))

    assert "900101-1234567" not in stub.received_elements[0].text
    assert "[MASKED]" in stub.received_elements[0].text


def test_logs_do_not_contain_sensitive_text(caplog) -> None:
    with caplog.at_level(logging.INFO):
        client.post("/api/v1/decide", json=_payload())

    for record in caplog.records:
        assert "검색" not in record.getMessage()
        assert not hasattr(record, "text")
        assert not hasattr(record, "content_description")
