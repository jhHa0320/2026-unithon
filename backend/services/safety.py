import re

from backend.schemas.request import ElementDTO
from backend.schemas.response import DecideResponse

_RESIDENT_ID_PATTERN = re.compile(r"\d{6}-\d{7}")
_PHONE_PATTERN = re.compile(r"0\d{1,2}-?\d{3,4}-?\d{4}")
_ACCOUNT_NUMBER_PATTERN = re.compile(r"\d{8,}")
_MASK = "****"


def filter_sensitive_elements(
    elements: list[ElementDTO], sensitive_keywords: list[str]
) -> tuple[list[ElementDTO], list[ElementDTO]]:
    """위험 키워드(송금/결제/삭제/인증 등)가 text/content_description에 매칭되는 element를 분리한다.

    Returns:
        (safe_elements, sensitive_elements) — safe_elements만 LLM에 전달한다.
        sensitive_elements는 clickable=False로 강제되어 안내 대상에서 제외된다.
    """
    safe_elements: list[ElementDTO] = []
    sensitive_elements: list[ElementDTO] = []

    for element in elements:
        combined_text = " ".join(filter(None, [element.text, element.content_description]))
        if any(keyword in combined_text for keyword in sensitive_keywords):
            sensitive_elements.append(element.model_copy(update={"clickable": False}))
        else:
            safe_elements.append(element)

    return safe_elements, sensitive_elements


def check_confidence(response: DecideResponse, threshold: float) -> DecideResponse:
    """confidence가 threshold 미만이면 status를 ASK_USER로 강제 override한다."""
    if response.confidence < threshold:
        return response.model_copy(update={"status": "ASK_USER", "target_node_id": None})
    return response


def mask_sensitive_text(elements: list[ElementDTO]) -> list[ElementDTO]:
    """전화번호, 계좌번호형(8자리 이상 연속 숫자), 주민번호형(6자리-7자리) 패턴을 마스킹한다."""
    return [
        element.model_copy(
            update={
                "text": _mask_value(element.text),
                "content_description": _mask_value(element.content_description),
            }
        )
        for element in elements
    ]


def _mask_value(value: str | None) -> str | None:
    if value is None:
        return None
    masked = _RESIDENT_ID_PATTERN.sub(_MASK, value)
    masked = _PHONE_PATTERN.sub(_MASK, masked)
    masked = _ACCOUNT_NUMBER_PATTERN.sub(_MASK, masked)
    return masked


def validate_target_node_id(
    response: DecideResponse, original_elements: list[ElementDTO]
) -> DecideResponse:
    """target_node_id가 원본(safety 필터링 전) elements에 실재하는 id인지 검증한다."""
    if response.target_node_id is None:
        return response

    valid_ids = {element.id for element in original_elements}
    if response.target_node_id not in valid_ids:
        return response.model_copy(
            update={
                "target_node_id": None,
                "status": "UNSUPPORTED",
                "reason": "target_node_id not found in elements",
            }
        )
    return response
