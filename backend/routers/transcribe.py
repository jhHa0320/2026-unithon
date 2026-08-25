"""POST /api/v1/transcribe — 기기에서 녹음한 오디오를 텍스트로 받아쓴다.

Android의 온디바이스 `SpeechRecognizer`를 대체하는 실험 경로다(api-change 브랜치).
클라이언트는 오디오 파일을 multipart로 올리고 `{"text": "..."}`를 돌려받는다.

**오디오는 저장하지 않는다.** 메모리에서 Whisper로 곧장 넘기고 응답 후 버린다.
"""

import asyncio
from functools import lru_cache

from fastapi import APIRouter, Depends, File, Form, UploadFile

from backend.config import Settings, get_settings
from backend.core.errors import AppError
from backend.core.logging import get_logger
from backend.schemas.transcribe import TranscribeResponse
from backend.services.stt_client import STTClient, STTClientError, WhisperSTTClient

router = APIRouter(prefix="/api/v1", tags=["transcribe"])
logger = get_logger(__name__)

# STT는 응답이 곧 사용자 대기 시간이다. SDK의 HTTP deadline보다 살짝 크게 잡아
# HTTP가 먼저 끊기고 to_thread 스레드가 풀리게 한다.
_TIMEOUT_MARGIN_SECONDS = 3.0


@lru_cache(maxsize=1)
def _build_stt_client(api_key: str | None, model: str, timeout_seconds: float) -> STTClient | None:
    """프로세스당 한 번만 만든다. 키가 없으면 None — 라우터가 503으로 응답한다.

    추론 쪽과 달리 Mock 폴백이 없다. 받아쓰기를 흉내 내면 사용자가 하지 않은 말이
    goal로 들어가고, 그 goal로 자동화가 실제 조작을 실행한다 — 조용히 틀린 답을 주느니
    명시적으로 실패하는 편이 안전하다.
    """
    if not api_key:
        logger.warning("OPENAI_API_KEY not set — /transcribe disabled")
        return None
    logger.info("using WhisperSTTClient", extra={"model": model, "timeout_s": timeout_seconds})
    return WhisperSTTClient(api_key=api_key, model=model, timeout_seconds=timeout_seconds)


def get_stt_client(settings: Settings = Depends(get_settings)) -> STTClient | None:
    return _build_stt_client(
        settings.OPENAI_API_KEY,
        settings.WHISPER_MODEL,
        settings.WHISPER_TIMEOUT_SECONDS,
    )


@router.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(
    audio: UploadFile = File(..., description="녹음한 오디오 파일 (m4a/mp3/wav/webm 등)"),
    language: str | None = Form("ko", description="언어 코드. 비우면 자동 감지"),
    settings: Settings = Depends(get_settings),
    stt_client: STTClient | None = Depends(get_stt_client),
) -> TranscribeResponse:
    if stt_client is None:
        raise AppError(
            error_code="STT_NOT_CONFIGURED",
            message="음성 인식이 설정되지 않았습니다. 서버에 OPENAI_API_KEY를 설정하세요.",
            status_code=503,
        )

    data = await audio.read()
    if not data:
        raise AppError(
            error_code="EMPTY_AUDIO",
            message="오디오가 비어 있습니다.",
            status_code=422,
        )
    # 상한을 넘는 업로드는 받아 두고 나서 거절하는 게 아니라 여기서 끊는다 — 서버 메모리를
    # 지키는 것이 목적이고, 몇 초짜리 발화가 이 크기를 넘을 일은 없다.
    if len(data) > settings.WHISPER_MAX_UPLOAD_BYTES:
        raise AppError(
            error_code="AUDIO_TOO_LARGE",
            message=f"오디오가 너무 큽니다({len(data)} bytes).",
            status_code=413,
        )

    filename = audio.filename or "audio.m4a"
    try:
        text = await asyncio.wait_for(
            asyncio.to_thread(stt_client.transcribe, data, filename, language),
            timeout=settings.WHISPER_TIMEOUT_SECONDS + _TIMEOUT_MARGIN_SECONDS,
        )
    except asyncio.TimeoutError as exc:
        raise AppError(
            error_code="STT_TIMEOUT",
            message="음성 인식이 지연되고 있습니다. 다시 시도해 주세요.",
            status_code=504,
        ) from exc
    except STTClientError as exc:
        # 상세 사유는 로그로만. 응답 본문에 넣으면 키나 내부 오류가 샐 수 있다.
        logger.warning("stt client failed", extra={"detail": str(exc)})
        raise AppError(
            error_code="STT_FAILED",
            message="음성을 인식하지 못했습니다. 다시 시도해 주세요.",
            status_code=502,
        ) from exc

    return TranscribeResponse(text=text)
