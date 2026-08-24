from backend.schemas.request import ElementDTO
from backend.schemas.response import DecideResponse


def filter_sensitive_elements(elements: list[ElementDTO]) -> list[ElementDTO]:
    """위험 키워드(송금/결제/삭제/인증 등) 매칭 element를 LLM 전달 목록에서 제외한다.

    TODO: config.SENSITIVE_KEYWORDS 기준으로 text/content_description을 검사해 필터링.
    """
    return elements


def check_confidence(response: DecideResponse, threshold: float) -> DecideResponse:
    """confidence가 threshold 미만이면 status를 ASK_USER로 강제 override한다.

    TODO: response.confidence < threshold 인 경우 status="ASK_USER"로 교체.
    """
    return response


def mask_sensitive_text(elements: list[ElementDTO]) -> list[ElementDTO]:
    """비밀번호/주민번호/계좌번호 등 민감 텍스트를 LLM 전송 전에 마스킹한다.

    TODO: 정규식 등으로 민감 패턴을 탐지해 text/content_description을 마스킹.
    """
    return elements
