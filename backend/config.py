from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # 안전 게이트
    CONFIDENCE_THRESHOLD: float = 0.6

    # 세션
    SESSION_TTL_MINUTES: int = 30

    # LLM — 키가 설정된 프로바이더가 자동으로 선택된다 (services/ai_client.py).
    # 우선순위: Gemini > Claude > Mock. 둘 다 없으면 Mock으로 떠서 개발은 계속 가능하다.
    AI_CLIENT_TIMEOUT_SECONDS: float = 12.0

    # Google Gemini
    GEMINI_API_KEY: str | None = None
    GEMINI_MODEL: str = "gemini-3.7-flash"
    # 지연이 중요하면 0으로 두어 thinking을 끈다. None이면 SDK 기본값을 그대로 쓴다.
    GEMINI_THINKING_BUDGET: int | None = None

    # Anthropic Claude (대안)
    ANTHROPIC_API_KEY: str | None = None
    ANTHROPIC_MODEL: str = "claude-opus-5"
    ANTHROPIC_EFFORT: str = "low"
    ANTHROPIC_MAX_TOKENS: int = 2048

    # 규칙 기반 최적화 (services/rules.py) — LLM 콜 수와 콜당 토큰을 줄인다
    MAX_ELEMENTS_TO_LLM: int = 60
    ENABLE_RULE_APP_RESOLUTION: bool = True
    ENABLE_DECISION_CACHE: bool = True
    APP_MATCH_MIN_SCORE: int = 60
    MAX_REPEATED_SCREENS: int = 3
    # 조작 가능 노드 중 라벨 없는 비율이 이 값 이상이면 Vision fallback 후보 (Phase 7)
    VISION_UNLABELED_RATIO: float = 0.6

    # 되돌릴 수 없는 행동 — 구두 동의 게이트 (CLAUDE.md §5-2)
    # 노드 라벨에 이 중 하나가 포함되면 동의 없이 CLICK을 통과시키지 않는다.
    IRREVERSIBLE_KEYWORDS: list[str] = [
        "결제",
        "송금",
        "이체",
        "구매",
        "주문",
        "전송",
        "보내기",
        "삭제",
        "탈퇴",
        "확정",
    ]

    # 민감정보 마스킹 정규식 (CLAUDE.md §4-4)
    # 전화번호는 일부러 제외한다 — 연락처 검색에 필요한 정보라서 가리면 목표 수행이 막힌다.
    SENSITIVE_PATTERNS: list[str] = [
        r"\d{6}[-\s]?[1-4]\d{6}",                       # 주민등록번호
        r"\d{4}[-\s]?\d{4}[-\s]?\d{4}[-\s]?\d{4}",      # 카드번호
        r"\d{3,6}[-\s]\d{2,6}[-\s]\d{5,7}",             # 계좌번호
    ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
