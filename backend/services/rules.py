"""규칙 기반 최적화 — LLM 호출 자체를 줄이거나, 호출당 토큰을 줄이는 계층.

화면 전환마다 1콜이 발생하는 구조라 **콜 수와 콜당 토큰이 곧 원가이자 지연**이다.
LLM에게 물어보지 않아도 되는 것은 여기서 결정론적으로 끝낸다.

여기 있는 어떤 규칙도 특정 앱을 알지 못한다 (CLAUDE.md §12). 앱 이름은 전부
기기가 준 `installed_apps` 라벨과 사용자가 말한 `goal`에서만 나온다.
"""

import hashlib
import re
import unicodedata

from backend.config import Settings
from backend.schemas.request import ElementDTO, InstalledApp

_CLASS_PREFIXES = (
    "android.widget.",
    "android.view.",
    "androidx.",
    "android.webkit.",
)

_TOKEN_SPLIT = re.compile(r"[\s,./!?~\-_·]+")


# --- 1. 요소 필터링 -----------------------------------------------------------


def _area(bounds: list[int]) -> int:
    return max(0, bounds[2] - bounds[0]) * max(0, bounds[3] - bounds[1])


def _is_actionable(element: ElementDTO) -> bool:
    return element.clickable or element.editable or element.scrollable


def filter_elements(
    elements: list[ElementDTO], settings: Settings
) -> list[ElementDTO]:
    """LLM에 보낼 가치가 있는 노드만 남긴다.

    - 면적 0 (화면 밖 / 접힌 컨테이너) 제거
    - 라벨도 없고 조작도 불가능한 순수 레이아웃 컨테이너 제거
    - 같은 라벨·같은 클래스로 겹치는 중복 노드 제거 (래퍼가 자식과 같은 라벨을 갖는 흔한 패턴)
    - **라벨 없는 clickable 노드는 남긴다** — 사진 그리드 셀처럼 이름은 없지만
      위치로 지목해야 하는 항목이다 (§build_llm_payload의 position_hint 참고)
    """
    kept: list[ElementDTO] = []
    seen: set[tuple[str, str]] = set()

    for element in elements:
        if _area(element.bounds) <= 0:
            continue
        label = element.label
        if not label and not _is_actionable(element):
            continue
        if label:
            key = (label, element.class_name)
            if key in seen:
                continue
            seen.add(key)
        kept.append(element)

    # 화면 위→아래, 왼→오른쪽 읽기 순서로 정렬 (위치 추론과 캐시 안정성 모두에 필요)
    kept.sort(key=lambda e: (e.bounds[1], e.bounds[0]))
    return kept[: settings.MAX_ELEMENTS_TO_LLM]


def _short_class(class_name: str) -> str:
    for prefix in _CLASS_PREFIXES:
        if class_name.startswith(prefix):
            return class_name[len(prefix) :]
    return class_name


def build_llm_payload(elements: list[ElementDTO]) -> list[dict]:
    """LLM에 보낼 압축 표현. null 필드 제거, 클래스명 축약, 플래그 압축.

    라벨이 없는 clickable 노드에는 `position_hint`를 붙인다 —
    "그리드의 1번째 항목"처럼 위치로 지목할 수 있게 해서, 접근성 라벨이 없는
    화면에서도 Vision 없이 진행할 수 있게 하는 장치다.
    """
    unlabeled = [e for e in elements if not e.label and e.clickable]
    hint_index = {id(e): i + 1 for i, e in enumerate(unlabeled)}

    payload: list[dict] = []
    for element in elements:
        item: dict = {"id": element.id, "class": _short_class(element.class_name)}
        if element.label:
            item["label"] = element.label
        flags = "".join(
            f
            for f, on in (
                ("c", element.clickable),
                ("e", element.editable),
                ("s", element.scrollable),
            )
            if on
        )
        if flags:
            item["flags"] = flags
        if id(element) in hint_index:
            item["position_hint"] = f"이름 없는 {hint_index[id(element)]}번째 항목"
        item["bounds"] = element.bounds
        payload.append(item)
    return payload


# --- 2. 화면 지문 (변화 감지 / 캐시 키) ---------------------------------------


def screen_signature(app_package: str | None, elements: list[ElementDTO]) -> str:
    """같은 화면이면 같은 값이 나오는 안정적인 지문.

    id는 화면 덤프마다 재부여되므로 지문에 넣지 않는다. 라벨과 클래스만 쓴다.
    """
    parts = sorted(f"{e.label}\x1f{_short_class(e.class_name)}" for e in elements)
    raw = f"{app_package or ''}\x1e" + "\x1e".join(parts)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def is_loading_screen(elements: list[ElementDTO]) -> bool:
    """조작 가능한 요소가 하나도 없는 화면 — 아직 로딩 중이다.

    LLM에게 물어봐야 답이 없다. 호출을 건너뛰고 다음 화면 변경을 기다린다.
    """
    return not any(_is_actionable(e) for e in elements)


# --- 3. 규칙 기반 앱 선택 (LLM 콜 1회 절약) ------------------------------------


def _normalize(value: str) -> str:
    """비교용 정규화. 공백은 여기서 지우지 않는다 — goal을 단어로 쪼개야 하기 때문."""
    return unicodedata.normalize("NFKC", value).lower()


def _normalize_label(value: str) -> str:
    """앱 라벨은 공백까지 제거해 비교한다 ("Google 포토" -> "google포토")."""
    return re.sub(r"\s+", "", _normalize(value))


def _is_subsequence(needle: str, haystack: str) -> bool:
    it = iter(haystack)
    return all(ch in it for ch in needle)


def _score(token: str, label: str) -> int:
    """한 단어와 앱 라벨의 유사도.

    한국어는 조사가 붙는다 ("카톡으로", "네이버에서"). 조사 사전을 두는 대신
    **토큰의 접두어를 길이 순으로 대조**한다 — "카톡으로"의 접두어 "카톡"이
    "카카오톡"의 부분수열이므로 매칭된다. 특정 앱 지식이 아니라 순수 문자열 규칙이다.
    """
    if not token or not label:
        return 0
    for length in range(len(token), 1, -1):
        prefix = token[:length]
        if prefix == label:
            return 100
        if prefix in label or label in prefix:
            return 80
        # 짧은 접두어가 아주 긴 라벨에 우연히 걸리는 것을 막는다
        if len(label) <= 3 * len(prefix) and _is_subsequence(prefix, label):
            return 60
    return 0


def resolve_app(
    goal: str, installed_apps: list[InstalledApp] | None, settings: Settings
) -> str | None:
    """goal에서 앱 이름을 직접 짚어낼 수 있으면 LLM 없이 패키지명을 반환한다.

    앱 이름을 아는 게 아니라 **기기가 준 라벨과 사용자 발화를 대조**할 뿐이다.
    확신이 없거나 후보가 둘 이상 동점이면 None을 반환해 LLM에게 넘긴다 (fail-safe).
    """
    if not settings.ENABLE_RULE_APP_RESOLUTION or not installed_apps:
        return None

    tokens = [t for t in _TOKEN_SPLIT.split(_normalize(goal)) if len(t) >= 2]
    if not tokens:
        return None

    scored: list[tuple[int, str]] = []
    for app in installed_apps:
        label = _normalize_label(app.label)
        best = max((_score(t, label) for t in tokens), default=0)
        if best >= settings.APP_MATCH_MIN_SCORE:
            scored.append((best, app.package))

    if not scored:
        return None
    scored.sort(key=lambda x: -x[0])
    if len(scored) > 1 and scored[0][0] == scored[1][0]:
        return None  # 동점 — 애매하면 LLM에게 넘긴다
    return scored[0][1]


# --- 4. Vision fallback이 필요한 화면인지 판정 (Phase 7) -----------------------


def unlabeled_ratio(elements: list[ElementDTO]) -> float:
    """조작 가능한 노드 중 라벨이 없는 것의 비율."""
    actionable = [e for e in elements if e.clickable or e.editable]
    if not actionable:
        return 0.0
    return sum(1 for e in actionable if not e.label) / len(actionable)


def needs_vision(elements: list[ElementDTO], settings: Settings) -> bool:
    """접근성 트리만으로 화면을 해석할 수 없는지 규칙으로 판정한다.

    Vision은 비싸고 느리다(§docs/ARCHITECTURE.md의 비교표). 매 스텝 켜는 게 아니라
    **이 규칙이 참인 화면에서만** 스크린샷을 덧붙인다.

    주의: 라벨이 없어도 `build_llm_payload`의 position_hint("이름 없는 N번째 항목")로
    지목이 가능한 경우가 많다. Vision은 위치만으로 고를 수 없을 때의 최후 수단이다.
    """
    return unlabeled_ratio(elements) >= settings.VISION_UNLABELED_RATIO
