from fastapi.testclient import TestClient

from backend.main import app

client = TestClient(app)


def test_decide_returns_mock_response() -> None:
    payload = {
        "session_id": "test-session-1",
        "goal": "채팅방 알림 끄기",
        "app_package": "com.kakao.talk",
        "elements": [
            {
                "id": 1,
                "text": "알림 끄기",
                "content_description": None,
                "class_name": "android.widget.TextView",
                "clickable": True,
                "bounds": [0, 0, 100, 50],
            }
        ],
        "history": None,
    }

    response = client.post("/api/v1/decide", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["target_node_id"] == 1
    assert body["status"] == "CONTINUE"
