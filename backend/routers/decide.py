import asyncio
import time

from fastapi import APIRouter, Depends

from backend.config import Settings, get_settings
from backend.core.logging import get_logger
from backend.schemas.request import DecideRequest
from backend.schemas.response import DecideResponse
from backend.services import safety
from backend.services.ai_client import AIClient, MockAIClient
from backend.services.session import session_manager

router = APIRouter(prefix="/api/v1", tags=["decide"])
logger = get_logger(__name__)

AI_CLIENT_TIMEOUT_SECONDS = 5.0


def get_ai_client() -> AIClient:
    """AI 클라이언트 주입 지점. B-2에서 GeminiAIClient로 교체하면 나머지는 그대로 동작한다."""
    return MockAIClient()


@router.post("/decide", response_model=DecideResponse)
async def decide(
    request: DecideRequest,
    settings: Settings = Depends(get_settings),
    ai_client: AIClient = Depends(get_ai_client),
) -> DecideResponse:
    start_time = time.perf_counter()

    # 1. 요청 검증 — elements 빈 배열/bounds 정합성은 schemas/request.py validator가 처리(위반 시 422)

    # 2. 민감 요소 탐지 — 로깅용 신호일 뿐, elements를 걸러내지 않는다(결제까지 자동 진행)
    sensitive_elements = safety.detect_sensitive_elements(
        request.elements, settings.SENSITIVE_KEYWORDS
    )

    # 3. 세션 로드 — 요청에 history가 실려오면 그것을 우선하고, 없으면 서버 세션에서 조회
    history = request.history or session_manager.get_history(request.session_id)

    # 4. 개인정보 마스킹 — LLM에 나가는 텍스트에서 전화번호/계좌번호/주민번호 패턴 제거
    masked_elements = safety.mask_sensitive_text(request.elements)

    # 5. LLM 호출 — 동기 구현체를 스레드로 offload하고 타임아웃을 건다
    try:
        response = await asyncio.wait_for(
            asyncio.to_thread(
                ai_client.decide,
                goal=request.goal,
                app_package=request.app_package,
                elements=masked_elements,
                history=history,
                user_speech=request.user_speech,
            ),
            timeout=AI_CLIENT_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        # 타임아웃은 게이트/검증을 거치지 않는 최종 응답이다.
        response = DecideResponse(
            target_node_id=None,
            action_type=None,
            input_value=None,
            instruction="AI 응답이 지연되어 이번 단계를 처리할 수 없음",
            voice_message="잠시 응답이 늦어지고 있어요. 다시 시도해 주세요.",
            confidence=0.0,
            status="UNSUPPORTED",
            reason="AI 응답 지연",
        )
    else:
        # 6. confidence 게이트 — 임계값 미만이면 ASK_USER로 강제 override
        response = safety.check_confidence(response, settings.CONFIDENCE_THRESHOLD)

        # 7. 응답 검증 — 지어낸 node_id 차단 후, action_type/input_value 정합성 확인
        response = safety.validate_target_node_id(response, request.elements)
        response = safety.validate_action(response)

    # 8. 로깅 — text/content_description 원문은 어떤 경우에도 기록하지 않는다
    latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
    logger.info(
        "decide request processed",
        extra={
            "session_id": request.session_id,
            "app_package": request.app_package,
            "target_node_id": response.target_node_id,
            "action_type": response.action_type,
            "confidence": response.confidence,
            "status": response.status,
            "elements_count": len(request.elements),
            "sensitive_elements_count": len(sensitive_elements),
            "has_user_speech": request.user_speech is not None,
            "latency_ms": latency_ms,
        },
    )

    # 9. 세션 갱신 — 이번 step 결과를 history에 추가, 최근 N개만 유지
    session_manager.update_history(request.session_id, _history_summary(response))

    # 10. 응답 반환
    return response


def _history_summary(response: DecideResponse) -> str:
    """다음 턴의 LLM 프롬프트에 넣을 한 줄 요약. 화면 원문 텍스트는 담지 않는다."""
    if response.target_node_id is None:
        return f"[{response.status}] {response.instruction}"
    return f"[{response.status}] node={response.target_node_id} action={response.action_type}"
