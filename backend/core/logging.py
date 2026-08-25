import json
import logging
import sys

# 절대 로그에 남기면 안 되는 필드 (화면 데이터 비영속화 원칙, CLAUDE.md 4장 2번 참고)
REDACTED_KEYS = {"text", "content_description", "user_speech", "input_value"}

# LogRecord가 기본으로 들고 있는 속성. 이 목록에 없는 속성만 extra로 간주해 출력한다.
_STANDARD_RECORD_ATTRS = frozenset(
    {
        "args", "asctime", "created", "exc_info", "exc_text", "filename",
        "funcName", "levelname", "levelno", "lineno", "module", "msecs",
        "msg", "name", "pathname", "process", "processName", "relativeCreated",
        "stack_info", "thread", "threadName", "taskName",
    }
)


class RedactSensitiveFieldsFilter(logging.Filter):
    """민감 필드가 실수로 extra에 실려도 값을 지운다. 마지막 방어선."""

    def filter(self, record: logging.LogRecord) -> bool:
        for key in REDACTED_KEYS:
            if hasattr(record, key):
                setattr(record, key, "[REDACTED]")
        return True


class JsonFormatter(logging.Formatter):
    """extra로 넘긴 구조화 필드까지 포함해 한 줄 JSON으로 출력한다."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, object] = {
            "time": self.formatTime(record),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
        }

        for key, value in record.__dict__.items():
            if key not in _STANDARD_RECORD_ATTRS and not key.startswith("_"):
                payload[key] = value

        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)

        return json.dumps(payload, ensure_ascii=False, default=str)


def setup_logging(level: int = logging.INFO) -> None:
    # 한글이 섞인 로그가 cp949 콘솔에서 깨지지 않도록 stdout을 UTF-8로 고정한다.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    handler.addFilter(RedactSensitiveFieldsFilter())

    root_logger = logging.getLogger()
    root_logger.setLevel(level)
    root_logger.handlers = [handler]


def get_logger(name: str) -> logging.Logger:
    return logging.getLogger(name)
