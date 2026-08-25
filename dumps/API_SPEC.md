# API Spec 실전 예제 (v2)

> **스키마의 소스 오브 트루스는 [`CLAUDE.md`](../CLAUDE.md) §5다.** 이 문서는 그 계약의 **실제 JSON 예시**만 담는다 — 스키마 정의를 여기에 중복해서 적지 말 것. (v1 시절 이 파일과 `backend/schemas/`가 각자 다른 계약을 들고 갈라진 적이 있다.)

## [POST] /api/v1/decide

화면 하나마다 한 번 호출한다. 서버는 "다음에 실행할 것 하나"만 돌려준다.

---

### 예시 1 — 첫 요청: 어떤 앱을 열까

아직 아무 앱도 실행하지 않은 상태. `app_package=null`, `elements=[]`, `installed_apps` 전송.

**Request**
```json
{
  "session_id": "s-20260825-001",
  "goal": "영희한테 카톡으로 방금 찍은 사진 보내줘",
  "app_package": null,
  "elements": [],
  "installed_apps": [
    { "package": "com.kakao.talk", "label": "카카오톡" },
    { "package": "com.nhn.android.search", "label": "네이버" },
    { "package": "com.google.android.apps.photos", "label": "Google 포토" }
  ],
  "history": null
}
```

**Response**
```json
{
  "action": "LAUNCH_APP",
  "target_node_id": null,
  "value": "com.kakao.talk",
  "instruction": "카카오톡을 열게요.",
  "confidence": 0.97,
  "status": "CONTINUE",
  "reason": null
}
```

Android: `getLaunchIntentForPackage("com.kakao.talk")` 실행 → 이후 요청부터 `app_package` 채워서 전송.

---

### 예시 2 — 텍스트 입력

**Request** (일부 생략)
```json
{
  "session_id": "s-20260825-001",
  "goal": "영희한테 카톡으로 방금 찍은 사진 보내줘",
  "app_package": "com.kakao.talk",
  "elements": [
    {
      "id": 12, "text": null, "content_description": "검색",
      "class_name": "android.widget.ImageButton",
      "clickable": true, "editable": false, "scrollable": false, "password": false,
      "bounds": [820, 100, 900, 180]
    },
    {
      "id": 17, "text": "이름 또는 전화번호 검색", "content_description": null,
      "class_name": "android.widget.EditText",
      "clickable": true, "editable": true, "scrollable": false, "password": false,
      "bounds": [40, 200, 1040, 280]
    }
  ],
  "history": [
    { "step": 1, "action": "LAUNCH_APP", "selected_text": "카카오톡을 열게요." }
  ]
}
```

**Response**
```json
{
  "action": "SET_TEXT",
  "target_node_id": 17,
  "value": "영희",
  "instruction": "영희님을 찾고 있어요.",
  "confidence": 0.94,
  "status": "CONTINUE",
  "reason": null
}
```

---

### 예시 3 — 정보 부족: 되묻기

`goal`이 "카톡 보내줘"뿐이라 받는 사람을 알 수 없는 경우.

**Response**
```json
{
  "action": "NONE",
  "target_node_id": null,
  "value": null,
  "instruction": "누구에게 보낼까요?",
  "confidence": 0.9,
  "status": "ASK_USER",
  "reason": null
}
```

Android: 루프 정지 → `instruction`을 TTS 재생 → STT 답변("영희요") 수신 →
**같은 `session_id`로** `goal = "카톡 보내줘. 받는 사람은 영희입니다."` 재요청.

---

### 예시 4 — 되돌릴 수 없는 행동: 구두 동의

전송 버튼을 누를 차례.

**Response**
```json
{
  "action": "NONE",
  "target_node_id": 42,
  "value": null,
  "instruction": "영희님께 사진을 보낼까요?",
  "confidence": 0.96,
  "status": "CONFIRM_REQUIRED",
  "reason": null
}
```

- `action`이 `NONE`이므로 Android는 **실행하지 않는다.** `target_node_id`는 서버가 어떤 행동에 동의를 받았는지 추적하는 용도다.
- Android: TTS 재생 → STT 답변("응, 보내줘") → `goal`에 이어붙여 재요청.

**동의 후 재요청의 Response**
```json
{
  "action": "CLICK",
  "target_node_id": 42,
  "value": null,
  "instruction": "영희님께 사진을 보냈어요.",
  "confidence": 0.96,
  "status": "CONTINUE",
  "reason": null
}
```

---

### 예시 5 — 완료

```json
{
  "action": "NONE",
  "target_node_id": null,
  "value": null,
  "instruction": "영희님께 사진을 보냈어요.",
  "confidence": 0.95,
  "status": "DONE",
  "reason": null
}
```

Android: 루프 종료, `instruction` TTS 재생.

---

## 에러 응답

검증 실패 시 422, 그 외 공통 포맷:

```json
{ "error_code": "VALIDATION_ERROR", "message": "..." }
```

주요 422 케이스:
- `app_package`가 있는데 `elements`가 빈 배열 (`app_package=null`일 때만 허용)
- `bounds`가 4개 정수가 아니거나 `left>=right` / `top>=bottom`

## Android 구현 시 주의

- 필드명은 **snake_case 그대로** 쓴다. camelCase로 바꾸지 말 것.
- `status`가 `CONTINUE`가 아니면 **어떤 경우에도 `action`을 실행하지 않는다.**
- 비밀번호 필드(`isPassword=true`)는 `text`를 **아예 보내지 않는다.**
