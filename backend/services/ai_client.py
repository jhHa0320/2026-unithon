from typing import Protocol

from backend.schemas.request import ElementDTO, HistoryEntry
from backend.schemas.response import DecideResponse


class AIClient(Protocol):
    """LLM 호출 인터페이스. 실제 구현체는 별도로 추가한다 (여기서는 구현하지 않음)."""

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
    ) -> DecideResponse: ...


class MockAIClient:
    """테스트/개발용 고정 응답 Mock 구현체. 실제 LLM 연동 전까지 사용."""

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
    ) -> DecideResponse:
        return DecideResponse(
            target_node_id=1,
            instruction="Mock: 다음 단계를 안내합니다.",
            confidence=0.99,
            status="CONTINUE",
            reason=None,
        )
