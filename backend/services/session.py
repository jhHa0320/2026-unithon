import time
from dataclasses import dataclass, field
from threading import Lock

from backend.config import get_settings
from backend.schemas.request import HistoryEntry

MAX_HISTORY = 3
MAX_CACHE_ENTRIES = 16


@dataclass
class SessionData:
    history: list[HistoryEntry] = field(default_factory=list)
    step_counter: int = 0
    # 되돌릴 수 없는 행동에 대해 확인을 요청한 노드 라벨 (CLAUDE.md §5-2 무한 루프 방지)
    pending_confirmation: str | None = None
    # (goal, screen_signature) -> 직전 결정. 같은 화면·같은 목표 재요청 시 LLM 콜을 건너뛴다.
    decision_cache: dict[tuple[str, str], object] = field(default_factory=dict)
    last_signature: str | None = None
    repeat_count: int = 0
    last_accessed: float = field(default_factory=time.time)


class SessionManager:
    """session_id 기반 in-memory 세션 저장소. TTL 경과 시 세션을 폐기한다."""

    def __init__(self, ttl_minutes: int) -> None:
        self._ttl_seconds = ttl_minutes * 60
        self._sessions: dict[str, SessionData] = {}
        self._lock = Lock()

    def get_history(self, session_id: str) -> list[HistoryEntry]:
        """session_id로 기존 세션의 history를 조회한다. 없으면 빈 history(새 세션 취급)."""
        self._evict_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return []
            session.last_accessed = time.time()
            return list(session.history)

    def get_pending_confirmation(self, session_id: str) -> str | None:
        self._evict_expired()
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return None
            session.last_accessed = time.time()
            return session.pending_confirmation

    def set_pending_confirmation(self, session_id: str, label: str | None) -> None:
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            session.pending_confirmation = label
            session.last_accessed = time.time()

    def get_cached_decision(self, session_id: str, key: tuple[str, str]) -> object | None:
        with self._lock:
            session = self._sessions.get(session_id)
            if session is None:
                return None
            return session.decision_cache.get(key)

    def put_cached_decision(
        self, session_id: str, key: tuple[str, str], response: object
    ) -> None:
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            if len(session.decision_cache) >= MAX_CACHE_ENTRIES:
                session.decision_cache.pop(next(iter(session.decision_cache)))
            session.decision_cache[key] = response

    def bump_signature(self, session_id: str, signature: str) -> int:
        """같은 화면이 연속으로 몇 번째인지 반환한다. 무한 루프 감지용."""
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            if session.last_signature == signature:
                session.repeat_count += 1
            else:
                session.last_signature = signature
                session.repeat_count = 1
            session.last_accessed = time.time()
            return session.repeat_count

    def update_history(
        self, session_id: str, action: str, selected_text: str
    ) -> HistoryEntry:
        """이번 step 결과를 history에 추가하고, 최근 MAX_HISTORY개만 유지한다."""
        with self._lock:
            session = self._sessions.setdefault(session_id, SessionData())
            session.step_counter += 1
            entry = HistoryEntry(
                step=session.step_counter, action=action, selected_text=selected_text
            )
            session.history.append(entry)
            session.history = session.history[-MAX_HISTORY:]
            session.last_accessed = time.time()
            return entry

    def reset(self) -> None:
        """테스트 전용 — 세션 저장소를 비운다."""
        with self._lock:
            self._sessions.clear()

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
