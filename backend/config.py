from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    CONFIDENCE_THRESHOLD: float = 0.6
    SESSION_TTL_MINUTES: int = 30
    SENSITIVE_KEYWORDS: list[str] = [
        "송금",
        "결제",
        "계좌",
        "비밀번호",
        "주민번호",
        "인증",
        "카드번호",
        "삭제",
    ]


@lru_cache
def get_settings() -> Settings:
    return Settings()
