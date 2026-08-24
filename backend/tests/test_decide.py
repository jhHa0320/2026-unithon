import logging
import time

from fastapi.testclient import TestClient

from backend.main import app
from backend.routers.decide import get_ai_client
from backend.schemas.response import DecideResponse

client = TestClient(app)

BASE_ELEMENT = {
    "id": 1,
    "text": "알림 끄기",
    "content_description": None,
    "class_name": "android.widget.TextView",
    "clickable": True,
    "bounds": [0, 0, 100, 50],
}


def _payload(elements: list[dict] | None = None, session_id: str = "test-session") -> dict:
    return {
        "session_id": session_id,
        "goal": "채팅방 알림 끄기",
        "app_package": "com.kakao.talk",
        "elements": elements if elements is not None else [dict(BASE_ELEMENT)],
        "history": None,
    }


def teardown_function() -> None:
    app.dependency_overrides.pop(get_ai_client, None)


def test_decide_returns_continue_on_normal_case() -> None:
    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "CONTINUE"
    assert body["target_node_id"] == 1


def test_sensitive_keyword_element_excluded_from_ai_request() -> None:
    captured: dict = {}

    class CapturingAIClient:
        def decide(self, goal, app_package, elements, history):
            captured["elements"] = elements
            return DecideResponse(
                target_node_id=1,
                instruction="ok",
                confidence=0.99,
                status="CONTINUE",
                reason=None,
            )

    app.dependency_overrides[get_ai_client] = lambda: CapturingAIClient()

    elements = [
        dict(BASE_ELEMENT),
        {
            "id": 2,
            "text": "송금하기",
            "content_description": None,
            "class_name": "android.widget.Button",
            "clickable": True,
            "bounds": [0, 60, 100, 110],
        },
    ]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    assert response.status_code == 200
    sent_ids = {element.id for element in captured["elements"]}
    assert 2 not in sent_ids
    assert 1 in sent_ids


def test_low_confidence_overrides_to_ask_user() -> None:
    class LowConfidenceAIClient:
        def decide(self, goal, app_package, elements, history):
            return DecideResponse(
                target_node_id=1,
                instruction="not sure",
                confidence=0.1,
                status="CONTINUE",
                reason=None,
            )

    app.dependency_overrides[get_ai_client] = lambda: LowConfidenceAIClient()

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ASK_USER"
    assert body["target_node_id"] is None


def test_target_node_id_not_in_elements_overrides_to_unsupported() -> None:
    class WrongTargetAIClient:
        def decide(self, goal, app_package, elements, history):
            return DecideResponse(
                target_node_id=999,
                instruction="wrong",
                confidence=0.99,
                status="CONTINUE",
                reason=None,
            )

    app.dependency_overrides[get_ai_client] = lambda: WrongTargetAIClient()

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UNSUPPORTED"
    assert body["reason"] == "target_node_id not found in elements"


def test_empty_elements_returns_422() -> None:
    response = client.post("/api/v1/decide", json=_payload(elements=[]))

    assert response.status_code == 422


def test_invalid_bounds_returns_422() -> None:
    elements = [{**BASE_ELEMENT, "bounds": [100, 0, 0, 50]}]

    response = client.post("/api/v1/decide", json=_payload(elements=elements))

    assert response.status_code == 422


def test_ai_client_timeout_returns_unsupported(monkeypatch) -> None:
    monkeypatch.setattr("backend.routers.decide.AI_CLIENT_TIMEOUT_SECONDS", 0.1)

    class SlowAIClient:
        def decide(self, goal, app_package, elements, history):
            time.sleep(0.5)
            return DecideResponse(
                target_node_id=1,
                instruction="too late",
                confidence=0.99,
                status="CONTINUE",
                reason=None,
            )

    app.dependency_overrides[get_ai_client] = lambda: SlowAIClient()

    response = client.post("/api/v1/decide", json=_payload())

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "UNSUPPORTED"
    assert body["reason"] == "AI 응답 지연"


def test_logs_do_not_contain_sensitive_text(caplog) -> None:
    with caplog.at_level(logging.INFO):
        client.post("/api/v1/decide", json=_payload())

    for record in caplog.records:
        assert "알림 끄기" not in record.getMessage()
        assert not hasattr(record, "text")
        assert not hasattr(record, "content_description")
