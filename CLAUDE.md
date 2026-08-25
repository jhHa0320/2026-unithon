# CLAUDE.md - AI Digital Guide Layer (모노레포)

## 1. 프로젝트 개요

- 타겟 환경: Android Mobile (Kotlin), 초기 MVP 대상 앱: **카카오톡 (사진 보내기 시나리오 — 예: "엄마한테 사진 보내줘" → 대화방 선택 → 사진 첨부 → 전송)**
- 백엔드 파이프라인과 API 계약은 앱에 종속되지 않는다. 시나리오가 바뀌어도 바뀌는 건 프롬프트(`services/prompt.py`)뿐이다.
- **이 레포는 백엔드(FastAPI)와 Android 클라이언트(Kotlin)를 함께 포함하는 모노레포다.** 폴더로 담당 영역이 나뉜다.

### 데이터 흐름
[사용자 목표] → [Android 접근성 서비스] → [컨텍스트 빌더(JSON)] → [백엔드 API] → [LLM] → [오버레이] → (반복, 정보 부족 시 되묻기 포함 — §5-1)

## 2. 팀 구성 및 담당 폴더

| 영역 | 담당 폴더 | 담당자 |
|---|---|---|
| 백엔드 | `backend/` | (작성 필요) |
| Android | `android/` | (작성 필요) |
| AI/LLM | (작성 필요 — backend 내부인지 별도 폴더인지) | (작성 필요) |

## 3. 기술 스택

### 백엔드
- Python 3.11+, FastAPI, Pydantic v2, uvicorn
- 세션: in-memory dict + TTL (Redis 아님, 해커톤 스코프상 불필요)

### Android
> (작성 필요 — Android 담당자)
- Kotlin 버전 / minSdk / targetSdk:
- UI 프레임워크 (Compose / View):
- 사용 라이브러리:

### AI/LLM
- 사용 모델: Gemini `gemini-3.6-flash` (`thinking_level: low`) — 상세는 §9
- 호출 방식: 백엔드 경유. API 키 보호를 위해 Android는 직접 호출하지 않는다.
- 프롬프트 템플릿 위치: `backend/services/prompt.py`

## 4. 안전 원칙 (절대 준수 — 전체 프로젝트 공통)

1. **화면 데이터 비영속화**: UI Tree/노드 데이터는 요청 처리 중에만 메모리에 존재, 추론 직후 폐기. DB/파일 저장 금지.
2. **보안 통제 우회 금지**: Google Play 접근성 API 정책 준수 범위 내에서만 동작.
3. **신뢰도 게이트**: LLM confidence가 임계값 미만이면 백엔드가 응답을 무시하고 `status=ASK_USER`로 강제 override.
4. **민감정보 마스킹**: 비밀번호/주민번호/계좌번호 등은 LLM 전송 전 서버단에서 마스킹.
5. **후보가 여럿이면 추측 금지**: 목표가 가리키는 대상과 비슷한 항목이 화면에 둘 이상이면(예: `김엄마` / `엄마♥`) 임의로 고르지 않고 되묻는다. 실측된 최다 오작동 원인이며, 프롬프트 규칙으로 강제한다(§9).

## 4-1. 실행 범위 (In-Scope / Out-of-Scope)

AI가 대화방 선택부터 **사진 전송 버튼까지** accessibility 자동 클릭(`performAction(ACTION_CLICK)`/`setText`)으로 중단 없이 진행한다. 별도의 확인 탭을 두지 않는다.

- **In-Scope**: 카카오톡 앱을 실행해 수신자 대화방을 찾고, 사진을 선택해 첨부하고, 전송까지 완료하는 것. 우리 앱은 오버레이로 진행 상황만 안내하고 실제 조작은 카카오톡 UI를 그대로 자동 조작한다.
- **Out-of-Scope**: 카카오톡 API·SDK 연동. 사진을 우리 서버에 업로드하거나 저장하는 것. 어디까지나 기기 내 접근성 조작만 한다.

### 이 시나리오의 핵심 위험 — 취소 불가능한 전송

**잘못된 사람에게 전송된 사진은 되돌릴 수 없다.** 이전 시나리오(KTX 예매)에서는 오판이 금전 손실이었지만 수수료를 내고 취소가 가능했다. 지금은 그마저 없고, 사생활 침해 성격이 있어 피해의 종류가 다르다.

- 수신자가 확정되지 않았으면 **절대 진행하지 않는다** (§4 안전 원칙 5번).
- 전송 대상 사진 역시 확정되지 않았으면 되묻는다.
- 테스트·리허설은 본인 계정이나 합의된 상대에게만 할 것.

## 5. API 계약 — `POST /api/v1/decide`

### Request

```python
class ElementDTO(BaseModel):
    id: int
    text: str | None = None
    content_description: str | None = None
    class_name: str
    clickable: bool
    bounds: list[int]  # [left, top, right, bottom] — 4개, left<right, top<bottom

class HistoryEntry(BaseModel):
    step: int
    selected_text: str

class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str
    elements: list[ElementDTO] = Field(min_length=1)
    user_speech: str | None = None       # 예/아니오 등 확인 응답 (§5-1 참고)
    history: list[HistoryEntry] | None = None  # 생략 시 서버가 session_id로 조회
```

### Response

```python
class DecideResponse(BaseModel):
    target_node_id: int | None = None
    action_type: Literal["CLICK", "SET_TEXT"] | None = None
    input_value: str | None = None       # SET_TEXT일 때 입력할 값
    instruction: str                     # 로그/디버깅용 — 사용자에게 읽어주지 않음
    voice_message: str = ""              # TTS로 읽어줄 문구
    confidence: float                    # 0.0 ~ 1.0
    status: Literal["CONTINUE", "DONE", "ASK_USER", "UNSUPPORTED"]
    reason: str | None = None
```

> id는 string이 아니라 int. camelCase 초안은 폐기, 이 스키마로 통일한다. Android 쪽 `model/Types.kt`와 필드명이 정확히 일치하는지 Android 담당자가 대조 확인할 것.
>
> **불변식 (서버가 보장, 클라이언트는 신뢰해도 됨)**: `target_node_id != null`이면 `action_type != null`이고, `action_type == "SET_TEXT"`이면 `input_value != null`이다. LLM 응답이 이를 위반하면 서버가 `UNSUPPORTED`로 강등한다.
>
> **에러 응답은 이 스키마가 아니다.** 검증 실패·서버 오류 시 `{"error_code": ..., "message": ...}` 포맷으로 응답하므로 클라이언트는 HTTP 상태코드(422/4xx/5xx)로 먼저 분기해야 한다. 상세는 `dumps/API_SPEC.md` 참고.

## 5-1. 정보 부족 시 되묻기 (ASK_USER 슬롯필링)

사용자의 첫 발화가 필요한 정보(누구에게·어떤 사진 등)를 다 담고 있지 않은 경우가 기본 전제다. 예: "사진 보내줘"만 말하고 끝나는 경우.

- **판단 주체는 LLM이다.** 백엔드가 별도 슬롯 검증 로직을 두지 않는다 — 현재 화면 요소와 지금까지의 `goal`/`history`만으로 다음 클릭/입력을 확정할 수 없다고 LLM이 판단하면, `status="ASK_USER"`, `voice_message`에 사용자에게 물어볼 질문 문장(예: "누구에게 보낼까요?"), `target_node_id=null`을 반환한다. 이는 §4의 confidence 게이트로 인한 강제 override와는 별개의, LLM 스스로의 정상적인 응답이다.
  > 결정론적 슬롯 검사를 백엔드에 넣는 안을 검토했으나 **구현하지 않기로 했다.** 프롬프트에 "후보가 여럿이면 되묻기" 규칙 하나를 추가하니 모호한 목표의 되묻기 성공률이 67% → 100%가 되어 코드가 불필요해졌다(§9).
- **되묻기는 화면 단계마다 반복될 수 있다** — 수신자를 물어본 다음 어떤 사진인지 묻고, 사진첩 화면에서 다시 좁히는 식으로 매 스텝 반복 가능하다.
- **후보가 여럿일 때도 되묻는다** — 목표에 "엄마"라고만 했는데 친구 목록에 `김엄마`·`엄마♥`가 함께 있으면 둘 중 하나를 고르지 않고 물어본다. 전송은 되돌릴 수 없기 때문이다(§4-1).
- **답변 전달 방식** — 답변의 성격에 따라 두 경로로 나뉜다. 둘 다 같은 `session_id`로 재요청한다.
  - **정보 제공형 답변**("엄마한테요", "어제 찍은 거요")은 **`goal`에 이어붙인다**. 예: `goal = "사진 보내줘"` → `goal = "사진 보내줘. 받는 사람은 김엄마, 어제 찍은 사진입니다."`. 목표 자체를 영구히 구체화하는 정보이므로 이후 모든 스텝에서 계속 유효해야 하기 때문이다.
  - **예/아니오 확인 응답**("응", "아니 취소해줘")은 **`user_speech`에 담는다**. 직전 질문에만 유효한 일회성 응답이라 `goal`에 누적하면 목표 문장이 오염된다. 긍정/부정 판정은 백엔드가 수행한다(작업 B-4).
- **읽어줄 문구는 항상 `voice_message`다.** `instruction`은 서버 로그·디버깅용 요약이므로 TTS로 읽지 말 것.
- 이 흐름은 `elements`(현재 화면 요소)와 함께 매번 새로 전송되므로, 사용자가 답변하는 사이 화면이 안 바뀌어도 문제없다.

1. 요청 수신 및 pydantic 검증 (`elements` 빈 배열 체크, `bounds` 4개 정수·`left<right`·`top<bottom` 검증)
2. 민감 요소 **탐지** — 위험 키워드(전송/인증/삭제 등) 매칭 element를 로그용으로 카운트만 한다. §4-1대로 전송까지 자동 진행해야 하므로 **elements에서 제외하지 않는다**
3. 세션 로드 — `session_id`로 history(최근 2~3개) 조회
4. 민감 텍스트 마스킹
5. LLM 호출 (`services/ai_client.py`)
6. confidence 게이트 — 임계값 미만이면 `ASK_USER`로 강제 override
7. 응답 검증 — `target_node_id`가 원본 elements에 실재하는지, `action_type`/`input_value`가 정합한지 확인. 위반 시 `UNSUPPORTED`
8. 로깅 — `text`/`content_description` 원문 제외하고 기록
9. 세션 갱신
10. 응답 반환

## 7. 폴더 구조

project-root/
├── backend/
│ ├── main.py
│ ├── config.py
│ ├── routers/
│ │ └── decide.py
│ ├── services/
│ │ ├── ai_client.py # LLM 호출 (현재는 규칙 기반 MockAIClient)
│ │ ├── safety.py # 마스킹, confidence 게이트, 응답 검증 (전송은 차단하지 않음)
│ │ └── session.py # session_id 기반 history 관리
│ ├── schemas/
│ │ ├── request.py # DecideRequest, ElementDTO, HistoryEntry
│ │ └── response.py # DecideResponse
│ ├── core/
│ │ ├── logging.py # 민감정보 제외 로깅
│ │ └── errors.py # 공통 에러 포맷
│ └── tests/
│
└── android/
(작성 필요 — Android 담당자가 실제 패키지/폴더 구조로 채울 것)


## 8. Android 개발 맥락

> (작성 필요 — Android 담당자)

- 패키지명:
- AccessibilityService 등록 방식 / 설정 파일 위치:
- Overlay 구현 방식 (View / Compose, TYPE_ACCESSIBILITY_OVERLAY 사용 여부):
- UI Tree → 백엔드 요청 직렬화 위치 (컨텍스트 빌더 파일):
- 백엔드 API 호출 위치 및 방식 (Retrofit 등):
- 빌드/실행 명령어:
- 알려진 제약/이슈:

## 9. AI/LLM 개발 맥락

- **사용 모델 및 API**: Gemini `gemini-3.6-flash`, `thinking_level: low`
  (`generateContent` + structured output, `google-genai>=2.3`). 실측 응답 콜당 약 2.4초.
  모델명·추론 깊이·타임아웃은 `config.py`의 `GEMINI_MODEL` / `GEMINI_THINKING_LEVEL` / `GEMINI_TIMEOUT_SECONDS`에서 관리한다.
  **`gemini-1.5-*`(2025-09-29 종료), `gemini-2.0-*`(2026-06-01 종료)는 사용할 수 없다.**
  `temperature`/`top_p`/`top_k`는 2026-07-21자로 deprecated — 옛 예제를 복사하지 말 것.
  `thinking_level`을 `medium`으로 올려도 정확도는 같고 지연만 1.8배라 `low`를 쓴다.
- **호출 방식**: 백엔드 경유. API 키가 클라이언트에 노출되면 안 되므로 Android는 절대 직접 호출하지 않는다.
- **프롬프트 템플릿 위치**: `services/prompt.py`. `SYSTEM_INSTRUCTION` + `build_input()`.
  프롬프트를 바꾸면 `PROMPT_VERSION`을 올린다 — 로그에 함께 기록되므로 정확도 회귀 시 어느 버전인지 추적된다.
- **`ai_client.py`와의 연동**: `AIClient` Protocol 아래 `MockAIClient`(규칙 기반)와 `GeminiAIClient`(실제 호출) 두 구현체.
  `routers/decide.py`의 `get_ai_client()`가 `GEMINI_API_KEY` 유무로 고른다 —
  **키가 없으면 Mock으로 폴백**하므로 키 없는 팀원도 서버를 띄울 수 있다.
- **LLM 응답 스키마**: `schemas/llm.py`의 `LLMDecision`. 클라이언트 계약(`DecideResponse`)과 의도적으로 분리했다.
  `UNSUPPORTED`는 서버 판정이라 LLM이 선택할 수 없고, null 대신 센티널(`-1`/`"NONE"`/`""`)을 써서
  JSON Schema에 `anyOf`가 생기지 않게 했다(프로바이더별 스키마 지원 편차 회피). 변환은 `_to_decide_response()`.
- **confidence 산출**: 모델이 직접 출력한다. **단, 자기보고 confidence는 보정이 안 되어 있다** —
  대부분 0.9 언저리에 몰리고 틀릴 때도 높게 나온다. 되돌릴 수 없는 전송이 걸린 게이트를 여기에만 맡기지 말 것.
  **보완 코드는 넣지 않기로 했다** — 결정론적 슬롯 검사 대신 프롬프트에 "후보가 여럿이면 되묻기" 규칙을
  추가하니 모호한 목표의 되묻기 성공률이 67% → 100%가 되어 필요가 없어졌다. 완화 코드를 짜기 전에
  프롬프트로 먼저 시도하고 측정할 것.
- **Vision fallback**: 미사용. 사진첩 썸네일처럼 접근성 라벨이 없는 이미지 UI를 만났을 때만 도입한다.
- **알려진 제약/이슈**:
  - **`interactions` API를 쓰지 않는다.** 요청은 나가는데 서버가 응답 헤더를 보내지 않고 150초를 기다려도
    무응답이었다(2026-08-25 측정). 같은 키·모델로 `generateContent`는 2초대에 정상 응답한다.
  - **`gemini-3.7-flash`는 쓰지 않는다.** 최신이지만 응답에 29초가 걸려 KPI(30초)를 넘는다. 유료 티어에서도 동일.
  - **HTTP deadline은 10초 미만 불가.** Gemini가 `400 Minimum allowed deadline is 10s`로 거부한다.
    `http_options.timeout`은 **밀리초** 단위이고, 하한 미만이면 `GeminiAIClient`가 clamp한다.
    라우터 타임아웃(12초)은 이보다 커야 `asyncio.to_thread` 스레드가 풀린다.
  - 무료 티어는 모델당 하루 20회다. 리허설을 반복하면 금방 걸리므로 유료 전환이 사실상 필수.
  - 프롬프트 캐싱 미적용. 시스템 프롬프트가 매 콜 반복되므로 콜 수가 늘면 도입 검토.

## 10. 실행 명령어

### 백엔드
- `pip install -r requirements.txt`
- `cp .env.example .env` 후 `GEMINI_API_KEY` 입력 (비워 두면 Mock으로 동작)
- `uvicorn backend.main:app --reload --port 8000`
- `pytest backend/tests`
- Swagger UI: http://127.0.0.1:8000/docs · 에뮬레이터에서 호스트 접근: `http://10.0.2.2:8000`

### Android
> (작성 필요 — Android 담당자)

## 11. 코드 스타일

### 공통
- 커밋: 기능 단위로 짧게 (예: `feat: add confidence gate`)

### 백엔드 (Python)
- 모든 함수/클래스에 타입 힌트 필수
- pydantic 모델로 입출력 검증, dict 그대로 주고받지 않기
- 비즈니스 로직은 router가 아니라 services/에

### Android (Kotlin)
> (작성 필요 — Android 담당자)

## 12. 하지 말 것

- Redis, 외부 DB 등 스코프 밖 인프라 도입 금지
- 카카오톡 API/SDK 연동 금지 — 전송은 카카오톡 앱 UI를 accessibility로 자동 조작해 실행 (4-1 참고)
- 사진을 서버로 업로드하거나 저장하는 것 금지 — 기기 내 접근성 조작만 한다
- confidence 임계값, 위험 키워드 목록 하드코딩 금지 — config.py에서 관리
- 빈 elements 리스트 등 예외 상황에서 서버가 죽지 않고 항상 에러 포맷으로 응답하게 할 것
- 다른 담당자 폴더(backend가 아니면 android/, 그 반대도 마찬가지)의 코드를 사전 협의 없이 수정하지 말 것