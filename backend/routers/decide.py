import asyncio
import time

from fastapi import APIRouter, Depends

from backend.config import Settings, get_settings
from backend.core.logging import get_logger
from backend.schemas.request import DecideRequest
from backend.schemas.response import DecideResponse
from backend.services.ai_client import AIClient, MockAIClient
from backend.services.session import session_manager

router = APIRouter(prefix="/api/v1", tags=["decide"])
logger = get_logger(__name__)

AI_CLIENT_TIMEOUT_SECONDS = 5.0


def get_ai_client() -> AIClient:
    # TODO: 실제 LLM 클라이언트 구현체로 교체
    return MockAIClient()


@router.post("/decide", response_model=DecideResponse)
async def decide(
    request: DecideRequest,
    settings: Settings = Depends(get_settings),
    ai_client: AIClient = Depends(get_ai_client),
) -> DecideResponse:
    start_time = time.perf_counter()

    # 1. 요청 수신 및 pydantic 검증
    #    - elements 빈 배열 체크, bounds 정합성 검증은 schemas/request.py의 validator에서 처리됨 (위반 시 422)

    # 2. 세션 로드 — session_id로 history(최근 3개) 조회. 없으면 빈 history(새 세션)로 취급
    history = session_manager.get_history(request.session_id)

    # 3. LLM 호출 (5초 타임아웃 — 지연 시 UNSUPPORTED로 정상 응답)
    try:
        response = await asyncio.wait_for(
            asyncio.to_thread(
                ai_client.decide,
                goal=request.goal,
                app_package=request.app_package,
                elements=request.elements,
                history=history,
            ),
            timeout=AI_CLIENT_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        response = DecideResponse(
            target_node_id=None,
            instruction="AI 응답이 지연되어 이번 단계를 처리할 수 없습니다.",
            confidence=0.0,
            status="UNSUPPORTED",
            reason="AI 응답 지연",
        )

    # 4. 로깅 — text/content_description은 어떤 경우에도 기록하지 않음
    latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
    logger.info(
        "decide request processed",
        extra={
            "session_id": request.session_id,
            "goal": request.goal,
            "app_package": request.app_package,
            "target_node_id": response.target_node_id,
            "confidence": response.confidence,
            "status": response.status,
            "elements_count": len(request.elements),
            "latency_ms": latency_ms,
        },
    )

    # 5. 세션 갱신 — 이번 step 결과(target_node_id 포함)를 history에 추가, 최근 3개만 유지
    selected_text = (
        f"[node:{response.target_node_id}] {response.instruction}"
        if response.target_node_id is not None
        else response.instruction
    )
    session_manager.update_history(request.session_id, selected_text)

    # 6. 응답 반환
    return response
