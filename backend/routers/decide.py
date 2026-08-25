import asyncio
import time

from fastapi import APIRouter, Depends, Response

from backend.config import Settings, get_settings
from backend.core.logging import get_logger
from backend.schemas.request import DecideRequest
from backend.schemas.response import DecideResponse
from backend.services import rules, safety
from backend.services.ai_client import AIClient, MockAIClient
from backend.services.session import session_manager

router = APIRouter(prefix="/api/v1", tags=["decide"])
logger = get_logger(__name__)


def get_ai_client() -> AIClient:
    """설정된 키를 보고 LLM 프로바이더를 고른다 — Gemini > Claude > Mock.

    키가 하나도 없으면 Mock으로 뜬다. 덕분에 Android 팀은 키를 기다리지 않고
    통신·자동화 개발을 계속할 수 있다. 프로바이더를 바꿔도 라우터·스키마·프롬프트는
    그대로다 (`AIClient` Protocol만 만족하면 된다).
    """
    settings = get_settings()
    if settings.GEMINI_API_KEY:
        from backend.services.ai_client import GeminiAIClient

        return GeminiAIClient(settings)
    if settings.ANTHROPIC_API_KEY:
        from backend.services.ai_client import ClaudeAIClient

        return ClaudeAIClient(settings)
    return MockAIClient()


def _idle(instruction: str, reason: str) -> DecideResponse:
    """실행할 것이 없지만 루프는 계속되는 응답 (CONTINUE + NONE)."""
    return DecideResponse(
        action="NONE",
        target_node_id=None,
        value=None,
        instruction=instruction,
        confidence=1.0,
        status="CONTINUE",
        reason=reason,
    )


@router.post("/decide", response_model=DecideResponse)
async def decide(
    request: DecideRequest,
    http_response: Response,
    settings: Settings = Depends(get_settings),
    ai_client: AIClient = Depends(get_ai_client),
) -> DecideResponse:
    start_time = time.perf_counter()
    llm_called = False
    rule_hit: str | None = None

    # 1. 요청 검증은 schemas/request.py의 validator에서 처리됨 (위반 시 422)

    # 2. 세션 로드
    history = session_manager.get_history(request.session_id)
    pending_confirmation = session_manager.get_pending_confirmation(request.session_id)

    # 3. 민감정보 마스킹 — LLM에는 마스킹된 사본만 보낸다
    masked = safety.mask_elements(request.elements, settings)

    # 4. 규칙 필터링 — 레이아웃 컨테이너·중복·화면 밖 노드 제거
    filtered = rules.filter_elements(masked, settings)

    signature = rules.screen_signature(request.app_package, filtered)
    repeat = session_manager.bump_signature(request.session_id, signature)

    response: DecideResponse | None = None

    # 5. 규칙: 같은 화면이 계속 반복 — 클릭이 먹지 않고 있다. 루프를 끊는다.
    if repeat > settings.MAX_REPEATED_SCREENS:
        response = DecideResponse(
            action="NONE",
            target_node_id=None,
            value=None,
            instruction="화면이 바뀌지 않아 더 진행할 수 없어요. 직접 눌러 주시겠어요?",
            confidence=1.0,
            status="UNSUPPORTED",
            reason=f"same screen repeated {repeat} times",
        )
        rule_hit = "repeat_guard"

    # 6. 규칙: 조작 가능한 요소가 없다 — 아직 로딩 중이다. LLM에 물어봐야 답이 없다.
    elif request.app_package and rules.is_loading_screen(filtered):
        response = _idle("화면을 기다리고 있어요.", "loading screen — no actionable element")
        rule_hit = "loading_screen"

    # 7. 규칙: goal에서 앱을 직접 짚어낼 수 있으면 LLM 콜 1회를 통째로 건너뛴다
    elif request.app_package is None:
        package = rules.resolve_app(request.goal, request.installed_apps, settings)
        if package is not None:
            label = next(
                (a.label for a in (request.installed_apps or []) if a.package == package),
                package,
            )
            response = DecideResponse(
                action="LAUNCH_APP",
                target_node_id=None,
                value=package,
                instruction=f"{label} 열게요.",
                confidence=1.0,
                status="CONTINUE",
                reason="resolved by rule",
            )
            rule_hit = "app_resolution"

    # 8. 규칙: 같은 목표 + 같은 화면이면 직전 결정을 재사용 (중복 이벤트로 인한 낭비 방지)
    cache_key = (request.goal, signature)
    if response is None and settings.ENABLE_DECISION_CACHE:
        cached = session_manager.get_cached_decision(request.session_id, cache_key)
        if isinstance(cached, DecideResponse):
            response = cached
            rule_hit = "decision_cache"

    # 9. LLM 호출 (타임아웃 시 UNSUPPORTED로 정상 응답)
    if response is None:
        llm_called = True
        try:
            response = await asyncio.wait_for(
                asyncio.to_thread(
                    ai_client.decide,
                    goal=request.goal,
                    app_package=request.app_package,
                    elements=filtered,
                    installed_apps=request.installed_apps,
                    history=history,
                ),
                timeout=settings.AI_CLIENT_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            response = DecideResponse(
                action="NONE",
                target_node_id=None,
                value=None,
                instruction="응답이 늦어지고 있어요. 잠시 후 다시 말씀해 주세요.",
                confidence=0.0,
                status="UNSUPPORTED",
                reason="AI 응답 지연",
            )
        else:
            if settings.ENABLE_DECISION_CACHE and response.status == "CONTINUE":
                session_manager.put_cached_decision(
                    request.session_id, cache_key, response
                )

    # 10. 신뢰도 게이트
    response = safety.apply_confidence_gate(response, settings)

    # 11. 되돌릴 수 없는 행동 게이트 (구두 동의) — 캐시/규칙 경로도 반드시 통과시킨다
    response, pending_confirmation = safety.apply_confirmation_gate(
        response, request.elements, pending_confirmation, settings
    )
    session_manager.set_pending_confirmation(request.session_id, pending_confirmation)

    # 12. 응답 검증 — 실재하지 않는 노드/불완전한 action 차단
    response = safety.validate_response(response, request.elements)

    # 13. 로깅 — text/content_description 원문은 어떤 경우에도 기록하지 않는다
    latency_ms = round((time.perf_counter() - start_time) * 1000, 2)
    logger.info(
        "decide request processed",
        extra={
            "session_id": request.session_id,
            "app_package": request.app_package,
            "action": response.action,
            "target_node_id": response.target_node_id,
            "confidence": response.confidence,
            "status": response.status,
            "elements_in": len(request.elements),
            "elements_to_llm": len(filtered),
            "llm_called": llm_called,
            "rule_hit": rule_hit,
            "screen_repeat": repeat,
            "latency_ms": latency_ms,
        },
    )

    # 규칙 계층이 처리했는지 관측용 헤더 (스키마는 건드리지 않는다 — 개발/디버깅 전용)
    http_response.headers["X-Rule-Hit"] = rule_hit or ""
    http_response.headers["X-LLM-Called"] = "1" if llm_called else "0"
    http_response.headers["X-Elements"] = f"{len(request.elements)}->{len(filtered)}"

    # 14. 세션 갱신
    session_manager.update_history(
        request.session_id,
        action=response.action,
        selected_text=(
            f"[node:{response.target_node_id}] {response.instruction}"
            if response.target_node_id is not None
            else response.instruction
        ),
    )

    return response
