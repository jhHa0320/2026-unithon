from pydantic import BaseModel, Field, field_validator


class ElementDTO(BaseModel):
    """접근성 서비스가 추출한 화면 노드 1개. 요청 처리 중에만 메모리에 존재하며 저장하지 않는다."""

    id: int
    text: str | None = None
    content_description: str | None = None
    class_name: str
    clickable: bool
    # 이 노드가 스크롤 가능한 컨테이너인지(RecyclerView/ScrollView 등). 목표 항목이 화면 밖에
    # 있을 때 LLM이 무엇을 스크롤해야 할지 고르는 근거다. 이 필드를 안 보내는 구버전
    # 클라이언트도 그대로 동작하도록 기본값을 둔다.
    scrollable: bool = False
    # 안드로이드 resource-id(viewIdResourceName, 예: "com.kakao.talk:id/btn_send").
    # 라벨이 없거나 애매한 버튼을 LLM이 식별하는 데 가장 강한 신호다 — 아이콘만 있는
    # 전송/첨부 버튼은 text도 contentDescription도 비어 있는 경우가 많다.
    # 없는 노드도 많으므로 선택 필드.
    view_id: str | None = None
    bounds: list[int]  # [left, top, right, bottom]

    @field_validator("bounds")
    @classmethod
    def validate_bounds(cls, value: list[int]) -> list[int]:
        if len(value) != 4:
            raise ValueError("bounds must contain exactly 4 integers")
        left, top, right, bottom = value
        if left >= right:
            raise ValueError("bounds requires left < right")
        if top >= bottom:
            raise ValueError("bounds requires top < bottom")
        return value


class HistoryEntry(BaseModel):
    """이전 step에서 에이전트가 무엇을 선택했는지에 대한 요약. LLM에 최근 몇 개만 전달한다."""

    step: int
    selected_text: str


class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str
    elements: list[ElementDTO] = Field(min_length=1)
    # 사용자의 음성 응답(STT 결과). 확인 질문에 대한 답변 등 대화 턴에서만 채워진다.
    user_speech: str | None = None
    history: list[HistoryEntry] | None = None
