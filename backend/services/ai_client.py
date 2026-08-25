"""LLM 호출 인터페이스와 개발용 Mock 구현체.

MockAIClient는 실제 추론 없이 규칙만으로 응답을 만든다. Android 팀이 서버 기동
직후부터 통신·자동화 루프를 붙여볼 수 있게 하는 것이 목적이며, Gemini 연동
(작업 B-2) 시 GeminiAIClient로 교체된다. 교체 지점은 routers/decide.py의
get_ai_client() 하나뿐이다.
"""

from typing import Protocol

from backend.schemas.request import ElementDTO, HistoryEntry
from backend.schemas.response import DecideResponse

# 사용자의 확인 응답 판정용 임시 사전. 정식 구현은 작업 B-4에서 대체한다.
_AFFIRMATIVE_WORDS = ("응", "어", "네", "예", "그래", "좋아", "해줘", "진행", "확인", "맞아")
_NEGATIVE_WORDS = ("아니", "안돼", "안 돼", "취소", "그만", "싫어", "하지마", "중단")

# 입력 필드로 간주할 클래스명 조각
_EDITABLE_CLASS_HINTS = ("EditText", "AutoCompleteTextView", "SearchView")

_HANGUL_BASE = 0xAC00
_HANGUL_LAST = 0xD7A3


def _object_particle(word: str) -> str:
    """목적격 조사를 받침 유무에 따라 고른다. TTS 문구가 어색해지지 않게 하기 위함."""
    last_char = word.strip()[-1:]
    if not last_char:
        return "를"
    code = ord(last_char)
    if not _HANGUL_BASE <= code <= _HANGUL_LAST:
        # 한글이 아니면(영문·숫자·기호) 판정이 불가능하므로 조사를 붙이지 않는다.
        return ""
    has_final_consonant = (code - _HANGUL_BASE) % 28 != 0
    return "을" if has_final_consonant else "를"


class AIClient(Protocol):
    """LLM 호출 인터페이스. 구현체는 동기 함수로 두고 라우터가 스레드로 offload한다."""

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
        user_speech: str | None,
    ) -> DecideResponse: ...


class MockAIClient:
    """규칙 기반 개발용 Mock 구현체. 실제 LLM 연동(B-2) 전까지 사용."""

    def decide(
        self,
        goal: str,
        app_package: str,
        elements: list[ElementDTO],
        history: list[HistoryEntry] | None,
        user_speech: str | None = None,
    ) -> DecideResponse:
        if user_speech:
            declined = self._check_declined(user_speech)
            if declined is not None:
                return declined

        target = next((element for element in elements if element.clickable), None)
        if target is None:
            return DecideResponse(
                target_node_id=None,
                action_type=None,
                input_value=None,
                instruction="클릭 가능한 요소가 없어 다음 행동을 결정할 수 없음",
                voice_message="화면에서 누를 수 있는 것을 찾지 못했어요. 어떻게 할까요?",
                confidence=1.0,
                status="ASK_USER",
                reason="no clickable element in elements",
            )

        if self._is_editable(target):
            return DecideResponse(
                target_node_id=target.id,
                action_type="SET_TEXT",
                input_value="서울",
                instruction=f"입력 필드(node {target.id})에 텍스트 입력",
                voice_message="출발역을 입력할게요.",
                confidence=0.9,
                status="CONTINUE",
                reason=None,
            )

        label = self._label_of(target)
        return DecideResponse(
            target_node_id=target.id,
            action_type="CLICK",
            input_value=None,
            instruction=f"node {target.id} 클릭",
            voice_message=(
                f"{label}{_object_particle(label)} 누를게요."
                if label
                else "다음 단계로 넘어갈게요."
            ),
            confidence=0.9,
            status="CONTINUE",
            reason=None,
        )

    def _check_declined(self, user_speech: str) -> DecideResponse | None:
        """사용자가 거절했으면 흐름을 종료하는 응답을, 그 외에는 None을 반환한다.

        부정어를 긍정어보다 먼저 본다. '아니 그래'처럼 둘 다 섞인 발화에서는
        중단하는 쪽이 안전하기 때문이다.
        """
        if any(word in user_speech for word in _NEGATIVE_WORDS):
            return DecideResponse(
                target_node_id=None,
                action_type=None,
                input_value=None,
                instruction="사용자가 진행을 거절하여 흐름 종료",
                voice_message="알겠습니다. 여기서 멈출게요.",
                confidence=1.0,
                status="DONE",
                reason="user declined",
            )
        if any(word in user_speech for word in _AFFIRMATIVE_WORDS):
            return None
        return DecideResponse(
            target_node_id=None,
            action_type=None,
            input_value=None,
            instruction="사용자 응답을 긍정/부정으로 판정하지 못함",
            voice_message="죄송해요, 다시 한번 말씀해 주시겠어요?",
            confidence=1.0,
            status="ASK_USER",
            reason="user_speech not recognized as yes or no",
        )

    def _is_editable(self, element: ElementDTO) -> bool:
        return any(hint in element.class_name for hint in _EDITABLE_CLASS_HINTS)

    def _label_of(self, element: ElementDTO) -> str | None:
        return element.text or element.content_description
