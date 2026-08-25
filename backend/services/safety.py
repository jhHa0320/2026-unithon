"""마스킹·신뢰도 게이트·응답 검증 모듈.

LLM 응답을 그대로 신뢰하지 않는다는 것이 이 모듈의 전제다.
마스킹은 LLM 호출 '전', 게이트/검증은 LLM 호출 '후'에 적용된다.

주의: 이 모듈은 전송/인증 요소를 차단하지 않는다. 전송 버튼까지 에이전트가
직접 클릭해 작업을 완결하는 것이 제품 목표이므로, 민감 요소는 '탐지 후 로깅'만
하고 LLM 전달 목록에서 제외하지 않는다. 실행 자체를 막는 게이트는
confidence 게이트(check_confidence)와 응답 검증(validate_*)뿐이다.
"""

import re

from backend.schemas.request import ElementDTO
from backend.schemas.response import DecideResponse

_RESIDENT_ID_PATTERN = re.compile(r"\d{6}-\d{7}")
_PHONE_PATTERN = re.compile(r"0\d{1,2}-?\d{3,4}-?\d{4}")
_ACCOUNT_NUMBER_PATTERN = re.compile(r"\d{8,}")
_MASK = "****"


def detect_sensitive_elements(
    elements: list[ElementDTO], sensitive_keywords: list[str]
) -> list[ElementDTO]:
    """위험 키워드(전송/인증/삭제 등)가 매칭되는 element를 '탐지'한다.

    반환값은 로깅·관측용이며 호출부는 이 결과로 elements를 걸러내지 않는다.
    전송 단계 도달 여부를 서버 로그에서 추적하기 위한 신호로만 쓴다.
    """
    detected: list[ElementDTO] = []
    for element in elements:
        combined_text = " ".join(filter(None, [element.text, element.content_description]))
        if any(keyword in combined_text for keyword in sensitive_keywords):
            detected.append(element)
    return detected


def mask_sensitive_text(elements: list[ElementDTO]) -> list[ElementDTO]:
    """전화번호, 계좌번호형(8자리 이상 연속 숫자), 주민번호형(6자리-7자리) 패턴을 마스킹한다.

    개인정보가 LLM으로 나가는 것만 막는다. 노드 자체는 그대로 전달되므로
    에이전트의 화면 조작 능력에는 영향을 주지 않는다.
    원본 element는 수정하지 않고 복사본을 반환한다.
    """
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


_FALLBACK_QUESTION = "어떻게 해야 할지 확실하지 않아요. 다시 알려주시겠어요?"


def check_confidence(response: DecideResponse, threshold: float) -> DecideResponse:
    """confidence가 threshold 미만이면 status를 ASK_USER로 강제 override한다.

    확신 없는 조작은 실행하지 않고 사용자에게 되묻는다. 전송 화면인지 여부와
    무관하게 동일한 임계값이 적용된다.

    **문구도 반드시 질문으로 바꾼다.** 예전에는 voice_message가 비어 있을 때만 대체했는데,
    LLM이 CONTINUE로 판단하며 "사진첩을 열게요" 같은 평서문을 써 둔 응답이 여기서 ASK_USER로
    강등되면 — 사용자는 안내 문장을 듣고 앱은 답변을 기다리는 엇갈림이 생긴다. 사용자는
    무엇을 답해야 할지 알 수 없어 그대로 멈춘다. 단, LLM이 이미 ASK_USER로 되묻고 있었다면
    그 질문이 화면 맥락("김엄마 님과 엄마♥ 님 중...")을 담고 있으므로 그대로 살린다.
    """
    if response.confidence >= threshold:
        return response

    llm_already_asked = response.status == "ASK_USER" and response.voice_message.strip()
    return response.model_copy(
        update={
            "target_node_id": None,
            "action_type": None,
            "input_value": None,
            "status": "ASK_USER",
            "voice_message": response.voice_message if llm_already_asked else _FALLBACK_QUESTION,
            "reason": f"confidence {response.confidence:.2f} below threshold {threshold:.2f}",
        }
    )


def validate_target_node_id(
    response: DecideResponse, elements: list[ElementDTO]
) -> DecideResponse:
    """target_node_id가 요청 elements에 실재하는 id인지 검증한다.

    LLM이 존재하지 않는 노드를 지어내는(hallucination) 경우를 차단한다.
    """
    if response.target_node_id is None:
        return response

    valid_ids = {element.id for element in elements}
    if response.target_node_id in valid_ids:
        return response

    return _to_unsupported(response, "target_node_id not found in elements")


def validate_action(response: DecideResponse) -> DecideResponse:
    """action_type과 나머지 필드의 정합성을 검증한다.

    - 조작 대상이 있으면 action_type이 반드시 있어야 클라이언트가 실행할 수 있다.
    - 반대로 action_type만 있고 대상이 없어도 실행할 수 없다(무엇을 누르거나 스크롤할지 모름).
    - SET_TEXT인데 input_value가 없으면 클라이언트가 무엇을 입력할지 알 수 없다.
    """
    if response.target_node_id is None:
        if response.action_type is not None:
            return _to_unsupported(response, "action_type given without target_node_id")
        return response

    if response.action_type is None:
        return _to_unsupported(response, "target_node_id given without action_type")

    if response.action_type == "SET_TEXT" and not response.input_value:
        return _to_unsupported(response, "SET_TEXT given without input_value")

    return response


def _to_unsupported(response: DecideResponse, reason: str) -> DecideResponse:
    return response.model_copy(
        update={
            "target_node_id": None,
            "action_type": None,
            "input_value": None,
            "status": "UNSUPPORTED",
            "voice_message": "죄송해요, 이 화면에서는 어떻게 해야 할지 모르겠어요.",
            "reason": reason,
            # 응답 자체가 틀린 경우라 같은 화면으로 다시 물어도 결과가 같다 — 재시도 대상 아님.
            "retryable": False,
        }
    )
