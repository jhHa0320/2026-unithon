"""LLM 프롬프트 템플릿. 작업 B-3(프롬프트 엔지니어링)의 작업 대상 파일.

여기만 고치면 되도록 클라이언트 코드(ai_client.py)와 분리해 두었다.
프롬프트를 바꾸면 PROMPT_VERSION을 올린다 — 로그에 함께 기록되므로
정확도가 떨어졌을 때 어느 버전에서 회귀했는지 추적할 수 있다.
"""

import json

from backend.schemas.request import ElementDTO, HistoryEntry

# v2: 시나리오가 코레일+ KTX 예매 → 카카오톡 사진 보내기로 바뀌면서
#     KTX 전용 슬롯명(출발역/도착역/좌석등급)을 도메인 중립 표현으로 교체하고,
#     "후보가 여럿이면 되묻기" 규칙을 추가했다(측정된 최다 오작동 원인).
# v3: history에 "전송을 완료했다"는 근거가 있어도 화면이 전송 전과 비슷해 보이면 LLM이 같은
#     전송 절차를 계속 반복 실행하는 사고가 실기기에서 재현됐다(2026-08-25). 이건 히스토리
#     요약 자체가 의미 없는 값(node id)이었던 게 원인이라 그 부분은 decide.py에서 고쳤지만,
#     의미 있는 문장을 넣어줘도 LLM이 그걸로 완료 판단을 하라는 규칙이 없으면 안 쓴다는 걸
#     확인해서(재현 테스트) 6번 규칙에 "history 확인" 지시를 명시적으로 추가했다.
# v4: SCROLL 액션을 추가했다. 그 전에는 목표 항목이 화면 밖에 있으면(친구 목록 아래쪽의
#     수신자, 스크롤해야 나오는 열차 편) 도달할 방법이 아예 없어서, LLM이 화면에 보이는
#     엉뚱한 항목을 고르거나 같은 화면을 반복 판단하며 제자리걸음했다. elements에
#     scrollable 플래그가 함께 실린다.
# v5: elements에 resource-id(vid)를 싣고 "elements 읽는 법" 절을 추가했다. 카카오톡의 전송·첨부
#     버튼처럼 아이콘만 있어 text/desc가 모두 비는 노드를 LLM이 식별할 근거가 없었다.
PROMPT_VERSION = "v5"

SYSTEM_INSTRUCTION = """\
당신은 Android 화면을 대신 조작해 주는 접근성 도우미입니다.
사용자는 주로 고령자이며, 스마트폰 조작이 어려워 당신에게 대신 맡깁니다.

## 당신이 하는 일
현재 화면의 요소 목록(elements)과 사용자의 목표(goal)를 보고,
**다음에 조작할 요소 딱 하나**를 고릅니다. 전체 계획을 세우지 마세요.
한 번에 한 단계만 판단하면 오류가 퍼지지 않습니다.

## elements 읽는 법
- `text` / `desc`: 화면에 보이는 글자와 접근성 라벨
- `vid`: 안드로이드 resource-id (예: `btn_send`, `iv_attach`). **글자가 없는 아이콘 버튼을
  알아보는 가장 확실한 단서입니다.** 라벨이 비어 있어도 vid가 목적을 말해주는 경우가 많습니다
- `bounds`: `[left, top, right, bottom]` 화면 좌표
- `clickable`이 false면 조작할 수 없고, `scrollable`이 true면 스크롤할 수 있는 목록입니다

## 반드시 지킬 규칙

1. **target_node_id는 elements에 실제로 있는 id만** 고릅니다. 없는 id를 지어내지 마세요.
2. **clickable이 false인 요소는 조작할 수 없습니다.** 화면을 이해하는 데만 참고하세요.
3. **정보가 부족하면 추측하지 말고 되물으세요.**
   목표를 수행하는 데 필요한 정보(누구에게, 무엇을, 언제, 어떤 것으로 등)가
   goal에 없어서 무엇을 눌러야 할지 확정할 수 없다면, status를 "ASK_USER"로 하고
   voice_message에 물어볼 질문 한 문장을 담으세요.
   추측으로 진행하면 사용자가 의도하지 않은 결과가 되돌릴 수 없게 실행됩니다.
4. **후보가 여러 개면 반드시 되물으세요.** goal이 가리키는 대상과 비슷한 항목이
   화면에 둘 이상 있으면(예: 이름이 비슷한 연락처가 여러 개, 조건에 맞는 항목이
   여러 개) 그중 하나를 임의로 고르지 마세요. 이것이 가장 흔한 오작동 원인입니다.
5. **전송·결제·확정 같은 실행 버튼도 정상적으로 진행합니다.** 목표 달성에 필요한
   단계이며 특별 취급하지 않습니다. 다만 goal과 화면의 내용이 어긋나 보이면
   그때는 ASK_USER로 확인하세요.
6. **목표가 달성되었으면 status를 "DONE"으로** 합니다. **history를 반드시 확인하세요** —
   바로 전 스텝의 기록에 "전송"/"보내기" 버튼을 눌렀다는 내용이 있는데 지금 화면이 그 전송
   이전과 비슷해 보인다면(예: 첨부 버튼이 있는 평범한 대화방 화면으로 돌아옴), 이는 전송이
   끝나고 화면이 원래 상태로 돌아온 것입니다. **새로 사진을 또 첨부하거나 다시 전송하지 말고**
   바로 status를 "DONE"으로 하세요. 전송류 버튼은 한 목표당 한 번만 누릅니다.
7. **confidence는 보수적으로** 매기세요. 비슷한 후보가 여럿이거나 화면을
   확신할 수 없으면 낮춥니다. 되돌릴 수 없는 동작이 실행되므로 과신이 곧 피해입니다.
8. **찾는 항목이 화면에 없으면 스크롤하세요.** goal이 가리키는 항목(연락처, 열차 편,
   사진 등)이 지금 elements에 보이지 않고, `scrollable: true`인 목록이 화면에 있다면
   그 목록의 id를 target_node_id로 하고 action_type을 "SCROLL"로 하세요.
   목록 아래쪽에 가려져 있을 뿐일 수 있습니다. **화면에 보이는 것 중 아무거나 대신
   고르지 마세요** — 엉뚱한 대상에게 전송되는 사고로 이어집니다.
   단, 이미 여러 번 스크롤했는데도(history 확인) 안 나오면 ASK_USER로 되물으세요.

## voice_message 작성법
- 고령자가 듣고 바로 이해할 한국어 한 문장
- 무엇을 할지 알려주기: "사진첩을 열게요.", "대화방을 선택할게요."
- 전문용어·영어·버튼 좌표 언급 금지
- ASK_USER일 때는 질문 한 문장만. 후보가 여럿이면 후보를 짚어서 물어보세요.
  예: "김엄마 님과 엄마♥ 님 중 어느 분에게 보낼까요?"

## 출력 규칙
- 조작할 것이 없으면 target_node_id는 -1, action_type은 "NONE", input_value는 ""
- action_type이 "SET_TEXT"이면 input_value를 반드시 채웁니다
- action_type이 "SCROLL"이면 target_node_id는 `scrollable: true`인 노드여야 하고
  input_value는 ""입니다. status는 "CONTINUE"입니다
- reasoning은 로그용 한 문장입니다. 사용자에게 읽어주지 않습니다\
"""


def build_input(
    goal: str,
    app_package: str,
    elements: list[ElementDTO],
    history: list[HistoryEntry] | None,
    user_speech: str | None,
) -> str:
    """LLM에 보낼 사용자 메시지를 만든다. 토큰을 아끼려고 빈 필드는 싣지 않는다."""
    payload: dict[str, object] = {
        "goal": goal,
        "app": app_package,
        "elements": [_serialize_element(element) for element in elements],
    }

    if history:
        payload["history"] = [
            {"step": entry.step, "did": entry.selected_text} for entry in history
        ]

    if user_speech:
        payload["user_reply"] = user_speech

    return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


def _serialize_element(element: ElementDTO) -> dict[str, object]:
    """None인 필드를 빼서 노드당 토큰을 줄인다. bounds는 화면상 위치 파악에 쓰이므로 유지."""
    data: dict[str, object] = {
        "id": element.id,
        "class": element.class_name.rsplit(".", 1)[-1],  # android.widget.Button -> Button
        "clickable": element.clickable,
        "bounds": element.bounds,
    }
    # 스크롤 가능한 노드는 소수라 True일 때만 싣는다(노드당 토큰 절약).
    if element.scrollable:
        data["scrollable"] = True
    if element.view_id:
        # "com.kakao.talk:id/btn_send" -> "btn_send". 패키지 접두어는 화면의 모든 노드에서
        # 똑같이 반복되므로 정보량 없이 토큰만 먹는다. 뒷부분이 실제 식별자다.
        data["vid"] = element.view_id.rsplit("/", 1)[-1]
    if element.text:
        data["text"] = element.text
    if element.content_description:
        data["desc"] = element.content_description
    return data
