from typing import Literal

from pydantic import BaseModel, Field

ActionType = Literal["CLICK", "SET_TEXT"]
DecideStatus = Literal["CONTINUE", "DONE", "ASK_USER", "UNSUPPORTED"]


class DecideResponse(BaseModel):
    # 수행 대상 노드. 요청의 ElementDTO.id와 동일한 값이어야 한다.
    target_node_id: int | None = None
    # 대상 노드에 어떤 조작을 할지. 조작할 것이 없으면(ASK_USER/DONE 등) None.
    action_type: ActionType | None = None
    # action_type이 SET_TEXT일 때 입력할 문자열.
    input_value: str | None = None
    # 서버/클라이언트 로그와 디버깅용 요약. 사용자에게 그대로 읽어주지 않는다.
    instruction: str
    # TTS로 사용자에게 읽어줄 문구. 읽을 것이 없으면 빈 문자열.
    voice_message: str = ""
    confidence: float = Field(ge=0.0, le=1.0)
    status: DecideStatus
    reason: str | None = None
