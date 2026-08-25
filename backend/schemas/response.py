from typing import Literal, Optional
from pydantic import BaseModel

class Decision(BaseModel):
    target_node_id: Optional[str] = None
    action_type: str  # "CLICK" | "SET_TEXT"
    input_value: Optional[str] = None

class DecideResponse(BaseModel):
    decision: Optional[Decision] = None
    status: Literal["CONTINUE", "WAIT_FOR_CONFIRM", "DONE", "FAIL"]
    voice_message: str
    confidence: float
