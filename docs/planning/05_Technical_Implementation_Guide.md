# 구현방법 및 기술설계

Android 개발을 처음 접하는 팀원이 코딩 에이전트와 함께 10시간 안에 MVP를 만들 수 있도록 단계별로 설명

UNWORK Hackathon · 2026.08

# 1. 먼저 이해해야 할 핵심

| **핵심 구조** 사용자가 목표를 말하면, LLM이 **설치된 앱 중 무엇을 열지** 먼저 정한다. 앱이 열린 뒤에는 Accessibility API가 현재 화면의 UI 정보를 주고, LLM이 **다음에 실행할 것 하나**를 골라 AI가 직접 클릭·입력한다. 화면이 바뀌면 이를 반복한다. 정보가 부족하면 되묻고, 되돌릴 수 없는 버튼(전송·결제·삭제) 앞에서는 구두 동의를 받는다. |
| --- |

[아키텍처] 발화 → LLM 앱 선택 → 앱 실행 → Accessibility → Context → LLM → Auto Action → (반복) → 구두 동의 → 완료

**절대 원칙: 특정 앱을 코드에 넣지 않는다.** 카카오톡은 데모 사례일 뿐이다. 패키지명·버튼 라벨·화면 순서가 코드나 프롬프트에 등장하면 그건 버그다.

# 2. Accessibility API가 실제로 주는 정보

AccessibilityService는 현재 활성 창의 UI를 AccessibilityNodeInfo 트리 형태로 읽을 수 있다.

| **필드** | **예시** | **용도** |
| --- | --- | --- |
| text | "검색", "전송", "친구" | 버튼/텍스트의 화면상 라벨 |
| contentDescription | "더보기", "사진 첨부" | 아이콘처럼 글자가 없는 UI의 접근성 설명 |
| className | Button, EditText, ImageButton | UI 종류 추정 |
| clickable | true/false | 클릭 후보인지 필터링 |
| **isEditable** | true/false | `ACTION_SET_TEXT` 대상인지 판별 — 검색창·메시지 입력창 찾기에 필수 |
| **isScrollable** | true/false | 목록·그리드 컨테이너. 찾는 항목이 화면 밖에 있을 때 스크롤 대상 |
| **isPassword** | true/false | 비밀번호 필드 — **text를 서버로 보내지 않는다** |
| boundsInScreen | [left, top, right, bottom] | 좌표 확인 및 화면 내 위치 판단 |
| packageName | com.kakao.talk | 현재 앱 식별 |
| parent/children | UI hierarchy | 현재 화면의 구조와 문맥 파악 |

Android가 "이 버튼은 전송 기능이다"라는 비즈니스 의미를 직접 주지는 않는다. text/contentDescription과 주변 UI 구조를 제공하고, LLM이 언어적 의미와 사용자 목표를 연결한다.

# 3. 예시: AI는 어떻게 다음 동작을 고르고 실행하는가

## 3-1. 첫 스텝 — 어떤 앱을 열 것인가

사용자 목표: "영희한테 카톡으로 방금 찍은 사진 보내줘."

아직 아무 앱도 열지 않았으므로 Android는 `app_package=null`, `elements=[]`, 그리고 `installed_apps=[{package, label}, ...]`를 보낸다. LLM이 목표와 앱 목록을 보고 `action="LAUNCH_APP"`, `value="com.kakao.talk"`를 반환한다.

**사용자가 "카톡"이라고 줄여 말해도, 아예 앱 이름을 말하지 않고 "영희한테 사진 보내줘"라고만 해도 LLM이 판단한다.** 이게 어르신의 첫 번째 장벽을 없애는 지점이다.

## 3-2. 이후 스텝 — 화면에서 무엇을 누를 것인가

현재 화면에서 수집한 UI가 [친구, 채팅, 검색, 더보기]라면, 서버는 이를 JSON으로 만들고 LLM에게 "다음으로 실행할 element id와 action"을 요청한다. 반환된 `target_node_id`에 대해 `ACTION_CLICK` 또는 `ACTION_SET_TEXT`를 즉시 실행한다.

찾는 항목이 화면에 안 보이면 아무거나 추측해서 누르지 말고 `SCROLL_FORWARD`로 더 찾는다.

## 3-3. 정보가 부족할 때 (기본 케이스)

사용자가 "카톡 보내줘"라고만 말했다면, LLM은 누구에게 보낼지 확정할 수 없다. 이때 `target_node_id` 대신 `status="ASK_USER"`와 `instruction="누구에게 보낼까요?"`를 반환한다. Android는 이 문장을 TTS로 재생하고, 답변("영희요")을 STT로 받아 `goal`에 이어붙여("카톡 보내줘. 받는 사람은 영희입니다.") 같은 session으로 재요청한다.

## 3-4. 되돌릴 수 없는 행동 앞

전송 버튼 차례가 되면 LLM은 `status="CONFIRM_REQUIRED"`, `instruction="영희님께 사진을 보낼까요?"`를 반환한다. 사용자가 "응"이라고 답하면 그 답이 `goal`에 붙어 재요청되고, LLM이 `CONTINUE` + `CLICK`으로 실제 전송을 실행한다.

[에이전트 루프] 한 번에 한 단계만 판단하여 오류 전파를 줄인다. 정보가 부족하면 되묻고, 되돌릴 수 없으면 동의를 받는다.

# 4. 권장 기술 스택

## 4-1. Android

| **영역** | **추천** | **이유** |
| --- | --- | --- |
| 언어/빌드 | Kotlin + 기존 Gradle 설정 (AGP 9.3.2, minSdk 26) | 이미 세팅되어 있다. 건드리지 말 것 |
| UI | **기존 View + XML 유지** | 우리 앱 화면은 마이크 버튼 하나뿐이다. Compose로 전환하는 건 10시간 안에서 순손실 |
| 화면 읽기 | `AccessibilityService` + `rootInActiveWindow` 재귀 탐색 | 핵심. 다른 방법 없음 |
| 자동 조작 | `performAction(ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_FORWARD)`, `performGlobalAction(GLOBAL_ACTION_BACK)` | 좌표 탭(dispatchGesture)보다 노드 기반이 훨씬 안정적 |
| **앱 실행** | `PackageManager.getLaunchIntentForPackage()` + **Manifest `<queries>`** | `<queries>` 없으면 Android 11+에서 설치 앱 목록이 비어 앱 선택이 통째로 실패한다. **1순위 함정** |
| 오버레이 | `TYPE_ACCESSIBILITY_OVERLAY` | `SYSTEM_ALERT_WINDOW` 권한 요청이 불필요 — 어르신에게 보여줄 권한 화면이 하나 줄어든다 |
| STT | `android.speech.SpeechRecognizer` | 내장, 한국어 지원, 무료, 추가 SDK 없음. Whisper는 10시간 안에서 과하다 |
| TTS | `android.speech.tts.TextToSpeech` | 내장 |
| 네트워킹 | Retrofit + OkHttp + kotlinx.serialization | API 스키마가 snake_case라 `@SerialName` 없이 그대로 매핑된다 |
| 비동기/루프 | Kotlin Coroutines + Flow | 화면 변경 디바운스에 Flow의 `debounce`가 그대로 쓰인다 |
| Wake Word | **이번 데모에서는 제외** (로드맵) | Porcupine + Foreground Service + 배터리 최적화 예외까지 붙이면 데모 실패 리스크가 가장 크다 |

## 4-2. 백엔드 / AI

| **영역** | **추천** | **이유** |
| --- | --- | --- |
| 서버 | FastAPI + Pydantic v2 | 이미 구현되어 있다 |
| LLM | **Google Gemini (`gemini-3.7-flash`)** + `google-genai` SDK | 프로바이더는 `AIClient` Protocol 뒤에 있어 키만 바꾸면 교체된다 (Claude 구현체도 있음) |
| 응답 형식 | **structured output** (`response_mime_type` + `response_schema`) | JSON 스키마를 API 차원에서 강제한다. 프롬프트로 "JSON만 반환해"라고 부탁하고 파싱 실패를 감수하지 않는다 |
| 비용/지연 | `system_instruction` 분리 + `temperature=0` + 필요시 `GEMINI_THINKING_BUDGET=0` | 시스템 프롬프트가 화면마다 반복되므로 캐싱에 유리. temperature=0이 아니면 같은 화면에서 판단이 흔들려 디버깅이 불가능해진다 |
| Vision fallback | 같은 모델에 image 블록 추가 | 별도 비전 모델이 필요 없다. 접근성 라벨이 없는 화면에서만 스크린샷을 함께 보낸다 |
| 세션 | in-memory dict + TTL | Redis 금지 (해커톤 스코프 밖) |

**키가 없어도 개발은 멈추지 않는다.** 키가 비어 있으면 `MockAIClient`로 뜬다. 키를 받은 사람은 `.env`에 넣고 `python -m backend.dev.check_llm`으로 30초 안에 연결을 확인할 수 있다.

## 4-3. Vision 인코딩 vs UI Tree — 무엇으로 화면을 읽을 것인가

| | **A. UI Tree 전용** | **B. Vision 전용** | **C. Tree 우선 + Vision fallback** |
|---|---|---|---|
| 화면 이해 | 접근성 노드의 text/contentDescription | 스크린샷 판독 | A + 필요한 화면만 B |
| 실행 방식 | `performAction` — **노드 지정** | `dispatchGesture` — **좌표 탭** | 노드 지정 |
| 실행 정확도 | **높음** (좌표 오차 개념 없음) | 낮음 (좌표 오차·스크롤 중 변위) | 높음 |
| 라벨 없는 UI | 취약 (단 position_hint로 상당 부분 커버) | 강함 | 강함 |
| 콜당 입력 토큰 | 실측 2,868자 → 대략 1~2K 토큰 | 이미지 3~5K 토큰(추정) **+ 텍스트** | A + 필요 화면만 가산 |
| 지연 | 가장 낮음 | 인코딩·전송·판독으로 증가 | A와 거의 동일 |

**결론: C를 쓴다.** 핵심은 **Vision이 Tree의 대안이 아니라 추가**라는 점이다. 안정적으로 *실행*하려면 노드 핸들이 필요하고(좌표 탭은 스크롤·애니메이션·해상도 차이에서 어긋난다), 그러려면 어차피 트리를 읽어야 한다. 따라서 Vision을 켜도 Tree 비용은 그대로 남는다 — 비용은 항상 `Tree + 이미지`다.

**B(Vision 전용)는 더 비싸고 더 느리면서 실행 정확도까지 낮다.** 접근성 서비스를 못 쓰는 환경이 아니면 고를 이유가 없다.

Vision은 규칙으로 트리거한다 — 조작 가능 노드 중 라벨 없는 비율이 임계값 이상인 화면에서만 스크린샷을 덧붙인다 (`rules.needs_vision`). 상세 비교는 [`docs/ARCHITECTURE.md`](../ARCHITECTURE.md) §5-2.

> 토큰 수치는 문자수만 실측했고 토큰 변환은 추정이다. API 키가 생기면 `client.messages.count_tokens()`로 재보고 갱신할 것.

## 4-4. 규칙 기반 최적화 — LLM에게 묻지 않아도 되는 것

화면 전환마다 1콜이므로 **작업당 콜 수가 곧 원가이자 지연**이다. 카톡 사진 전송이 대략 7~9콜이다. 백엔드 `services/rules.py`가 다음을 결정론적으로 처리한다.

| 규칙 | 효과 |
|---|---|
| 요소 필터링 (면적 0·순수 컨테이너·중복 제거) | **노드 63% 감소** (실측) |
| 페이로드 압축 (null 제거·클래스명 축약·플래그 압축) | **문자수 80% 감소** (실측) |
| `goal`에서 앱 직접 특정 | **LLM 콜 1회 완전 제거** |
| `(goal, 화면지문)` 결정 캐시 | 중복 이벤트 낭비 콜 제거 |
| 로딩 화면 감지 | 답 없는 화면에 콜 낭비 안 함 |
| 동일 화면 반복 차단 | 무한 루프 + 무한 과금 방지 |

**Android도 같은 규칙을 클라이언트에서 미리 적용하면 네트워크까지 줄어든다.** 최소한 이 둘은 전송 전에 걸러라:
- 면적 0인 노드
- 라벨(`text`/`contentDescription`)도 없고 `clickable`/`editable`/`scrollable`도 아닌 노드

**`position_hint`가 사진 그리드 문제를 상당 부분 푼다.** 그리드 셀에 `contentDescription`이 없어도 **노드 자체는 트리에 존재한다** — 익명일 뿐이다. 백엔드가 라벨 없는 clickable 노드에 읽기 순서 힌트("이름 없는 1번째 항목")를 붙이므로, LLM이 "가장 최근 사진 = 1번째"로 고를 수 있다. **라벨이 없다고 곧바로 Vision이 필요한 게 아니다.**

# 5. 개발 단계 — 반드시 이 순서로

## Phase 0. 우리 앱 화면 + 음성 입출력

Android Studio에서 기존 프로젝트를 연다. `MainActivity`에 큰 마이크 버튼 하나를 둔다.

`RECORD_AUDIO` 권한 요청, `SpeechRecognizer`로 발화를 텍스트화, `TextToSpeech`로 문장 재생.

접근성 설정으로 이동하는 버튼 하나 구현.

| **완료 기준** 버튼을 누르고 말하면 발화 텍스트가 Logcat에 뜨고, TTS로 임의 문장이 읽힌다. |
| --- |

## Phase 1. AccessibilityService 등록

Manifest에 `BIND_ACCESSIBILITY_SERVICE`를 갖는 `<service>` 선언.

`res/xml/accessibility_service_config.xml`에 `canRetrieveWindowContent=true` 등 필요한 설정.

사용자가 시스템 설정에서 직접 서비스를 켜도록 안내.

`onAccessibilityEvent`에서 현재 `packageName`과 이벤트 타입을 Logcat에 출력.

## Phase 2. UI Tree 덤프

`rootInActiveWindow`에서 루트 노드를 얻고 재귀 함수로 children 탐색.

`text`, `contentDescription`, `className`, `clickable`, `isEditable`, `isScrollable`, `isPassword`, `boundsInScreen`을 출력.

카카오톡을 열고 실제로 어떤 데이터가 노출되는지 확인한다.

| **1차 성공 기준** 카카오톡 실행 시 Logcat에 "친구", "검색", "전송" 등 현재 화면의 UI 정보와 좌표가 나타난다. 이 단계가 안 되면 AI를 붙이지 않는다. |
| --- |

> **이 덤프를 백엔드 담당자에게 즉시 전달할 것.** 프롬프트 개발이 여기에 의존한다. (기존 `dumps/s1.xml`은 안드로이드 설정 화면 덤프라 쓸 수 없다.)
>
> **특히 확인할 것: 사진 첨부 화면의 그리드.** 각 사진 셀에 contentDescription이 있는지 없는지가 데모 성패를 가른다. 없으면 Vision fallback(Phase 7) 또는 텍스트 메시지 백업 시나리오로 전환해야 한다.

## Phase 3. AI 없이 자동 실행 검증

`text` 또는 `contentDescription`이 특정 문자열인 노드를 찾아 `ACTION_CLICK`으로 클릭한다.

`isEditable`인 노드를 찾아 `ACTION_SET_TEXT`로 문자열을 입력한다.

| **2차 성공 기준** 목표 문자열을 지정하기만 해도 카카오톡의 실제 버튼이 눌리고, 검색창에 글자가 들어간다. |
| --- |

## Phase 4. 앱 실행

Manifest에 `<queries>` 선언 (없으면 다음 단계가 통째로 실패한다).

`PackageManager`로 설치된 앱의 패키지명·라벨 목록 수집.

`getLaunchIntentForPackage(package)`로 앱 실행.

| **3차 성공 기준** 패키지명을 넘겨주면 그 앱이 실행되고, 설치 앱 목록이 비어있지 않다. |
| --- |

## Phase 5. 백엔드 연결

모든 UI를 보내지 말고 클릭 가능한/입력 가능한/의미 있는 노드만 추린다. 각 노드에 session 내 임시 id를 부여한다.

`DecideRequest`(`CLAUDE.md` §5)를 전송하고 응답의 `action`으로 분기해 실행한다.

`status`가 `ASK_USER` 또는 `CONFIRM_REQUIRED`면 **자동 실행 루프를 멈추고** `instruction`을 TTS로 재생 → STT로 답변 수신 → `goal`에 이어붙여 재요청. 이 왕복 로직을 이 단계에서 함께 구현한다.

첫 요청은 `app_package=null`, `elements=[]`, `installed_apps=[...]`로 보내 `LAUNCH_APP`을 받는다.

## Phase 6. 반복 루프

`TYPE_WINDOW_STATE_CHANGED`/`TYPE_WINDOW_CONTENT_CHANGED` 이벤트 감지.

**Debounce 필수** — 화면이 안정된 뒤(예: 500ms 무이벤트) 새 UI Tree를 수집한다. 안 하면 전환 중인 화면을 읽어 엉뚱한 판단을 한다.

같은 `session_id`와 누적된 `goal`을 유지한 채 새 상태를 전달하고 자동 실행을 반복한다.

`status`가 `DONE`이면 루프를 멈추고 `instruction`을 TTS로 읽는다.

**같은 화면에서 같은 action이 반복되면 루프를 강제 종료**하는 안전장치를 둔다 (예: 동일 응답 3회 시 중단).

## Phase 7. Vision fallback (필요시)

아이콘이 `text=null, contentDescription=null`로 노출되거나 사진 그리드처럼 커스텀 렌더링이라 Accessibility Tree만으로 의미를 알 수 없을 때만, 화면 캡처를 image 블록으로 함께 보낸다.

# 6. 10시간 타임박스 (3인 병렬)

| **시간** | **멤버 A (음성/UX/네트워크)** | **멤버 B (백엔드/AI)** | **멤버 C (접근성/자동조작)** |
| --- | --- | --- | --- |
| 0~2h | Phase 0 — 마이크 버튼, STT/TTS, 권한 안내 화면 | LLM 연동 (`ClaudeAIClient`) + 시스템 프롬프트 초안 | Phase 1~2 — AccessibilityService 등록, UI Tree 덤프 |
| 2~3h | — | — | **덤프를 B에게 전달** (프롬프트 개발의 전제) |
| 2~5h | Retrofit 연동, `Types.kt` 작성 (스키마 1:1) | 덤프 기반 프롬프트 튜닝, `/decide` 실측 | Phase 3~4 — 자동 클릭/입력 검증, `<queries>` + 앱 실행 |
| 5~7h | TTS 질문 재생 + STT 답변 → `goal` 누적 왕복 | 되묻기·동의 프롬프트 검증, confidence 튜닝 | Phase 5 — 노드 필터링 + 요청 전송 + action 분기 실행 |
| 7~9h | 오버레이 진행 상황 표시 | 실측 기반 최종 튜닝 | Phase 6 — 디바운스 루프, 무한루프 방지 |
| 9~10h | **전원: 리허설 3회 + 백업 시나리오 확인 + 발표 준비** | | |

**의존성 흐름**: C의 UI 덤프(2h) → B의 프롬프트 개발 / A의 통신 모듈(5h) → C의 자동화 결합.
백엔드 Mock(`MockAIClient`)은 이미 동작하므로 A와 C는 B를 기다리지 않고 0h부터 통신 테스트를 시작할 수 있다.

# 6-1. 가장 빠르게 가는 법 — 무엇을 자르고 무엇을 먼저 할 것인가

## 이미 끝나 있는 것 (다시 만들지 말 것)

백엔드는 동작한다. `pytest backend/tests` 34개 통과. Mock 클라이언트가 붙어 있어 **API 키 없이도 `/api/v1/decide`가 정상 응답**한다. A와 C는 B를 기다릴 필요가 전혀 없다.

```bash
uvicorn backend.main:app --reload --port 8000
```

**개발용 하네스: http://localhost:8000/dev**

가상 폰 화면을 실제 `/decide`에 흘려보내며 루프를 눈으로 본다. 어떤 스텝이 규칙으로 처리되고 어떤 스텝이 LLM을 호출했는지, `ASK_USER`·`CONFIRM_REQUIRED` 게이트가 언제 뜨는지 그대로 보인다.

- **멤버 B**: 실기기 `uiautomator dump` XML을 붙여넣으면 그 화면으로 바로 판단시켜 볼 수 있다. 프롬프트 튜닝을 Android 완성 전에 시작할 수 있다.
- **멤버 A·C**: 응답 헤더 `X-Rule-Hit` / `X-LLM-Called` / `X-Elements`로 서버가 무엇을 했는지 확인한다.
- **데모 대비책**: Android가 못 끝나도 이 화면으로 에이전트 루프 자체는 시연할 수 있다.

## 크리티컬 패스는 단 하나

**멤버 C의 Phase 1~2 (AccessibilityService 등록 + UI Tree 덤프)**. 이게 늦어지면 B의 프롬프트 튜닝도, C의 자동화도 전부 멈춘다. **다른 무엇보다 먼저 끝낸다.** 2시간 안에 안 되면 그 자체가 위험 신호다.

## 잘라낸 것 (데모에 넣지 말 것)

| 자른 것 | 이유 |
|---|---|
| Wake word / 화면 Off 상시 감지 | Foreground Service + 배터리 최적화 예외까지 필요. 데모 실패 리스크 1위 |
| Compose 전환 | 화면이 마이크 버튼 하나뿐이다. 순손실 |
| 실제 결제 | 전송이 같은 코드 경로다. 돈 쓸 이유가 없다 |
| Vision fallback | Phase 7. `position_hint`로 먼저 시도하고, 안 되면 그때 |
| 예쁜 오버레이 | 진행 상황은 TTS로 충분하다. 시간 남으면 |

## 막히면 즉시 갈아탈 것

| 막히는 지점 | 대체 |
|---|---|
| 사진 그리드에 라벨이 없다 | ① `position_hint`로 "1번째 항목" 시도 → ② 안 되면 **텍스트 메시지 전송 시나리오**로 전환 |
| 앱 목록이 비어 있다 | `<queries>` 선언 누락. 이거 말고 다른 원인은 거의 없다 |
| 클릭이 안 먹는다 | 해당 노드가 아니라 **부모 중 `clickable=true`인 노드**에 `performAction`을 걸어야 하는 경우가 많다 |
| 화면 전환 중 엉뚱한 판단 | 디바운스를 500ms → 800ms로 올린다 |
| LLM이 느리다 | `ANTHROPIC_EFFORT=low` 확인. 그래도 느리면 프롬프트 캐시가 안 먹고 있는 것 |

# 7. 서버/LLM 프롬프트 설계

프롬프트는 "전체 작업을 계획해라"보다 **"현재 화면에서 다음으로 실행할 것 하나만 고르라"**가 안전하다. 실제 프롬프트는 `backend/prompts/decide_system.md`에 있다.

| **입력** | **내용** |
| --- | --- |
| goal | 사용자가 최종적으로 하고 싶은 일 (되묻기·동의 답변이 누적됨) |
| app_package | 현재 앱. `null`이면 앱 선택 단계 |
| installed_apps | `app_package`가 `null`일 때만 — 설치된 앱의 패키지명과 이름 |
| elements | id, text, content_description, class_name, clickable, editable, scrollable, bounds |
| history | 최근 진행 스텝 요약 |

| **출력 필드** | **예시** |
| --- | --- |
| action | CLICK / SET_TEXT / LAUNCH_APP / SCROLL_FORWARD / SCROLL_BACKWARD / BACK / NONE |
| target_node_id | 17 (LAUNCH_APP·ASK_USER·DONE일 때는 null. CONFIRM_REQUIRED일 때는 누르려는 버튼의 id) |
| value | "영희" (SET_TEXT) / "com.kakao.talk" (LAUNCH_APP) |
| instruction | TTS로 읽어줄 문장 |
| confidence | 0.91 |
| status | CONTINUE / DONE / ASK_USER / CONFIRM_REQUIRED / UNSUPPORTED |

**프롬프트에 반드시 명시할 것**:

1. "현재 화면 요소와 지금까지의 `goal`만으로 다음 행동을 확정할 수 없다면, `target_node_id`를 추측하지 말고 `status="ASK_USER"`와 함께 질문 한 문장을 반환하라." — 이 지시가 없으면 LLM이 불확실한 슬롯을 임의로 채워 엉뚱한 상대에게 전송한다.
2. "결제·송금·전송·삭제처럼 되돌릴 수 없는 버튼을 누를 차례라면, 누르기 전에 `status="CONFIRM_REQUIRED"`로 응답하고 `instruction`에 상대방·금액 등 구체적 정보를 포함한 확인 문장을 담아라."
3. "`elements`에 없는 id를 지어내지 마라."
4. "특정 앱의 화면 순서를 외워서 가정하지 마라. 매번 지금 주어진 `elements`만 보고 판단하라."

# 8. 안전 설계

되돌릴 수 있는 행동(검색, 이동, 입력, 스크롤)은 AI가 중단 없이 자동 실행한다. **되돌릴 수 없는 행동(전송·결제·송금·삭제)만 구두 동의 뒤에 실행한다.** 결제만 특별 취급하는 별도 코드 경로를 두지 않는다 — 같은 게이트를 탄다.

**안전을 프롬프트 하나에 걸지 않는다.** LLM이 확인 단계를 빠뜨려도 서버가 `IRREVERSIBLE_KEYWORDS`로 독립 검사해 `CONFIRM_REQUIRED`로 강제 override한다 (2단 방어).

LLM confidence가 임계값 미만이면 자동 실행 대신 진행을 멈추고 되묻는다.

비밀번호 필드(`isPassword`)는 클라이언트에서 text를 아예 보내지 않고, 서버에서도 한 번 더 제거한다. 주민번호·카드번호·계좌번호 패턴은 LLM 전송 전 서버에서 마스킹한다.

UI Tree를 저장하지 않고 추론 직후 폐기한다. 로그에 `text`/`content_description` 원문을 남기지 않는다.

접근성 권한을 요청하기 전에 화면 정보를 왜 읽는지, 어디에 쓰는지 명확히 설명한다.

**메시지 앱을 조작한다는 것은 사적인 대화가 화면에 노출된다는 뜻이다.** 예매 앱보다 프라이버시 민감도가 높다는 점을 항상 전제로 개발한다.

# 9. Google Play 정책 관점

AccessibilityService로 여러 단계를 자율 실행하는 것은 Play가 제한하는 "일반 앱의 자율적 계획·실행 자동화"에 해당할 소지가 있다. 접근성 목적(디지털 취약계층 지원)을 정책 심사에서 명확히 소명해야 한다.

| **정책상 유리한 제품 원칙** 되돌릴 수 없는 행동은 사용자 동의 없이 실행하지 않는다. 보안 통제를 우회하지 않는다. 목적에 필요한 최소 화면 정보만 처리하고 저장하지 않는다. 우리 시스템이 별도의 PG 연동이나 결제망 접근을 직접 갖지 않는다. |
| --- |

# 10. 데모 시나리오와 백업 플랜

| **우선순위** | **시나리오** | **비고** |
| --- | --- | --- |
| 1 | 카카오톡 사진 전송 | 앱 선택·텍스트 입력·목록 선택·구두 동의가 한 흐름에 다 들어간다 |
| 2 (백업) | 카카오톡 텍스트 메시지 전송 | 사진 그리드(접근성 라벨 위험 구간)가 빠져 훨씬 안전. **반드시 함께 준비할 것** |
| 3 (보너스) | 다른 앱에서 같은 코드로 한 번 더 | "앱을 하드코딩하지 않았다"는 주장의 가장 강력한 증명 |

**데모에서 실제 결제는 하지 않는다.** 전송 동의가 결제 동의와 코드상 같은 경로이므로 시연 손실이 없다. 발표에서 "결제도 이것과 같은 코드로 동작합니다"라고 말한다.

**리허설 시 유의**: 실제로 메시지가 전송된다. 테스트용 대화방(자기 자신과의 채팅 등)을 미리 만들어 두고 거기로 보낼 것.

# 11. 코딩 에이전트에게 줄 작업 순서

"Kotlin Android 프로젝트에서 큰 마이크 버튼 하나를 둔 화면을 만들고, RECORD_AUDIO 권한 요청과 SpeechRecognizer 한국어 STT, TextToSpeech 재생을 구현해줘."

"AccessibilityService를 등록하고(Manifest service + accessibility_service_config.xml, canRetrieveWindowContent=true) 접근성 설정으로 이동하는 버튼을 구현해줘."

"현재 rootInActiveWindow의 AccessibilityNodeInfo tree를 재귀 탐색해 text/contentDescription/className/clickable/isEditable/isScrollable/isPassword/boundsInScreen/packageName을 Logcat에 출력해줘."

"현재 UI에서 text 또는 contentDescription이 지정한 문자열인 clickable node를 찾아 performAction(ACTION_CLICK)으로 클릭하고, isEditable node에 ACTION_SET_TEXT로 문자열을 입력하는 함수를 만들어줘."

"AndroidManifest에 `<queries>`를 선언하고, PackageManager로 설치된 앱의 패키지명과 라벨 목록을 수집한 뒤 getLaunchIntentForPackage로 지정한 앱을 실행하는 함수를 만들어줘."

"Retrofit + kotlinx.serialization으로 POST /api/v1/decide를 호출하는 클라이언트와, CLAUDE.md §5 스키마와 필드명이 1:1로 일치하는 model/Types.kt를 만들어줘. 필드명은 snake_case 그대로 쓰고 camelCase로 바꾸지 마."

"응답의 action에 따라 CLICK/SET_TEXT/LAUNCH_APP/SCROLL_FORWARD/BACK을 실행하고, status가 ASK_USER나 CONFIRM_REQUIRED면 루프를 멈추고 instruction을 TTS로 재생한 뒤 STT 답변을 goal에 이어붙여 재요청하는 로직을 구현해줘."

"TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOW_CONTENT_CHANGED를 Flow로 받아 500ms debounce 후 UI Tree를 다시 수집하고 /decide를 재호출하는 루프를 만들어줘. status가 DONE이면 종료하고, 같은 응답이 3회 반복되면 강제 중단해줘."

# 참고 자료

**[S1] AI 스마트폰 도우미 - Google Play**
https://play.google.com/store/apps/details?id=ai.fluiz.ditestbed_agent
고령자 대상 접근성 앱으로, 음성 요청 후 Android Accessibility Service를 이용해 화면을 읽고 버튼 클릭·텍스트 입력·화면 이동을 수행한다고 설명.

**[S2] Gemini Live - Android Help**
https://support.google.com/gemini/answer/15274899
Android에서 화면 공유를 켜고 다른 앱을 사용하면서 Live 대화를 계속할 수 있음. 화면에 대한 실시간 도움을 제공.

**[S3] 손주도움 - Google Play**
https://play.google.com/store/apps/details?id=com.guideops.app
스마트폰 화면·키오스크·약봉투 등을 카메라로 비추면 다음 버튼과 사용법을 음성 안내.

**[S4] Android AccessibilityService API**
https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
접근성 서비스는 UI 이벤트를 받고 활성 창의 UI 트리를 조회할 수 있으며 접근성 오버레이를 그릴 수 있음.

**[S5] Android AccessibilityNodeInfo API**
https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
UI 노드의 text, contentDescription, boundsInScreen, isEditable, isPassword 등 접근성 정보를 조회하고 ACTION_CLICK·ACTION_SET_TEXT 등을 수행할 수 있음.

**[S6] Google Play AccessibilityService 정책**
https://support.google.com/googleplay/android-developer/answer/10964491?hl=ko
접근성 API 사용 시 선언·공개·동의 요구. 일반 앱의 자율적 계획·실행 자동화는 제한되며 장애 지원이 핵심인 접근성 도구는 별도 기준 적용.

**[S7] Google Play 민감 권한/API 정책**
https://support.google.com/googleplay/android-developer/answer/16558241
Accessibility API는 보안 통제 우회·기만적 UI 조작 등에 사용할 수 없고 목적에 필요한 최소 데이터만 처리해야 함.

**[S8] Android 패키지 가시성 (`<queries>`)**
https://developer.android.com/training/package-visibility
Android 11(API 30) 이상에서는 Manifest에 `<queries>`를 선언하지 않으면 다른 앱의 설치 여부를 조회할 수 없음. AI가 설치된 앱 중에서 대상 앱을 고르는 구조상 필수 선언.

**[S9] 디지털정보격차 실태조사 (과학기술정보통신부·한국지능정보사회진흥원)**
고령층의 디지털 접근·역량·활용 수준을 매년 조사하는 국가승인통계. 발표에서 수치를 인용할 경우 최신 연도 보고서 원문을 직접 확인해 인용할 것.
