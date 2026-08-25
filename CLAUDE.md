# CLAUDE.md - AI Digital Guide Layer (모노레포)

## 1. 프로젝트 개요

**제품 정의**: 어르신이 앱 하나만 켜고 말하면, AI가 알맞은 앱을 스스로 찾아 열고 목표를 끝까지 수행하는 Android 접근성 레이어. 앱을 찾고 메뉴를 눌러가는 조작 노동 자체를 없앤다. 금전이 이동하거나 되돌릴 수 없는 행동(결제·전송·삭제) 직전에만 구두로 동의를 구하고, 동의를 받으면 그 버튼까지 AI가 직접 누른다.

- 타겟 환경: Android Mobile (Kotlin)
- **대상 앱은 고정되어 있지 않다.** 사용자가 무엇을 말하든 AI가 설치된 앱 중에서 적절한 앱을 골라 실행하는 것이 제품의 본질이다.

### 해커톤 데모 시나리오 (2026.08 · 10시간 스코프)

**카카오톡 사진 전송**: 사용자가 우리 앱을 실행하고 "영희한테 카톡으로 방금 찍은 사진 보내줘"라고 말하면 —
카카오톡 실행 → 친구 검색 → 대화방 진입 → 사진 첨부 → 최근 사진 선택 → **"영희님께 사진을 보낼까요?" 구두 동의** → 전송.

> **시나리오는 시나리오일 뿐이다.** 코드·프롬프트·스키마 어디에도 카카오톡이나 특정 앱을 하드코딩하지 않는다. 데모는 범용 에이전트가 카카오톡이라는 한 사례에서 동작함을 보이는 것이지, 카카오톡 전용 앱을 만드는 것이 아니다. 앱 이름이 코드에 등장하면 그건 버그다.

### 데이터 흐름

```
[사용자 발화] → [STT] → [백엔드 /decide] → [LLM: 어떤 앱을 열까]
   → [앱 실행] → [접근성 서비스: UI Tree 읽기] → [컨텍스트 빌더(JSON)]
   → [백엔드 /decide] → [LLM: 다음에 뭘 누를까] → [자동 클릭/입력 + TTS]
   → (화면 변경 감지 → 반복)
```

반복 중 두 가지 이유로 루프가 사용자에게 넘어온다 — 정보가 부족할 때(§5-1 `ASK_USER`), 되돌릴 수 없는 행동 직전(§5-2 `CONFIRM_REQUIRED`).

## 2. 팀 구성 및 담당 폴더

| 영역 | 담당 폴더 | 담당자 |
|---|---|---|
| 백엔드 | `backend/` (routers, schemas, services, core, tests) | 멤버 B |
| Android | `android/` | 멤버 A(권한/음성/UX), 멤버 C(접근성/자동조작) |
| AI/LLM | `backend/services/ai_client.py`, `backend/prompts/` | 멤버 B |

공용 파일(`CLAUDE.md`, `docs/ARCHITECTURE.md`, `backend/schemas/*.py`)은 셋 다 참조한다. **스키마를 바꾸는 커밋은 반드시 셋 다에게 영향**이 가므로 변경 전에 채팅으로 먼저 알릴 것.

## 3. 기술 스택

### 백엔드
- Python 3.11+, FastAPI, Pydantic v2, uvicorn
- LLM: **Google Gemini (`gemini-3.7-flash`)** — `google-genai` 공식 SDK
  - **프로바이더는 갈아끼울 수 있다.** `services/ai_client.py`의 `AIClient` Protocol만 만족하면 되고, `get_ai_client()`가 **설정된 키를 보고 자동 선택**한다 — `GEMINI_API_KEY` > `ANTHROPIC_API_KEY` > Mock. 라우터·스키마·프롬프트는 프로바이더를 몰라야 한다.
  - structured output(`response_mime_type="application/json"` + `response_schema=DecideResponse`)으로 응답 스키마를 강제한다. 프롬프트로 "JSON만 반환해"라고 부탁하지 않는다.
  - 시스템 프롬프트는 `system_instruction`으로 분리한다. 화면마다 반복되는 접두어라 컨텍스트 캐싱에 유리하다.
  - `temperature=0` — 같은 화면에서 매번 다른 판단이 나오면 디버깅이 불가능해진다.
  - 지연이 중요하면 `GEMINI_THINKING_BUDGET=0`으로 thinking을 끈다. 정확도가 모자라면 다시 올린다.
  - Vision fallback도 같은 모델에 image part를 추가하는 방식이라 별도 모델이 필요 없다.
  - 대안: Anthropic Claude(`claude-opus-5`, `ClaudeAIClient`)가 같은 Protocol로 구현되어 있다.
- 세션: in-memory dict + TTL (Redis 아님, 해커톤 스코프상 불필요)

### Android
- Kotlin, minSdk 26 / targetSdk 37, AGP 9.3.2
- UI: **기존 View + XML 유지** (Compose로 전환하지 않는다 — 우리 앱 화면은 마이크 버튼 하나뿐이라 전환 비용이 순손실이다)
- 화면 읽기: `AccessibilityService` + `rootInActiveWindow` 재귀 탐색
- 자동 조작: `performAction(ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_FORWARD)`, `performGlobalAction(GLOBAL_ACTION_BACK)`
- 앱 실행: `PackageManager.getLaunchIntentForPackage()` — **Manifest에 `<queries>` 선언 필수** (Android 11+ 패키지 가시성 제한 때문에 없으면 설치된 앱이 조회되지 않는다)
- 오버레이: `TYPE_ACCESSIBILITY_OVERLAY` — `SYSTEM_ALERT_WINDOW` 권한 요청이 불필요해 어르신에게 보여줄 권한 화면이 하나 줄어든다
- STT: `android.speech.SpeechRecognizer` (내장, 한국어, 무료)
- TTS: `android.speech.tts.TextToSpeech` (내장)
- 네트워킹: Retrofit + OkHttp + kotlinx.serialization (§5 스키마가 snake_case이므로 `@SerialName` 없이 그대로 매핑된다)
- 비동기/루프: Kotlin Coroutines + Flow

### 데모 스코프에서 제외한 것
- **Wake word(Porcupine) / 화면 Off 상시 감지** — 제품 비전에는 남아 있으나 10시간 데모에서는 빼고, 사용자가 우리 앱을 실행해 마이크를 탭하는 것으로 대체한다. Foreground Service·배터리 최적화 예외까지 붙이면 데모 실패 리스크가 가장 크다.

## 4. 안전 원칙 (절대 준수 — 전체 프로젝트 공통)

1. **화면 데이터 비영속화**: UI Tree/노드 데이터는 요청 처리 중에만 메모리에 존재, 추론 직후 폐기. DB/파일 저장 금지.
2. **보안 통제 우회 금지**: Google Play 접근성 API 정책 준수 범위 내에서만 동작.
3. **신뢰도 게이트**: LLM confidence가 임계값 미만이면 백엔드가 응답을 무시하고 `status=ASK_USER`로 강제 override.
4. **민감정보 마스킹**: 비밀번호 필드(`password=true`)와 주민번호·카드번호·계좌번호 패턴은 LLM 전송 전 서버단에서 마스킹.
5. **되돌릴 수 없는 행동 게이트**: 결제·송금·전송·삭제 계열 노드를 클릭하려는 응답은 구두 동의 없이 통과시키지 않는다 (§5-2).

## 4-1. 결제·되돌릴 수 없는 행동의 연동 범위 (In-Scope / Out-of-Scope)

결제는 Mock이 아니라 **대상 앱 자체의 실제 결제 플로우**를 그대로 자동 실행한다. 실제 금전이 이동한다.

- **In-Scope**: AI가 accessibility 자동 클릭(`performAction(ACTION_CLICK)`/`ACTION_SET_TEXT`)으로 목표를 진행하다가, 결제 버튼 앞에서 구두 동의를 받고 그 버튼까지 누른다. 대상 앱에 사용자가 사전 등록해 둔 실제 결제수단으로 그 앱 자체의 결제 화면이 실행된다.
- **Out-of-Scope**: 우리 backend/Android가 PG사 API를 직접 호출하는 별도 연동을 새로 만드는 것. 우리 시스템은 결제망에 직접 연결되지 않는다.
- **데모에서는 실제 결제를 하지 않는다.** 카카오톡 사진 전송의 "전송" 버튼이 동일한 `CONFIRM_REQUIRED` 게이트를 타므로, 실제 돈을 쓰지 않고도 결제 동의 UX를 그대로 시연할 수 있다. 결제와 전송은 코드상 같은 경로다 — 다른 게 아니라 같은 메커니즘의 다른 사례다.

## 5. API 계약 — `POST /api/v1/decide` (v2)

> **v2는 이전의 두 초안(`ui_tree`/`decision`/`voice_message` 계열, `elements`/`target_node_id` 계열)을 모두 대체한다.** 둘 다 앱 실행과 텍스트 입력을 표현할 수 없어 새 시나리오에서 쓸 수 없다. 필드명은 snake_case로 통일하며, Android `model/Types.kt`가 이 필드명과 1:1로 일치해야 한다.

### Request

```python
class ElementDTO(BaseModel):
    id: int                          # 세션 내 임시 id (화면 덤프마다 재부여)
    text: str | None
    content_description: str | None
    class_name: str
    clickable: bool
    editable: bool                   # ACTION_SET_TEXT 가능 여부
    scrollable: bool                 # 스크롤 컨테이너 여부
    password: bool = False           # 비밀번호 입력 필드 — text는 보내지 말 것
    bounds: list[int]                # [left, top, right, bottom]

class InstalledApp(BaseModel):
    package: str                     # 예: "com.kakao.talk"
    label: str                       # 예: "카카오톡"

class HistoryEntry(BaseModel):
    step: int
    action: str
    selected_text: str

class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str | None          # None = 아직 대상 앱을 실행하지 않은 상태
    elements: list[ElementDTO]
    installed_apps: list[InstalledApp] | None = None   # app_package가 None일 때만 전송
    history: list[HistoryEntry] | None = None
```

### Response

```python
class DecideResponse(BaseModel):
    action: Literal["CLICK", "SET_TEXT", "LAUNCH_APP",
                    "SCROLL_FORWARD", "SCROLL_BACKWARD", "BACK", "NONE"]
    target_node_id: int | None
    value: str | None                # SET_TEXT의 입력값 / LAUNCH_APP의 패키지명
    instruction: str                 # TTS로 읽어줄 문장
    confidence: float
    status: Literal["CONTINUE", "DONE", "ASK_USER", "CONFIRM_REQUIRED", "UNSUPPORTED"]
    reason: str | None = None
```

### status 의미

| status | Android가 할 일 | action / target_node_id |
|---|---|---|
| `CONTINUE` | `action`을 즉시 실행하고, 화면이 바뀌면 다시 요청. `action=NONE`이면 지금 할 게 없다는 뜻이니 화면이 안정되면 재요청 | 실제 action, 유효한 id |
| `DONE` | 루프 종료. `instruction`을 TTS로 읽고 마무리 | `NONE` / `null` |
| `ASK_USER` | 루프 정지. `instruction`을 질문으로 읽고 STT 답변을 `goal`에 이어붙여 재요청 (§5-1) | `NONE` / `null` |
| `CONFIRM_REQUIRED` | 루프 정지. `instruction`을 확인 요청으로 읽고 구두 동의를 `goal`에 이어붙여 재요청 (§5-2) | `NONE` / **누르려는 버튼의 id** |
| `UNSUPPORTED` | 루프 정지. `instruction`을 읽고 사용자에게 직접 하도록 안내 | `NONE` / `null` |

### 앱 실행 (첫 스텝)

첫 요청은 `app_package=null`, `elements=[]`, `installed_apps=[...]`로 보낸다. LLM은 `goal`과 설치된 앱 목록을 보고 `action="LAUNCH_APP"`, `value="<패키지명>"`, `status="CONTINUE"`를 반환한다. Android는 해당 앱을 실행하고, 이후 요청부터는 `app_package`를 채워 보낸다.

`app_package`가 있는데 `elements`가 빈 배열이면 422다. `app_package`가 `null`일 때만 빈 배열이 허용된다.

## 5-1. 정보 부족 시 되묻기 (ASK_USER 슬롯필링)

사용자의 첫 발화가 필요한 정보를 다 담고 있지 않은 경우가 기본 전제다. 예: "카톡 보내줘"만 말하고 끝나는 경우 — 누구에게, 무엇을 보낼지가 없다.

- **판단 주체는 LLM이다.** 백엔드가 별도 슬롯 검증 로직을 두지 않는다. 현재 화면 요소와 지금까지의 `goal`/`history`만으로 다음 행동을 확정할 수 없다고 LLM이 판단하면 `status="ASK_USER"`, `action="NONE"`, `target_node_id=null`, `instruction`에 질문 문장(예: "누구에게 보낼까요?")을 반환한다. 이는 §4의 confidence 게이트로 인한 강제 override와는 별개인, LLM 스스로의 정상적인 응답이다.
- **되묻기는 화면 단계마다 반복될 수 있다** — 받는 사람을 물은 다음엔 보낼 내용을, 사진이 여러 장이면 어떤 사진을 묻는 식이다.
- **답변 전달 방식**: Android는 `instruction`을 TTS로 재생하고 STT 답변을 받아, **같은 `session_id`로 `goal`에 답변을 이어붙여** 재요청한다. 예: `goal = "카톡 보내줘"` → `goal = "카톡 보내줘. 받는 사람은 영희입니다."` 별도의 `answer` 필드는 두지 않는다.
- 이 흐름은 `elements`와 함께 매번 새로 전송되므로, 사용자가 답변하는 사이 화면이 안 바뀌어도 문제없다.

## 5-2. 되돌릴 수 없는 행동 앞 구두 동의 (CONFIRM_REQUIRED)

결제·송금·전송·삭제처럼 되돌릴 수 없는 행동은 **구두 동의를 받은 뒤에만** AI가 버튼을 누른다.

- **1차 판단은 LLM**: 다음에 누를 버튼이 되돌릴 수 없는 행동이면 `status="CONFIRM_REQUIRED"`, `action="NONE"`, `instruction`에 확인 문장(예: "영희님께 사진을 보낼까요?", "총 59,800원을 결제할까요?")을 반환한다. 이때 `target_node_id`에는 **누르려는 그 버튼의 id를 그대로 담는다** — `action`이 `NONE`이라 Android는 실행하지 않지만, 서버가 어떤 행동에 동의를 받았는지 추적하는 데 쓴다.
- **2차 안전망은 서버**: LLM이 이를 빠뜨리고 `CONTINUE` + `CLICK`을 반환하더라도, 대상 노드의 라벨이 `config.IRREVERSIBLE_KEYWORDS`에 걸리면 백엔드가 `CONFIRM_REQUIRED`로 강제 override한다. LLM 프롬프트 하나에 안전을 걸지 않는다.
- **동의 전달 방식은 `ASK_USER`와 동일**하다 — TTS로 확인 문장을 읽고, STT 답변을 `goal`에 이어붙여 같은 `session_id`로 재요청한다. `user_speech` 같은 별도 필드를 두지 않는다.
- **무한 루프 방지**: 서버는 세션에 "어떤 노드에 대해 확인을 요청했는지"(`pending_confirmation`)를 기록한다. 다음 요청에서 LLM이 같은 노드에 대해 `CONTINUE`를 반환하면 — 즉 LLM이 누적된 `goal`에서 동의를 확인했다면 — 게이트를 통과시키고 기록을 지운다.
- 사용자가 거절하면 LLM이 `goal`의 거절 표현을 보고 다른 행동이나 `DONE`을 반환한다.

## 6. 백엔드 처리 파이프라인

1. 요청 수신 및 pydantic 검증 — `bounds` 4개 정수·좌표 역전(`left>right`) 거절, `app_package`가 있으면 `elements` 빈 배열 금지
2. 세션 로드 — history(최근 3개), `pending_confirmation`, 결정 캐시
3. 민감정보 마스킹 (`services/safety.py`)
4. 규칙 필터링 (`services/rules.py`) — 면적 0·순수 컨테이너·중복 노드 제거, 읽기 순서 정렬
5. 화면 지문 계산 + 반복 카운트

   **여기서부터 LLM 없이 끝날 수 있다 (§6-1)**
6. 같은 화면 반복 초과 → `UNSUPPORTED` (루프 차단)
7. 조작 가능 요소 없음 → `CONTINUE` + `NONE` (로딩 중, 대기)
8. `goal`에서 앱 특정 가능 → `LAUNCH_APP` (콜 1회 절약)
9. `(goal, 화면지문)` 캐시 적중 → 직전 결정 재사용

10. LLM 호출 (`services/ai_client.py`, 타임아웃 초과 시 `UNSUPPORTED`)
11. confidence 게이트 — 임계값 미만이면 `ASK_USER`로 강제 override
12. 되돌릴 수 없는 행동 게이트 — `IRREVERSIBLE_KEYWORDS` 매칭 시 `CONFIRM_REQUIRED`로 강제 override (§5-2)
13. 응답 검증 — `target_node_id`가 원본 elements에 실재하는지, `SET_TEXT`인데 `value`가 없는지 등. 위반 시 `UNSUPPORTED`
14. 로깅 — `text`/`content_description` 원문 제외하고 기록
15. 세션 갱신
16. 응답 반환

> **게이트는 규칙 경로에도 적용된다.** 6~9번으로 LLM 없이 만들어진 응답도 11~13번을 그대로 통과한다. "규칙으로 빨리 처리했으니 안전 검사는 건너뛴다"가 되면 안 된다.

> **safety는 위험 요소를 LLM 목록에서 제외하지 않는다.** 이전 설계는 결제 관련 element를 필터링해 빼버렸는데, 그러면 AI가 결제 버튼을 누른다는 제품 정의 자체가 성립하지 않는다. 지금 설계는 **빼는 대신 게이트를 건다**.

## 6-1. 규칙 기반 최적화 (`services/rules.py`)

화면 전환마다 LLM 1콜인 구조라 **콜 수와 콜당 토큰이 곧 원가이자 지연**이다. 답이 정해진 것은 LLM에게 묻지 않는다.

| 규칙 | 효과 |
|---|---|
| `filter_elements` — 면적 0·순수 컨테이너·중복 라벨 제거, 읽기 순서 정렬 | 노드 63% 감소 (실측) |
| `build_llm_payload` — null 제거, 클래스명 축약, 플래그 압축 | 페이로드 80% 감소 (실측) |
| `resolve_app` — `goal` ↔ 기기가 준 앱 라벨 대조 | LLM 콜 1회 완전 제거 |
| 결정 캐시 — `(goal, 화면지문)` | 중복 이벤트 낭비 콜 제거 |
| `is_loading_screen` | 답 없는 화면에 콜 낭비 안 함 |
| 반복 화면 차단 | 무한 루프 + 무한 과금 방지 |
| `needs_vision` | Vision을 상시가 아니라 필요할 때만 |

**규칙은 앱을 알지 못한다** (§12). `resolve_app`은 기기가 준 `installed_apps` 라벨과 사용자 `goal`만 문자열로 대조하며, 한국어 조사는 토큰 접두어 대조로 처리한다. 애매하면 `None`을 반환해 LLM에게 넘긴다.

**`position_hint`**: 라벨 없는 clickable 노드(사진 그리드 셀 등)에 읽기 순서 힌트를 붙여, 접근성 라벨이 없어도 "1번째 항목"으로 지목 가능하게 한다. 라벨이 없다고 곧바로 Vision이 필요한 것이 아니다.

**Vision vs UI Tree 비교와 선택 근거**는 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) §5-2 참고. 결론은 **Tree 우선 + 규칙 트리거 Vision** — Vision은 Tree의 대안이 아니라 추가이므로(실행에는 노드 핸들이 필요) 항상 `Tree + 이미지` 비용이 든다.

**설정값** (`config.py`): `MAX_ELEMENTS_TO_LLM`, `ENABLE_RULE_APP_RESOLUTION`, `ENABLE_DECISION_CACHE`, `APP_MATCH_MIN_SCORE`, `MAX_REPEATED_SCREENS`, `VISION_UNLABELED_RATIO`

## 7. 폴더 구조

```
project-root/
├── backend/
│   ├── main.py
│   ├── config.py
│   ├── routers/
│   │   ├── decide.py
│   │   └── dev.py         # 개발용 하네스 라우트 (GET /dev)
│   ├── services/
│   │   ├── ai_client.py    # LLM 호출 (Anthropic SDK)
│   │   ├── rules.py        # 규칙 기반 최적화 — 필터·압축·앱선택·캐시·루프차단
│   │   ├── safety.py       # 마스킹, confidence 게이트, 되돌릴 수 없는 행동 게이트
│   │   └── session.py      # session_id 기반 history + pending_confirmation 관리
│   ├── prompts/
│   │   └── decide_system.md   # LLM 시스템 프롬프트 (캐시 대상)
│   ├── dev/
│   │   ├── harness.html    # 브라우저 개발 하네스 (프로덕션 기능 아님)
│   │   └── check_llm.py    # LLM 연결 자가진단
│   ├── schemas/
│   │   ├── request.py      # DecideRequest, ElementDTO, InstalledApp, HistoryEntry
│   │   └── response.py     # DecideResponse
│   ├── core/
│   │   ├── logging.py      # 민감정보 제외 로깅
│   │   └── errors.py       # 공통 에러 포맷
│   └── tests/
│
└── android/
    (작성 필요 — Android 담당자가 실제 패키지/폴더 구조로 채울 것)
```

## 8. Android 개발 맥락

- 패키지명: `com.example.pathpilot`
- **필수 Manifest 선언 (현재 전부 없음)**: `INTERNET`, `RECORD_AUDIO`, `BIND_ACCESSIBILITY_SERVICE`를 갖는 `<service>`, 그리고 앱 실행을 위한 `<queries>`
- AccessibilityService 설정 파일: `res/xml/accessibility_service_config.xml` (`canRetrieveWindowContent=true`)
- UI Tree → 백엔드 요청 직렬화 위치: (작성 필요 — 컨텍스트 빌더 파일 경로)
- 백엔드 API 호출 위치 및 방식: Retrofit
- 빌드/실행 명령어: (작성 필요)
- 알려진 제약/이슈: (작성 필요)

## 9. AI/LLM 개발 맥락

- 모델: `gemini-3.7-flash` (google-genai SDK). 키만 바꿔 Claude로도 동작한다
- 연결 확인: `python -m backend.dev.check_llm` — 실제로 한 번 호출해 되는지 30초 안에 판정
- 프롬프트: `backend/prompts/decide_system.md` — 프롬프트 원칙은 **"전체 작업을 계획하라"가 아니라 "현재 화면에서 다음에 실행할 것 하나만 고르라"**. **프로바이더 중립으로 쓸 것**
- 응답 형식: structured output으로 `DecideResponse` 스키마 강제
- confidence: 모델이 직접 출력
- Vision fallback: 접근성 라벨이 없는 화면(이미지 그리드 등)에서만 스크린샷을 image 블록으로 추가

## 10. 실행 명령어

### 백엔드
- `pip install -r requirements.txt`
- `uvicorn backend.main:app --reload --port 8000` (세션이 프로세스 메모리에 있으므로 **단일 워커**로만 실행)
- 개발용 하네스: http://localhost:8000/dev — 에이전트 루프를 브라우저에서 눈으로 확인 (규칙/LLM 구분, 게이트 동작, 실기기 덤프 재생)
- `pytest backend/tests`

### Android
> (작성 필요 — Android 담당자)

## 11. 코드 스타일

### 공통
- 커밋: 기능 단위로 짧게 (예: `feat: add confirm gate`)

### 백엔드 (Python)
- 모든 함수/클래스에 타입 힌트 필수
- pydantic 모델로 입출력 검증, dict 그대로 주고받지 않기
- 비즈니스 로직은 router가 아니라 services/에

### Android (Kotlin)
> (작성 필요 — Android 담당자)

## 12. 하지 말 것

- **특정 앱을 하드코딩하지 말 것** — 카카오톡은 데모 사례일 뿐이다. 패키지명·버튼 라벨·화면 순서를 코드나 프롬프트에 박아넣으면 제품이 아니라 매크로가 된다
- Redis, 외부 DB 등 스코프 밖 인프라 도입 금지
- 우리 backend/Android가 PG사 API를 직접 연동하는 것 금지 (§4-1)
- confidence 임계값, 되돌릴 수 없는 행동 키워드, 민감정보 패턴 하드코딩 금지 — `config.py`에서 관리
- 빈 elements 리스트 등 예외 상황에서 서버가 죽지 않고 항상 에러 포맷으로 응답하게 할 것
- 다른 담당자 폴더의 코드를 사전 협의 없이 수정하지 말 것
