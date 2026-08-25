import asyncio
import time
from functools import lru_cache

from fastapi import APIRouter, Depends

from backend.config import Settings, get_settings
from backend.core.logging import get_logger
from backend.schemas.request import DecideRequest
from backend.schemas.response import DecideResponse
from backend.services import prompt, safety
from backend.services.ai_client import (
    AIClient,
    AIClientError,
    GeminiAIClient,
    MockAIClient,
)
from backend.services.session import session_manager

router = APIRouter(prefix="/api/v1", tags=["decide"])
logger = get_logger(__name__)

# SDK의 HTTP deadline(GEMINI_TIMEOUT_SECONDS, 하한 10초)보다 반드시 커야 한다.
# 그래야 HTTP가 먼저 끊기고 스레드가 풀린다 — asyncio.wait_for는 대기만 중단할 뿐
# to_thread로 띄운 스레드를 실제로 끊지 못하기 때문이다.
# 실측 응답은 2초대이므로 이 값은 예산이 아니라 안전망이다.
AI_CLIENT_TIMEOUT_SECONDS = 12.0

# history에 남길 status. 되묻기(ASK_USER)와 실패(UNSUPPORTED)는 화면을 진행시키지 않았으므로
# "무엇을 했는지"의 기록이 아니다. 자세한 이유는 세션 갱신 지점(아래) 주석 참고.
_HISTORY_WORTHY_STATUSES = frozenset({"CONTINUE", "DONE"})


@lru_cache(maxsize=1)
def _build_ai_client(
    api_key: str | None, model: str, thinking_level: str, timeout_seconds: float
) -> AIClient:
    """클라이언트를 프로세스당 한 번만 만든다. 요청마다 생성하면 커넥션이 낭비된다."""
    if not api_key:
        logger.warning("GEMINI_API_KEY not set — falling back to MockAIClient")
        return MockAIClient()

    logger.info(
        "using GeminiAIClient",
        extra={"model": model, "thinking_level": thinking_level, "timeout_s": timeout_seconds},
    )
    return GeminiAIClient(
        api_key=api_key,
        model=model,
        thinking_level=thinking_level,
        timeout_seconds=timeout_seconds,
    )


def get_ai_client(settings: Settings = Depends(get_settings)) -> AIClient:
    """AI 클라이언트 주입 지점. 키가 없으면 Mock으로 폴백해 서버가 항상 뜨게 한다."""
    return _build_ai_client(
        settings.GEMINI_API_KEY,
        settings.GEMINI_MODEL,
        settings.GEMINI_THINKING_LEVEL,
        settings.GEMINI_TIMEOUT_SECONDS,
    )


@router.post("/decide", response_model=DecideResponse)
async def decide(
    request: DecideRequest,
    settings: Settings = Depends(get_settings),
    ai_client: AIClient = Depends(get_ai_client),
) -> DecideResponse:
    start_time = time.perf_counter()

    # 1. 요청 검증 — elements 빈 배열/bounds 정합성은 schemas/request.py validator가 처리(위반 시 422)

    # 2. 민감 요소 탐지 — 로깅용 신호일 뿐, elements를 걸러내지 않는다(전송까지 자동 진행)
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
            # 일시적 지연이므로 같은 화면으로 다시 시도하면 성공할 수 있다.
            retryable=True,
        )
    except AIClientError as exc:
        # LLM 호출/파싱 실패. 서버가 죽지 않고 계약대로 응답한다(CLAUDE.md 12장).
        # 상세 사유는 로그로만 남기고 응답 본문에는 넣지 않는다.
        logger.warning("ai client failed", extra={"detail": str(exc)})
        response = DecideResponse(
            target_node_id=None,
            action_type=None,
            input_value=None,
            instruction="AI 호출에 실패해 이번 단계를 처리할 수 없음",
            voice_message="죄송해요, 지금은 화면을 읽지 못했어요. 다시 시도해 주세요.",
            confidence=0.0,
            status="UNSUPPORTED",
            reason="AI 호출 실패",
            # 429(무료 티어 소진)나 순간적인 5xx가 대부분이라 재시도 가치가 있다.
            retryable=True,
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
            "prompt_version": prompt.PROMPT_VERSION,
            "latency_ms": latency_ms,
            "retryable": response.retryable,
        },
    )

    # 9. 세션 갱신 — 화면을 실제로 진행시킨 스텝만 history에 남긴다.
    #    ASK_USER/UNSUPPORTED까지 전부 넣으면 되묻기가 3~4번 연속될 때 "전송 버튼을 눌렀다"는
    #    진짜 근거가 MAX_HISTORY 창 밖으로 밀려난다 — 프롬프트 v3의 6번 규칙(중복 전송 방지)이
    #    바로 그 기록을 보고 완료를 판단하므로, 밀려나면 이미 보낸 사진을 다시 보내는 사고가 난다.
    if response.status in _HISTORY_WORTHY_STATUSES:
        session_manager.update_history(request.session_id, _history_summary(response))

    # 10. 응답 반환
    return response


def _history_summary(response: DecideResponse) -> str:
    """다음 턴의 LLM 프롬프트에 넣을 한 줄 요약. 화면 원문 텍스트는 담지 않는다.

    target_node_id가 있는 스텝도 반드시 instruction(LLM이 남긴 자연어 근거, 예: "최원호 님에게
    사진을 보내기 위해 전송 버튼을 클릭합니다")을 써야 한다 — "node=29 action=CLICK" 같은 번호만
    남기면, node_id는 화면을 스캔할 때마다 1부터 다시 매기는 임시 번호라 다음 턴에는 완전히 다른
    요소를 가리킨다. 그러면 LLM이 "직전에 전송 버튼을 눌렀다"는 사실 자체를 다음 턴에 알 방법이
    없어서, 이미 완료된 절차를 계속 반복 실행하는 사고로 이어졌다(2026-08-25 실기기 재현).
    instruction은 LLM이 만든 요약 문장이라 화면 원문(text/content_description)을 그대로 담지
    않으므로 이 함수의 "원문 미포함" 원칙과도 어긋나지 않는다.
    """
    return f"[{response.status}] {response.instruction}"
