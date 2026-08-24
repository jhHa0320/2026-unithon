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


def get_ai_client() -> AIClient:
    # TODO: 실제 LLM 클라이언트 구현체로 교체
    return MockAIClient()


@router.post("/decide", response_model=DecideResponse)
def decide(
    request: DecideRequest,
    settings: Settings = Depends(get_settings),
    ai_client: AIClient = Depends(get_ai_client),
) -> DecideResponse:
    # 1. 요청 수신 및 pydantic 검증
    #    - elements 빈 배열 체크, bounds 정합성 검증은 schemas/request.py의 validator에서 처리됨

    # 2. safety 필터링 — 위험 키워드 매칭 element를 LLM 전달 목록에서 제외
    filtered_elements = safety.filter_sensitive_elements(request.elements)

    # 3. 세션 로드 — session_id로 history(최근 3개) 조회
    history = session_manager.get_history(request.session_id)

    # 4. 민감 텍스트 마스킹
    masked_elements = safety.mask_sensitive_text(filtered_elements)

    # 5. LLM 호출
    response = ai_client.decide(
        goal=request.goal,
        app_package=request.app_package,
        elements=masked_elements,
        history=history,
    )

    # 6. confidence 게이트 — 임계값 미만이면 ASK_USER로 강제 override
    response = safety.check_confidence(response, settings.CONFIDENCE_THRESHOLD)

    # 7. 응답 검증 — target_node_id가 원본 elements에 실재하는지 확인, 없으면 UNSUPPORTED
    # TODO: response.target_node_id가 request.elements의 id 목록에 없으면 status="UNSUPPORTED"로 교체

    # 8. 로깅 — text/content_description 원문은 제외하고 기록 (core/logging.py의 필터가 처리)
    logger.info(
        "decide request processed",
        extra={"session_id": request.session_id, "status": response.status},
    )

    # 9. 세션 갱신
    # TODO: 이번 스텝 결과로 HistoryEntry를 생성해 session_manager.update_history 호출

    # 10. 응답 반환
    return response
