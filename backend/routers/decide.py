from fastapi import APIRouter
from backend.schemas.request import DecideRequest
from backend.schemas.response import DecideResponse, Decision

router = APIRouter(prefix="/api/v1", tags=["decide"])

@router.post("/decide", response_model=DecideResponse)
async def decide(request: DecideRequest):
    """
    안드로이드 팀이 즉시 개발을 시작할 수 있도록 하는 하드코딩된 Mock 서버입니다.
    실제 개발 시에는 Gemini API 연동 로직으로 교체될 예정입니다.
    """

    # 1. 사용자의 응답(user_speech)이 있는 경우 (결제 승인 단계)
    if request.user_speech:
        if any(word in request.user_speech for word in ["응", "어", "그래", "결제해", "좋아"]):
            return DecideResponse(
                decision=Decision(
                    target_node_id="n_mock_pay_btn", # 예시 ID
                    action_type="CLICK"
                ),
                status="DONE",
                voice_message="결제를 완료했습니다. 예매가 완료되었습니다!",
                confidence=1.0
            )

    # 2. 결제 직전 단계 시뮬레이션 (텍스트에 '결제'가 포함된 경우)
    is_payment_step = any("결제" in (el.text or "") for el in request.ui_tree)
    if is_payment_step:
        return DecideResponse(
            decision=None,
            status="WAIT_FOR_CONFIRM",
            voice_message="서울행 KTX 열차, 총 59,800원입니다. 결제할까요?",
            confidence=1.0
        )

    # 3. 일반적인 진행 단계 (첫 번째 클릭 가능한 요소를 클릭하도록 지시)
    target_node = next((el for el in request.ui_tree if el.clickable), None)

    return DecideResponse(
        decision=Decision(
            target_node_id=target_node.node_id if target_node else None,
            action_type="CLICK",
            input_value=None
        ),
        status="CONTINUE",
        voice_message="다음 단계로 이동할게요.",
        confidence=0.9
    )
