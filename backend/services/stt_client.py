"""STT(음성 → 텍스트) 클라이언트. Whisper API 구현체.

**이 파일만 OpenAI SDK를 쓴다.** 추론(Claude/Gemini)과 STT(Whisper)는 제공자가 다르므로
파일을 갈라 둔다 — 한 파일에 섞으면 어느 키가 어느 호출에 쓰이는지 추적이 어려워지고,
한쪽 제공자를 갈아끼울 때 다른 쪽을 건드리게 된다.

기존 경로는 Android의 `SpeechRecognizer`(온디바이스 STT)다. 이 모듈은 그것을 대체하는
실험 경로로, 기기에서 녹음한 오디오를 서버로 보내 Whisper로 받아쓴다. 어느 쪽이 더
정확한지는 실측으로 비교한다.

**오디오는 저장하지 않는다.** 요청 처리 중에만 메모리에 존재하고 응답 직후 버린다
(CLAUDE.md §4 화면 데이터 비영속화와 같은 원칙 — 음성은 화면보다 더 민감하다).
"""

from typing import Protocol

from backend.core.logging import get_logger

logger = get_logger(__name__)

try:
    import openai
except ImportError:  # pragma: no cover - 패키지 미설치 환경
    openai = None


class STTClientError(Exception):
    """STT 호출 실패. 라우터가 잡아 에러 포맷으로 응답한다."""


class STTClient(Protocol):
    """음성 → 텍스트 인터페이스. 구현체는 동기 함수로 두고 라우터가 스레드로 offload한다."""

    def transcribe(self, audio: bytes, filename: str, language: str | None) -> str: ...


class WhisperSTTClient:
    """OpenAI Whisper 구현체.

    `language`를 넘기면 인식 정확도와 지연이 함께 좋아진다 — 언어 자동 감지 단계가 빠지기
    때문이다. 한국어 고정 시나리오이므로 호출부가 "ko"를 넘기는 것을 기본으로 한다.
    """

    PROVIDER = "whisper"

    def __init__(self, api_key: str, model: str, timeout_seconds: float) -> None:
        if openai is None:  # pragma: no cover - 패키지 미설치 환경
            raise STTClientError("openai 패키지가 없습니다. pip install openai")
        self._client = openai.OpenAI(api_key=api_key, timeout=timeout_seconds)
        self._model = model

    def transcribe(self, audio: bytes, filename: str, language: str | None = "ko") -> str:
        # SDK는 (filename, bytes) 튜플로 업로드를 받는다. 확장자로 컨테이너 포맷을 판단하므로
        # 파일명을 그대로 넘겨야 한다 — 이름을 잃으면 포맷 추론이 실패해 400이 난다.
        try:
            result = self._client.audio.transcriptions.create(
                model=self._model,
                file=(filename, audio),
                language=language,
            )
        except Exception as exc:  # SDK 예외 계층이 넓어 광범위하게 잡고 변환한다
            raise STTClientError(self._describe_api_error(exc)) from exc

        text = (getattr(result, "text", "") or "").strip()
        # 받아쓴 내용 자체는 사용자 발화라 로그에 남기지 않는다. 길이만 남겨 디버깅에 쓴다.
        logger.info(
            "whisper transcribed",
            extra={"model": self._model, "audio_bytes": len(audio), "text_length": len(text)},
        )
        return text

    def _describe_api_error(self, exc: Exception) -> str:
        """SDK 예외를 로그용 문자열로 바꾼다. 응답 본문에는 노출하지 않는다(키가 샐 수 있다)."""
        if openai is not None and isinstance(exc, openai.APIStatusError):
            return f"Whisper API error {exc.status_code}: {getattr(exc, 'message', exc)}"
        return f"{type(exc).__name__}: {exc}"
