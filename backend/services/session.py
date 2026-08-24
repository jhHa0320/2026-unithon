import time
from dataclasses import dataclass, field
from threading import Lock

from backend.config import get_settings
from backend.schemas.request import HistoryEntry

MAX_HISTORY = 3


@dataclass
class SessionData:
    history: list[HistoryEntry] = field(default_factory=list)
    step_counter: int = 0
    last_accessed: float = field(default_factory=time.time)


class SessionManager:
    """session_id 기반 in-memory 세션 저장소. TTL 경과 시 세션을 폐기한다."""

    def __init__(self, ttl_minutes: int) -> None:
        self._ttl_seconds = ttl_minutes * 60
        self._sessions: dict[str, SessionData] = {}
        self._lock = Lock()

    def get_history(self, session_id: str) -> list[HistoryEntry]:
        """session_id로 기존 세션의 history를 조회한다. 없으면 빈 history를 반환한다(새 세션 취급)."""
        self._evict_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return []
            session.last_accessed = time.time()
            return list(session.history)

    def update_history(self, session_id: str, selected_text: str) -> HistoryEntry:
        """이번 step 결과를 history에 추가하고, 최근 MAX_HISTORY개만 유지한다."""
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            session.step_counter += 1
            entry = HistoryEntry(step=session.step_counter, selected_text=selected_text)
            session.history.append(entry)
            session.history = session.history[-MAX_HISTORY:]
            session.last_accessed = time.time()
            return entry

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
