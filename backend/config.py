from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    CONFIDENCE_THRESHOLD: float = 0.6
    SESSION_TTL_MINUTES: int = 30

    # --- 성능 실험 스위치 (api-change 브랜치) ---
    # 추론 모델을 어느 제공자로 돌릴지. 키가 없으면 Mock으로 폴백하는 규칙은 그대로다.
    #   gemini = 기존 경로(gemini-3.6-flash)
    #   claude = 실험 경로(Claude API)
    AI_PROVIDER: Literal["gemini", "claude"] = "gemini"

    # --- Claude (추론 실험용) ---
    # 비워 두면 provider가 claude여도 Mock으로 폴백한다 — 키 없는 팀원도 서버를 띄울 수 있게.
    ANTHROPIC_API_KEY: str | None = None
    CLAUDE_MODEL: str = "claude-opus-5"
    # low | medium | high | xhigh | max — 사고 깊이와 토큰 사용량을 함께 조절한다.
    # 화면당 1콜 × 10~15콜로 30초 예산을 맞춰야 하므로 기본 low. 정확도가 부족하면 올려서 실측할 것.
    CLAUDE_EFFORT: Literal["low", "medium", "high", "xhigh", "max"] = "low"
    # 사고(thinking)가 켜져 있으면 max_tokens는 '사고 + 응답'을 함께 캡한다. LLMDecision 자체는
    # 작지만 사고가 앞에서 예산을 먹으므로 넉넉히 잡는다 — 모자라면 응답이 잘려 파싱이 실패한다.
    CLAUDE_MAX_TOKENS: int = 4096
    CLAUDE_TIMEOUT_SECONDS: float = 10.0

    # --- Whisper (STT 실험용) ---
    # 비워 두면 /api/v1/transcribe가 503을 돌려준다(서버는 계속 뜬다).
    OPENAI_API_KEY: str | None = None
    WHISPER_MODEL: str = "whisper-1"
    # STT는 응답이 곧 사용자 대기 시간이라 추론보다 짧게 잡는다.
    WHISPER_TIMEOUT_SECONDS: float = 15.0
    # 업로드 오디오 크기 상한(바이트). 넘으면 413으로 거절해 서버 메모리를 지킨다.
    WHISPER_MAX_UPLOAD_BYTES: int = 10 * 1024 * 1024

    # --- Gemini ---
    # 키가 없으면 MockAIClient로 자동 폴백한다(팀원이 키 없이도 서버를 띄울 수 있게).
    GEMINI_API_KEY: str | None = None
    # gemini-1.5-*/2.0-*는 서비스 종료되어 사용 불가.
    # 최신은 3.7-flash지만 2026-08-25 실측에서 응답이 오지 않아(45초 타임아웃/504)
    # 실제로 동작이 확인된 3.6-flash를 기본값으로 둔다. 실측: 3.6-flash 2.2초, 3.5-flash-lite 1.2초.
    GEMINI_MODEL: str = "gemini-3.6-flash"
    # 화면당 1콜 × 10~15콜로 30초 예산을 맞춰야 하므로 기본 low.
    # 정확도가 부족한 화면 유형이 나오면 medium으로 올린다(작업 B-3).
    GEMINI_THINKING_LEVEL: Literal["low", "medium", "high"] = "low"

    # HTTP deadline. Gemini가 10초 미만을 거부하므로(400 "Minimum allowed deadline is 10s")
    # 이보다 낮게 설정할 수 없다. 실측 응답은 2초대이므로 이 값은 예산이 아니라 안전망이다.
    GEMINI_TIMEOUT_SECONDS: float = 10.0
    SENSITIVE_KEYWORDS: list[str] = [
        "전송",
        "보내기",
        "송금",
        "이체",
        "결제",
        "계좌",
        "비밀번호",
        "인증",
        "삭제",
        "탈퇴",
        "주민번호",
        "카드번호",
    ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
