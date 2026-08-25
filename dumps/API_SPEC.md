# 🤖 AI Digital Guide - API Spec (v2.0)

> **v2.0 변경 요약 (v1.0에서 마이그레이션 필요)**
> v1.0의 `ui_tree` / `node_id`(String) / 중첩 `decision` 객체 / `WAIT_FOR_CONFIRM`·`FAIL` 상태는 **폐기**되었습니다.
> v2.0은 `elements` / `id`(Int) / 평탄화된 응답 / `ASK_USER`·`UNSUPPORTED` 상태를 사용합니다.
> 이 문서가 서버 구현의 정본입니다. 구현체: `backend/schemas/request.py`, `backend/schemas/response.py`.

---

## [POST] /api/v1/decide

AI 에이전트가 현재 화면을 보고 다음 행동을 결정하는 핵심 API. 화면 1개당 요청 1개를 보내고,
응답의 행동을 실행한 뒤 바뀐 화면으로 다시 요청하는 루프 구조입니다.

### 📤 Request (Android → Server)

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `session_id` | String | ✅ | 세션 식별자. 같은 목표를 수행하는 동안 동일 값 유지 |
| `goal` | String | ✅ | 사용자 최종 목표 (예: "서울에서 부산 가는 KTX 예매해줘") |
| `app_package` | String | ✅ | 현재 앱 패키지명 (예: `com.korail.talk`) |
| `elements` | List | ✅ | 화면 노드 목록. **비어 있으면 422** |
| `user_speech` | String \| null | ❌ | 사용자의 음성 응답(STT 결과). `ASK_USER`에 답할 때만 채움 |
| `history` | List \| null | ❌ | 이전 단계 요약. **생략 시 서버가 `session_id`로 자동 조회하므로 보통 null로 두면 됨** |

**`elements[]` 항목**

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `id` | Int | ✅ | 클라이언트가 부여하는 노드 ID. **응답의 `target_node_id`와 매칭됨** |
| `text` | String \| null | ✅ | 노드 텍스트 |
| `content_description` | String \| null | ✅ | 접근성 설명 |
| `class_name` | String | ✅ | 위젯 클래스 (예: `android.widget.EditText`). 입력 필드 판별에 사용 |
| `clickable` | Boolean | ✅ | 클릭 가능 여부 |
| `bounds` | List[Int] | ✅ | `[left, top, right, bottom]`. **정확히 4개, `left<right`, `top<bottom`** |

**`history[]` 항목**: `step`(Int), `selected_text`(String)

### 📥 Response (Server → Android)

| 필드 | 타입 | 설명 |
|---|---|---|
| `target_node_id` | Int \| null | 조작할 노드의 `id`. 조작할 것이 없으면 null |
| `action_type` | String \| null | `"CLICK"` \| `"SET_TEXT"`. `target_node_id`가 null이면 null |
| `input_value` | String \| null | `SET_TEXT`일 때 입력할 문자열 |
| `instruction` | String | 로그·디버깅용 요약. **사용자에게 읽어주지 말 것** |
| `voice_message` | String | TTS로 읽어줄 문구. 읽을 것이 없으면 빈 문자열 |
| `confidence` | Float | 0.0 ~ 1.0 |
| `status` | String | `CONTINUE` \| `DONE` \| `ASK_USER` \| `UNSUPPORTED` |
| `reason` | String \| null | 게이트에 걸린 사유 (디버깅용) |

**status별 클라이언트 동작**

| status | 의미 | 클라이언트가 할 일 |
|---|---|---|
| `CONTINUE` | 다음 행동이 결정됨 | `voice_message` 재생 → `action_type` 실행 → 화면 변화 후 재요청 |
| `DONE` | 목표 달성 또는 사용자가 중단 | `voice_message` 재생 후 루프 종료 |
| `ASK_USER` | 판단 불가, 사용자 확인 필요 | `voice_message` 재생 → 마이크 활성화 → 답변을 `user_speech`에 담아 재요청 |
| `UNSUPPORTED` | 서버가 응답을 거부함 | `voice_message` 재생 후 루프 중단 |

> `target_node_id`가 null이 아니면 `action_type`은 **항상** null이 아님이 보장됩니다.
> `action_type == "SET_TEXT"`이면 `input_value`도 **항상** null이 아님이 보장됩니다.
> (서버가 검증 후 위반 시 `UNSUPPORTED`로 강등하므로, 클라이언트는 이 불변식을 신뢰해도 됩니다.)

### ⚠️ 에러 응답

검증 실패·서버 오류 시 **위 스키마가 아니라** 아래 포맷으로 응답합니다. HTTP 상태코드로 먼저 분기하세요.

```json
{ "error_code": "VALIDATION_ERROR", "message": "..." }
```

| HTTP | error_code | 발생 조건 |
|---|---|---|
| 422 | `VALIDATION_ERROR` | `elements` 빈 배열, `bounds` 형식 오류, 필수 필드 누락 |
| 4xx | `HTTP_ERROR` | 잘못된 경로/메서드, body 파싱 실패 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

### 💳 결제 단계에 대한 주의

서버는 결제·인증 관련 요소를 **차단하지 않습니다.** 에이전트가 결제 버튼까지 직접 클릭해
예매를 완결하는 것이 제품 목표이므로, `"결제하기"` 같은 노드도 그대로 `CLICK` 대상이 됩니다.
실행을 멈추는 것은 `confidence` 게이트(`ASK_USER`)와 응답 검증(`UNSUPPORTED`)뿐입니다.

개인정보(전화번호·계좌번호·주민번호 패턴)는 LLM 전송 전 서버가 마스킹하지만,
**노드 자체는 제거하지 않으므로** 조작 대상으로는 여전히 유효합니다.

---

## 🧪 요청/응답 예시

### 1. 일반 클릭

```json
// 요청
{
  "session_id": "demo", "goal": "서울에서 부산 가는 KTX 예매해줘",
  "app_package": "com.korail.talk", "user_speech": null, "history": null,
  "elements": [{
    "id": 1, "text": "승차권 예매", "content_description": null,
    "class_name": "android.widget.Button", "clickable": true,
    "bounds": [0, 0, 300, 120]
  }]
}
// 응답
{
  "target_node_id": 1, "action_type": "CLICK", "input_value": null,
  "instruction": "node 1 클릭", "voice_message": "승차권 예매를 누를게요.",
  "confidence": 0.9, "status": "CONTINUE", "reason": null
}
```

### 2. 텍스트 입력 (`class_name`이 EditText)

```json
// 응답
{
  "target_node_id": 7, "action_type": "SET_TEXT", "input_value": "서울",
  "instruction": "입력 필드(node 7)에 텍스트 입력",
  "voice_message": "출발역을 입력할게요.",
  "confidence": 0.9, "status": "CONTINUE", "reason": null
}
```

### 3. 사용자가 거절 (`user_speech: "아니 취소해줘"`)

```json
// 응답
{
  "target_node_id": null, "action_type": null, "input_value": null,
  "instruction": "사용자가 진행을 거절하여 흐름 종료",
  "voice_message": "알겠습니다. 여기서 멈출게요.",
  "confidence": 1.0, "status": "DONE", "reason": "user declined"
}
```

---

## 🚀 로컬 서버 실행

```bash
pip install -r requirements.txt
uvicorn backend.main:app --reload --port 8000
```

- 헬스체크: `GET http://127.0.0.1:8000/health` → `{"status":"ok"}`
- **Swagger UI: http://127.0.0.1:8000/docs** ← 스키마 확인·수동 테스트는 여기가 가장 편합니다
- 안드로이드 에뮬레이터에서 호스트 접근: `http://10.0.2.2:8000`

> 현재 서버는 **규칙 기반 Mock**(`MockAIClient`)으로 응답합니다. 계약은 확정이므로 통신 코드를
> 지금 붙여도 되며, Gemini 연동(작업 B-2) 시 응답 *내용*만 똑똑해지고 *형식*은 바뀌지 않습니다.

### Mock 서버의 현재 동작 규칙

1. `user_speech`에 부정어(아니/취소/그만…)가 있으면 → `DONE`
2. `user_speech`가 긍정/부정 판정 불가면 → `ASK_USER`
3. `class_name`에 `EditText`가 포함된 첫 clickable 노드 → `SET_TEXT` (`input_value: "서울"` 고정)
4. 그 외 첫 clickable 노드 → `CLICK`
5. clickable 노드가 하나도 없으면 → `ASK_USER`
