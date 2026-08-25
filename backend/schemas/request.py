from pydantic import BaseModel, field_validator, model_validator


class ElementDTO(BaseModel):
    """현재 화면의 UI 노드 하나. id는 화면 덤프마다 클라이언트가 새로 부여하는 세션 내 임시값."""

    id: int
    text: str | None = None
    content_description: str | None = None
    class_name: str
    clickable: bool
    editable: bool = False
    scrollable: bool = False
    password: bool = False
    bounds: list[int]  # [left, top, right, bottom]

    @field_validator("bounds")
    @classmethod
    def validate_bounds(cls, value: list[int]) -> list[int]:
        if len(value) != 4:
            raise ValueError("bounds must contain exactly 4 integers")
        left, top, right, bottom = value
        # 좌표가 역전된 것(진짜 손상된 데이터)만 거절한다.
        # 면적 0(접힌 뷰·화면 밖 노드)은 실제 UI Tree에 흔히 섞여 들어오므로 허용하고,
        # services/rules.py의 필터가 걸러낸다. 노드 하나 때문에 요청 전체가 422가 되면
        # 그 화면에서 자동화가 통째로 멈춘다.
        if left > right or top > bottom:
            raise ValueError("bounds must satisfy left <= right and top <= bottom")
        return value

    @property
    def label(self) -> str:
        """LLM과 안전 게이트가 이 노드를 식별할 때 쓰는 사람이 읽을 수 있는 라벨."""
        return (self.text or self.content_description or "").strip()


class InstalledApp(BaseModel):
    """설치된 앱 하나. app_package가 없을 때(=아직 대상 앱 미실행) LLM이 여기서 고른다."""

    package: str
    label: str


class HistoryEntry(BaseModel):
    step: int
    action: str
    selected_text: str


class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str | None = None
    elements: list[ElementDTO]
    installed_apps: list[InstalledApp] | None = None
    history: list[HistoryEntry] | None = None

    @model_validator(mode="after")
    def validate_elements_presence(self) -> "DecideRequest":
        # 대상 앱이 실행된 상태라면 화면 요소가 반드시 있어야 한다.
        # app_package가 None인 첫 요청(앱 선택 단계)에서만 빈 배열을 허용한다.
        if self.app_package and not self.elements:
            raise ValueError("elements must not be empty when app_package is set")
        return self
