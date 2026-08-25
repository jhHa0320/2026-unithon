"""LLM이 직접 반환하는 원시 스키마. 클라이언트에 나가는 DecideResponse와 의도적으로 분리한다.

분리하는 이유:
1. `UNSUPPORTED`는 서버가 검증 실패에 부여하는 판정이므로 LLM이 선택할 수 없어야 한다.
2. `reason`은 안전 게이트가 채우는 필드다. LLM이 쓰면 게이트 사유와 뒤섞인다.
3. 프롬프트 튜닝(B-3)으로 이 스키마가 바뀌어도 Android와의 계약은 흔들리지 않는다.

null 대신 센티널을 쓰는 이유: JSON Schema의 nullable(anyOf) 지원 범위가 프로바이더마다
달라 400을 유발할 수 있다. 전 필드를 non-nullable 원시 타입으로 고정하고 어댑터에서
None으로 되돌리는 편이 안전하다. 변환은 to_decide_response()가 담당한다.
"""

from typing import Literal

from pydantic import BaseModel, Field

# 조작할 노드가 없음을 뜻하는 센티널
NO_TARGET_NODE_ID = -1
NO_ACTION = "NONE"


class LLMDecision(BaseModel):
    """LLM이 채우는 응답. 모든 필드 필수, nullable 없음."""

    target_node_id: int = Field(
        description=(
            "조작할 요소의 id. elements에 실제로 존재하는 id여야 한다. "
            f"조작할 것이 없으면 {NO_TARGET_NODE_ID}."
        )
    )
    action_type: Literal["CLICK", "SET_TEXT", "SCROLL", "NONE"] = Field(
        description=(
            "CLICK=탭, SET_TEXT=텍스트 입력, "
            "SCROLL=목록을 한 화면 아래로 내림(목표 항목이 화면 밖에 있을 때), NONE=조작 없음"
        )
    )
    input_value: str = Field(
        description="action_type이 SET_TEXT일 때 입력할 문자열. 그 외에는 빈 문자열."
    )
    voice_message: str = Field(
        description="사용자에게 TTS로 읽어줄 한국어 한 문장. 고령자가 알아듣기 쉽게."
    )
    reasoning: str = Field(
        description="이 판단을 내린 근거 한 문장. 로그용이며 사용자에게 읽어주지 않는다."
    )
    confidence: float = Field(
        ge=0.0, le=1.0, description="이 판단에 대한 확신도. 0.0~1.0."
    )
    status: Literal["CONTINUE", "DONE", "ASK_USER"] = Field(
        description=(
            "CONTINUE=조작을 실행하고 계속, DONE=목표 달성 또는 종료, "
            "ASK_USER=정보가 부족해 사용자에게 되물어야 함"
        )
    )
