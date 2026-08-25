# 🤖 AI Digital Guide - API Spec (v1.0)

## [POST] /api/v1/decide
AI 에이전트가 현재 화면을 보고 다음 행동을 결정하는 핵심 API

### 📤 Request (Android -> Server)
- `session_id`: (String) 세션 식별자
- `goal`: (String) 사용자 최종 목표
- `ui_tree`: (List) 화면 노드 목록
    - `node_id`: (String) 클라이언트 부여 노드 ID
    - `text`: (String/Null) 노드 텍스트
    - `content_desc`: (String/Null) 접근성 설명
    - `clickable`: (Boolean) 클릭 가능 여부
    - `bounds`: (List[Int]) [left, top, right, bottom]
- `user_speech`: (String/Null) 사용자의 응답 텍스트 (결제 확인 등 대화 시 사용)

### 📥 Response (Server -> Android)
- `decision`: (Object)
    - `target_node_id`: (String/Null) 수행할 노드 ID
    - `action_type`: (String) "CLICK" | "SET_TEXT"
    - `input_value`: (String/Null) 입력할 값
- `status`: (String) `CONTINUE` | `WAIT_FOR_CONFIRM` | `DONE` | `FAIL`
- `voice_message`: (String) TTS로 읽어줄 안내 문구 (빈 문자열 가능)
- `confidence`: (Float) AI 판단 신뢰도
