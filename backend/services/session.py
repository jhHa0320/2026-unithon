import time
from dataclasses import dataclass, field
from threading import Lock

from backend.config import get_settings
from backend.schemas.request import HistoryEntry

MAX_HISTORY = 3


@dataclass
class SessionData:
    history: list[HistoryEntry] = field(default_factory=list)
    last_accessed: float = field(default_factory=time.time)


class SessionManager:
    """session_id 기반 in-memory 세션 저장소. TTL 경과 시 세션을 폐기한다."""

    def __init__(self, ttl_minutes: int) -> None:
        self._ttl_seconds = ttl_minutes * 60
        self._sessions: dict[str, SessionData] = {}
        self._lock = Lock()

    def get_history(self, session_id: str) -> list[HistoryEntry]:
        self._evict_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return []
            session.last_accessed = time.time()
            return list(session.history)

    def update_history(self, session_id: str, entry: HistoryEntry) -> None:
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            session.history.append(entry)
            session.history = session.history[-MAX_HISTORY:]
            session.last_accessed = time.time()

    def _evict_expired(self) -> None:
        now = time.time()
        with self._lock:
            expired = [
                session_id
                for session_id, data in self._sessions.items()
                if now - data.last_accessed > self._ttl_seconds
            ]
            for session_id in expired:
                del self._sessions[session_id]


session_manager = SessionManager(ttl_minutes=get_settings().SESSION_TTL_MINUTES)
