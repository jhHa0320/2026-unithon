from typing import Literal

from pydantic import BaseModel


class DecideResponse(BaseModel):
    target_node_id: int | None
    instruction: str
    confidence: float
    status: Literal["CONTINUE", "DONE", "ASK_USER", "UNSUPPORTED"]
    reason: str | None = None
