"""규칙 계층 테스트 — LLM 없이 결정론적으로 처리되는 부분."""

import pytest
from fastapi.testclient import TestClient

from backend.config import Settings, get_settings
from backend.main import app
from backend.routers.decide import get_ai_client
from backend.schemas.request import ElementDTO, InstalledApp
from backend.schemas.response import DecideResponse
from backend.services import rules
from backend.services.session import session_manager

client = TestClient(app)
SETTINGS = Settings()


def _el(id: int, label: str | None = None, cls: str = "android.widget.TextView",
        clickable: bool = True, editable: bool = False, scrollable: bool = False,
        bounds: list[int] | None = None) -> ElementDTO:
    return ElementDTO(
        id=id, text=label, content_description=None, class_name=cls,
        clickable=clickable, editable=editable, scrollable=scrollable,
        bounds=bounds or [0, id * 10, 100, id * 10 + 9],
    )


@pytest.fixture(autouse=True)
def _clean():
    session_manager.reset()
    yield
    app.dependency_overrides.clear()
    session_manager.reset()


# --- 요소 필터링 -------------------------------------------------------------


def test_filter_drops_zero_area_and_pure_containers() -> None:
    elements = [
        _el(1, "검색"),
        _el(2, None, cls="android.widget.FrameLayout", clickable=False),   # 라벨X 조작X
        _el(3, "친구", bounds=[0, 0, 0, 0]),                                # 면적 0
    ]
    kept = rules.filter_elements(elements, SETTINGS)
    assert [e.id for e in kept] == [1]


def test_filter_keeps_unlabeled_clickable_nodes() -> None:
    """사진 그리드 셀처럼 라벨은 없지만 눌러야 하는 항목은 살아남아야 한다."""
    elements = [_el(i, None, cls="android.widget.ImageView") for i in (1, 2, 3)]
    kept = rules.filter_elements(elements, SETTINGS)
    assert [e.id for e in kept] == [1, 2, 3]


def test_filter_dedupes_wrapper_with_same_label() -> None:
    elements = [
        _el(1, "전송", cls="android.widget.Button"),
        _el(2, "전송", cls="android.widget.Button"),
    ]
    assert len(rules.filter_elements(elements, SETTINGS)) == 1


def test_filter_sorts_in_reading_order() -> None:
    elements = [
        _el(1, "아래", bounds=[0, 500, 100, 560]),
        _el(2, "위", bounds=[0, 100, 100, 160]),
    ]
    assert [e.id for e in rules.filter_elements(elements, SETTINGS)] == [2, 1]


# --- LLM 페이로드 압축 + 위치 힌트 -------------------------------------------


def test_payload_is_compact_and_hints_unlabeled_items() -> None:
    elements = [
        _el(1, "검색", cls="android.widget.EditText", editable=True),
        _el(2, None, cls="android.widget.ImageView"),
        _el(3, None, cls="android.widget.ImageView"),
    ]
    payload = rules.build_llm_payload(rules.filter_elements(elements, SETTINGS))

    assert payload[0]["class"] == "EditText"          # 패키지 접두어 제거
    assert payload[0]["flags"] == "ce"                # clickable + editable
    assert "label" not in payload[1]                  # null 필드 제거
    assert payload[1]["position_hint"] == "이름 없는 1번째 항목"
    assert payload[2]["position_hint"] == "이름 없는 2번째 항목"


# --- 화면 지문 ---------------------------------------------------------------


def test_signature_is_stable_across_reassigned_ids() -> None:
    """id는 덤프마다 재부여되므로 지문에 영향을 주면 안 된다."""
    a = [_el(1, "검색"), _el(2, "친구")]
    b = [_el(77, "검색"), _el(88, "친구", bounds=[0, 20, 100, 29])]
    b[0].bounds = [0, 10, 100, 19]
    assert rules.screen_signature("com.x", a) == rules.screen_signature("com.x", b)


def test_signature_changes_with_screen_content() -> None:
    a = [_el(1, "검색")]
    b = [_el(1, "전송")]
    assert rules.screen_signature("com.x", a) != rules.screen_signature("com.x", b)


# --- 규칙 기반 앱 선택 --------------------------------------------------------

APPS = [
    InstalledApp(package="com.kakao.talk", label="카카오톡"),
    InstalledApp(package="com.nhn.android.search", label="네이버"),
    InstalledApp(package="com.google.android.apps.photos", label="Google 포토"),
]


@pytest.mark.parametrize(
    "goal,expected",
    [
        ("카카오톡으로 사진 보내줘", "com.kakao.talk"),
        ("카톡으로 사진 보내줘", "com.kakao.talk"),          # 부분 일치(subsequence)
        ("네이버에서 날씨 검색해줘", "com.nhn.android.search"),
        ("영희한테 사진 보내줘", None),                       # 앱 언급 없음 → LLM에게
    ],
)
def test_rule_app_resolution(goal: str, expected: str | None) -> None:
    assert rules.resolve_app(goal, APPS, SETTINGS) == expected


def test_app_resolution_returns_none_without_installed_apps() -> None:
    assert rules.resolve_app("카카오톡 열어줘", None, SETTINGS) is None


# --- 라우터 통합: 규칙이 실제로 LLM 콜을 건너뛰는가 ---------------------------


class CountingAIClient:
    def __init__(self) -> None:
        self.calls = 0

    def decide(self, *, goal, app_package, elements, installed_apps, history):
        self.calls += 1
        return DecideResponse(
            action="CLICK", target_node_id=1, value=None,
            instruction="누를게요.", confidence=0.9, status="CONTINUE",
        )


def _use(c) -> None:
    app.dependency_overrides[get_ai_client] = lambda: c


def test_app_resolution_skips_llm_entirely() -> None:
    counter = CountingAIClient()
    _use(counter)

    body = client.post("/api/v1/decide", json={
        "session_id": "r1", "goal": "카톡으로 영희한테 사진 보내줘",
        "app_package": None, "elements": [],
        "installed_apps": [a.model_dump() for a in APPS], "history": None,
    }).json()

    assert body["action"] == "LAUNCH_APP"
    assert body["value"] == "com.kakao.talk"
    assert counter.calls == 0          # LLM 콜 0회


def test_repeated_identical_request_uses_cache() -> None:
    counter = CountingAIClient()
    _use(counter)
    payload = {
        "session_id": "r2", "goal": "영희한테 사진 보내줘",
        "app_package": "com.x",
        "elements": [_el(1, "검색").model_dump()],
        "installed_apps": None, "history": None,
    }

    first = client.post("/api/v1/decide", json=payload).json()
    second = client.post("/api/v1/decide", json=payload).json()

    assert first == second
    assert counter.calls == 1          # 두 번째는 캐시 — 중복 이벤트로 인한 낭비 방지


def test_loading_screen_skips_llm() -> None:
    counter = CountingAIClient()
    _use(counter)

    body = client.post("/api/v1/decide", json={
        "session_id": "r3", "goal": "영희한테 사진 보내줘", "app_package": "com.x",
        "elements": [_el(1, "로딩 중", clickable=False).model_dump()],
        "installed_apps": None, "history": None,
    }).json()

    assert body["status"] == "CONTINUE" and body["action"] == "NONE"
    assert counter.calls == 0


def test_stuck_screen_breaks_the_loop() -> None:
    """같은 화면이 계속 반복되면 UNSUPPORTED로 루프를 끊는다."""
    counter = CountingAIClient()
    _use(counter)

    last = None
    for i in range(6):
        # goal을 매번 바꿔 캐시를 우회하고, 화면(지문)만 동일하게 유지
        last = client.post("/api/v1/decide", json={
            "session_id": "r4", "goal": f"영희한테 사진 보내줘 {i}",
            "app_package": "com.x",
            "elements": [_el(1, "검색").model_dump()],
            "installed_apps": None, "history": None,
        }).json()

    assert last["status"] == "UNSUPPORTED"
    assert "repeated" in last["reason"]


# --- Vision fallback 판정 ------------------------------------------------------


def test_labeled_screen_does_not_need_vision() -> None:
    elements = [_el(1, "검색"), _el(2, "친구"), _el(3, "채팅")]
    assert rules.needs_vision(elements, SETTINGS) is False


def test_unlabeled_grid_flags_vision_candidate() -> None:
    """사진 그리드처럼 라벨 없는 clickable이 대부분이면 Vision 후보."""
    elements = [_el(i, None, cls="android.widget.ImageView") for i in range(1, 10)]
    assert rules.needs_vision(elements, SETTINGS) is True
    assert rules.unlabeled_ratio(elements) == 1.0
