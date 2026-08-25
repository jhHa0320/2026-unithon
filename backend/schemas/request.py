from pydantic import BaseModel, field_validator
from typing import Optional, List

class UIElement(BaseModel):
    node_id: str
    text: Optional[str] = None
    content_desc: Optional[str] = None
    clickable: bool
    bounds: List[int]  # [left, top, right, bottom]

    @field_validator("bounds")
    @classmethod
    def validate_bounds(cls, value: List[int]) -> List[int]:
        if len(value) != 4:
            raise ValueError("bounds must contain exactly 4 integers")
        return value

class DecideRequest(BaseModel):
    session_id: str
    goal: str
    current_app: str
    ui_tree: List[UIElement]
    user_speech: Optional[str] = None
