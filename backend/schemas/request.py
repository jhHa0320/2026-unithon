from pydantic import BaseModel, field_validator


class ElementDTO(BaseModel):
    id: int
    text: str | None = None
    content_description: str | None = None
    class_name: str
    clickable: bool
    bounds: list[int]  # [left, top, right, bottom]

    @field_validator("bounds")
    @classmethod
    def validate_bounds(cls, value: list[int]) -> list[int]:
        if len(value) != 4:
            raise ValueError("bounds must contain exactly 4 integers: [left, top, right, bottom]")
        left, top, right, bottom = value
        if left >= right:
            raise ValueError("bounds left must be less than right")
        if top >= bottom:
            raise ValueError("bounds top must be less than bottom")
        return value


class HistoryEntry(BaseModel):
    step: int
    selected_text: str


class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str
    elements: list[ElementDTO]
    history: list[HistoryEntry] | None = None

    @field_validator("elements")
    @classmethod
    def validate_elements_not_empty(cls, value: list[ElementDTO]) -> list[ElementDTO]:
        if not value:
            raise ValueError("elements must not be empty")
        return value
