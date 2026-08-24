import logging
import sys

# 절대 로그에 남기면 안 되는 필드 (화면 데이터 비영속화 원칙, CLAUDE.md 3장 참고)
REDACTED_KEYS = {"text", "content_description"}


class RedactSensitiveFieldsFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        for key in REDACTED_KEYS:
            if hasattr(record, key):
                setattr(record, key, "[REDACTED]")
        return True


def setup_logging(level: int = logging.INFO) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        logging.Formatter(
            fmt='{"time": "%(asctime)s", "level": "%(levelname)s", '
            '"logger": "%(name)s", "message": "%(message)s"}'
        )
    )
    handler.addFilter(RedactSensitiveFieldsFilter())

    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    root_logger.handlers = [handler]


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)
