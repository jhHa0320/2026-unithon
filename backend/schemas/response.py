from typing import Literal

from pydantic import BaseModel, Field

ActionType = Literal["CLICK", "SET_TEXT", "SCROLL"]
DecideStatus = Literal["CONTINUE", "DONE", "ASK_USER", "UNSUPPORTED"]


class DecideResponse(BaseModel):
    # 수행 대상 노드. 요청의 ElementDTO.id와 동일한 값이어야 한다.
    target_node_id: int | None = None
    # 대상 노드에 어떤 조작을 할지. 조작할 것이 없으면(ASK_USER/DONE 등) None.
    # SCROLL은 대상 노드(또는 그 조상)를 한 화면 앞으로 스크롤한다 — 목표 항목이 화면 밖에
    # 있어 클릭할 수 없을 때 쓴다.
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
    # UNSUPPORTED가 '일시적 실패'인지 여부. AI 호출 실패·응답 지연처럼 같은 화면으로 다시
    # 시도하면 성공할 수 있는 경우에만 True다. 클라이언트는 True일 때 세션을 끝내지 말고
    # 한 번 더 시도해야 한다 — 무료 티어 소진(429)이나 순간적인 5xx 하나로 세션이 끝나면
    # 시연 도중 복구할 방법이 없기 때문. 화면을 이해하지 못해서 나온 UNSUPPORTED(지어낸
    # node_id, action_type 불일치 등)는 다시 해도 결과가 같으므로 False.
    retryable: bool = False
