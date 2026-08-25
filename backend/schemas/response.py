from typing import Literal

from pydantic import BaseModel

ActionType = Literal[
    "CLICK",
    "SET_TEXT",
    "LAUNCH_APP",
    "SCROLL_FORWARD",
    "SCROLL_BACKWARD",
    "BACK",
    "NONE",
]

StatusType = Literal[
    "CONTINUE",
    "DONE",
    "ASK_USER",
    "CONFIRM_REQUIRED",
    "UNSUPPORTED",
]


class DecideResponse(BaseModel):
    action: ActionType
    target_node_id: int | None = None
    value: str | None = None  # SET_TEXT의 입력값 / LAUNCH_APP의 패키지명
    instruction: str  # TTS로 읽어줄 문장
    confidence: float
    status: StatusType
    reason: str | None = None
