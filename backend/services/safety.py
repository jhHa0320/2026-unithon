"""안전 게이트 — 마스킹, 신뢰도 게이트, 되돌릴 수 없는 행동 게이트, 응답 검증.

설계 원칙: 위험한 요소를 LLM 목록에서 **제외하지 않는다**. 제외하면 "AI가 결제 버튼을
누른다"는 제품 정의 자체가 성립하지 않는다. 빼는 대신 게이트를 건다 (CLAUDE.md §6).
"""

import re

from backend.config import Settings
from backend.schemas.request import ElementDTO
from backend.schemas.response import DecideResponse

MASK_TOKEN = "[MASKED]"

_LOW_CONFIDENCE_QUESTION = "지금 화면에서 무엇을 해야 할지 잘 모르겠어요. 조금 더 자세히 말씀해 주시겠어요?"

_NODE_ACTIONS = {"CLICK", "SET_TEXT", "SCROLL_FORWARD", "SCROLL_BACKWARD"}


def mask_elements(elements: list[ElementDTO], settings: Settings) -> list[ElementDTO]:
    """LLM 전송 전 민감정보를 마스킹한 사본을 만든다. 원본 리스트는 수정하지 않는다."""
    patterns = [re.compile(p) for p in settings.SENSITIVE_PATTERNS]
    masked: list[ElementDTO] = []
    for element in elements:
        copy = element.model_copy()
        if copy.password:
            # 비밀번호 필드는 내용을 통째로 버린다 — 클래스명과 위치만으로 충분히 식별된다.
            copy.text = None
            copy.content_description = None
        else:
            copy.text = _mask_text(copy.text, patterns)
            copy.content_description = _mask_text(copy.content_description, patterns)
        masked.append(copy)
    return masked


def _mask_text(value: str | None, patterns: list[re.Pattern[str]]) -> str | None:
    if not value:
        return value
    for pattern in patterns:
        value = pattern.sub(MASK_TOKEN, value)
    return value


def apply_confidence_gate(response: DecideResponse, settings: Settings) -> DecideResponse:
    """confidence가 임계값 미만이면 ASK_USER로 강제 override한다 (CLAUDE.md §4-3)."""
    if response.status in ("ASK_USER", "CONFIRM_REQUIRED", "UNSUPPORTED"):
        return response
    if response.confidence >= settings.CONFIDENCE_THRESHOLD:
        return response
    return response.model_copy(
        update={
            "status": "ASK_USER",
            "action": "NONE",
            "target_node_id": None,
            "value": None,
            "instruction": _LOW_CONFIDENCE_QUESTION,
            "reason": (
                f"confidence {response.confidence:.2f} < {settings.CONFIDENCE_THRESHOLD}"
            ),
        }
    )


def is_irreversible(label: str, settings: Settings) -> bool:
    return bool(label) and any(k in label for k in settings.IRREVERSIBLE_KEYWORDS)


def apply_confirmation_gate(
    response: DecideResponse,
    elements: list[ElementDTO],
    pending_confirmation: str | None,
    settings: Settings,
) -> tuple[DecideResponse, str | None]:
    """되돌릴 수 없는 행동 게이트 (CLAUDE.md §5-2).

    반환값은 (응답, 갱신된 pending_confirmation)이다.

    - LLM이 스스로 CONFIRM_REQUIRED를 냈으면 그 대상 라벨을 기록한다.
    - LLM이 빠뜨리고 CONTINUE+CLICK을 냈는데 라벨이 IRREVERSIBLE이면 서버가 override한다.
    - 이미 확인을 요청했던 그 노드에 대해 LLM이 다시 CONTINUE를 내면, LLM이 누적된 goal에서
      동의를 읽었다는 뜻이므로 통과시키고 기록을 지운다. (없으면 서버가 영원히 되묻는다.)
    """
    target = _find(elements, response.target_node_id)

    if response.status == "CONFIRM_REQUIRED":
        return response, (target.label if target else pending_confirmation)

    if response.status != "CONTINUE" or response.action not in _NODE_ACTIONS:
        return response, pending_confirmation

    if target is None or not is_irreversible(target.label, settings):
        return response, pending_confirmation

    if pending_confirmation is not None and pending_confirmation == target.label:
        return response, None  # 동의 확인됨 → 통과

    return (
        response.model_copy(
            update={
                "status": "CONFIRM_REQUIRED",
                "action": "NONE",
                "value": None,
                "instruction": f"'{target.label}'을(를) 진행할까요?",
                "reason": "irreversible action requires spoken confirmation",
            }
        ),
        target.label,
    )


def validate_response(
    response: DecideResponse, elements: list[ElementDTO]
) -> DecideResponse:
    """LLM이 실재하지 않는 노드나 불완전한 action을 지목했는지 검사한다."""
    problem = _find_problem(response, elements)
    if problem is None:
        return response
    return response.model_copy(
        update={
            "status": "UNSUPPORTED",
            "action": "NONE",
            "target_node_id": None,
            "value": None,
            "instruction": "지금 화면에서는 대신 진행하기 어려워요. 직접 눌러 주시겠어요?",
            "reason": problem,
        }
    )


def _find_problem(response: DecideResponse, elements: list[ElementDTO]) -> str | None:
    if response.target_node_id is not None and _find(elements, response.target_node_id) is None:
        return f"target_node_id {response.target_node_id} not found in elements"
    if response.action in _NODE_ACTIONS and response.target_node_id is None:
        return f"action {response.action} requires target_node_id"
    if response.action == "SET_TEXT" and not response.value:
        return "action SET_TEXT requires value"
    if response.action == "LAUNCH_APP" and not response.value:
        return "action LAUNCH_APP requires value (package name)"
    return None


def _find(elements: list[ElementDTO], node_id: int | None) -> ElementDTO | None:
    if node_id is None:
        return None
    return next((e for e in elements if e.id == node_id), None)
