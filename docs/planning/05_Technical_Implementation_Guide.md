# 구현방법 및 기술설계

Android 개발을 처음 접하는 팀원이 코딩 에이전트와 함께 MVP를 만들 수 있도록 단계별로 설명

UNWORK Hackathon · 2026.08

# 1. 먼저 이해해야 할 핵심

| **핵심 구조** Wake word가 화면 Off 상태에서도 음성을 감지하고, Android Accessibility API가 현재 화면의 UI 정보를 제공하면, LLM이 사용자 목표를 보고 다음 UI 요소를 선택해 AI가 직접 클릭까지 실행한다. 전송 단계를 포함해 대화방~사진 선택~전송까지 중단 없이 자동 실행하며, 전송은 카카오톡 앱 자체의 실제 전송 플로우를 그대로 완료한다. 한 번 전송된 사진은 되돌릴 수 없다. |
| --- |

[아키텍처] Wake Word → Accessibility → Context → LLM → Auto Action(전송 버튼까지 포함, 실제 전송)

# 2. Accessibility API가 실제로 주는 정보

AccessibilityService는 현재 활성 창의 UI를 AccessibilityNodeInfo 트리 형태로 읽을 수 있다. 앱이 접근성 정보를 잘 제공한다면 각 노드에서 다음과 같은 정보를 얻을 수 있다.

| **필드** | **예시** | **용도** |
| --- | --- | --- |
| text | "엄마", "앨범", "전송" | 버튼/텍스트의 화면상 라벨 |
| contentDescription | "첨부", "사진 보내기" | 아이콘처럼 글자가 없는 UI의 접근성 설명 |
| className | Button, TextView, ImageButton | UI 종류 추정 |
| clickable | true/false | AI가 실행할 후보인지 필터링 |
| boundsInScreen | [left, top, right, bottom] | performAction 실행 및 좌표 확인 |
| packageName | com.kakao.talk | 현재 앱 식별 |
| actions | click, scroll, setText 등 | 지원 가능한 상호작용 파악 |
| parent/children | UI hierarchy | 현재 화면의 구조와 문맥 파악 |

Android가 "이 버튼은 전송 기능이다"라는 비즈니스 의미를 직접 주는 것은 아니다. text/contentDescription과 주변 UI 구조를 제공하고, LLM이 언어적 의미와 사용자 목표를 연결한다.

> 사진 목록의 썸네일은 text/contentDescription이 비어 있을 수 있다. 이때 "어떤 사진인지"는 접근성 트리만으로 알 수 없으므로 §5 Phase 7 Vision fallback이 보조 경로가 된다.
> (팀 확인 필요) 실기기에서 카카오톡 앨범 화면을 덤프해 썸네일 노드에 라벨이 실리는지 확인할 것 — 결과에 따라 Vision fallback이 선택이 아니라 필수 경로가 된다.

# 3. 예시: AI는 어떻게 다음 동작을 고르고 실행하는가

사용자 목표: "엄마한테 어제 찍은 사진 보내줘."

현재 화면에서 수집한 클릭 가능한 UI가 [엄마, 김엄마, 친구모임, 검색]이라면, 서버는 이를 JSON으로 만들고 LLM에게 "다음으로 선택할 element ID와 입력값"을 반환하도록 요청한다. 반환된 targetNodeId에 대해 즉시 performAction(ACTION_CLICK) 또는 setText를 실행한다. targetNodeId의 라벨이 전송/보내기 계열이어도 자동 실행을 막지 않는다 — 카카오톡 앱의 실제 전송 버튼까지 동일하게 자동 클릭해 전송을 완료한다.

**첫 발화가 정보를 다 담고 있지 않을 때** (오히려 기본 케이스): 사용자가 "사진 좀 보내줘"라고만 말했다면, 채팅 목록 화면에서 LLM은 어떤 요소를 클릭할지 확정할 수 없다. 이때 LLM은 targetNodeId 대신 `status="ASK_USER"`와 `instruction="누구에게 보낼까요?"`를 반환한다. Android는 이 문장을 TTS로 재생하고, 사용자의 답변("엄마한테")을 STT로 받아 `goal`에 이어붙여("사진 좀 보내줘. 받는 사람은 엄마입니다.") 같은 session으로 재요청한다.

이 시나리오에서 채워야 할 슬롯은 **① 수신자(누구에게) ② 어떤 사진** 둘뿐이다. 슬롯 수가 적어 왕복은 짧지만, **후보가 여럿일 때 임의로 고르는 실수는 되돌릴 수 없다**("엄마"와 "김엄마"가 함께 보이는 위 예시가 그것이다). 후보가 둘 이상이면 반드시 되묻는다.

[에이전트 루프] 한 번에 한 단계만 판단하여 오류 전파를 줄인다. 정보가 부족하거나 후보가 여럿이면 판단을 강행하지 않고 되묻는다.

# 4. 권장 기술 스택

| **영역** | **추천** | **이유** |
| --- | --- | --- |
| Wake Word | Porcupine 등 온디바이스 엔진 | 화면 Off 상태에서도 오프라인으로 상시 감지 가능 |
| Android | Kotlin + Android Studio | AccessibilityService와 시스템 API를 가장 직접적으로 다룸 |
| UI | Jetpack Compose 또는 기본 View | 상태 안내(진행 중/완료) 등 자체 UI 구성 |
| Accessibility | AccessibilityService / AccessibilityNodeInfo + performAction | 다른 앱의 UI 구조 읽기 및 자동 조작 실행 |
| AI | Gemini `gemini-3.6-flash` (generateContent + structured output) | goal + UI elements → targetNodeId + action 판단. 아래 "확정된 모델 선택" 참고 |
| Backend | FastAPI | API 키 보호, confidence 게이트·민감정보 마스킹 등 안전 규칙 관리 |
| Vision fallback | 선택적 Screenshot + multimodal model | 사진 썸네일 등 접근성 라벨이 없는 UI 보완 |
| Speech | Android STT/TTS 또는 클라우드 | 목표 입력 및 상태 안내 |

## 확정된 모델 선택 (실측 기준)

| **항목** | **값** | **비고** |
| --- | --- | --- |
| 모델 | `gemini-3.6-flash` | 실제 동작이 확인된 모델 |
| 호출 방식 | generateContent API + structured output (response_schema) | 응답을 고정 JSON 스키마로 강제 |
| thinking_level | `low` | 화면당 1콜을 짧게 유지하기 위함 |
| 콜당 지연 | 약 2.4초 (실측) | |
| 콜당 input 토큰 | 639~711 토큰 (실측) | 프롬프트 + 화면 elements 포함 |
| HTTP deadline | 10초 미만 설정 불가 | Gemini가 400으로 거부 ("Minimum allowed deadline is 10s") |

최신 `gemini-3.7-flash`는 실측에서 응답까지 29초가 걸려 사용하지 않는다. 대화형 자동 진행 루프에서는 화면마다 1콜이 들어가므로 이 지연을 감당할 수 없다.

**백엔드 구현 위치**

| **파일** | **역할** |
| --- | --- |
| `backend/services/ai_client.py` | `GeminiAIClient` — generateContent 호출, 응답 파싱 |
| `backend/services/prompt.py` | 프롬프트 템플릿 (현재 v2) |
| `backend/schemas/llm.py` | LLM이 직접 반환하는 원시 응답 스키마 |

# 5. 개발 단계 — 반드시 이 순서로

## Phase 0. Android 앱 실행 + Wake Word 등록

Android Studio 설치 후 Kotlin Empty Activity 생성.

Porcupine SDK 연동, Foreground Service 등록.

접근성 설정으로 이동하는 버튼 하나 구현.

## Phase 1. AccessibilityService 등록

Manifest에 AccessibilityService 선언.

accessibility_service_config.xml에 canRetrieveWindowContent=true 등 필요한 설정.

사용자가 시스템 설정에서 직접 서비스를 켜도록 함.

onAccessibilityEvent에서 현재 packageName과 이벤트 타입을 Logcat에 출력.

## Phase 2. UI Tree 덤프

rootInActiveWindow에서 루트 노드를 얻음.

재귀 함수로 children 탐색.

text, contentDescription, className, clickable, boundsInScreen을 출력.

카카오톡 앱을 열고 실제로 어떤 데이터가 노출되는지 확인. 채팅 목록·대화방·첨부(+)·앨범·전송 화면을 각각 덤프해 둔다.

| **1차 성공 기준** 화면이 꺼진 상태에서 wake word를 말하면 카카오톡이 실행되고, Logcat에 대화방 이름, "앨범", "전송" 등 현재 화면의 UI 정보와 좌표가 나타난다. 이 단계가 안 되면 AI를 붙이지 않는다. |
| --- |

## Phase 3. AI 없이 자동 클릭 검증

text/contentDescription이 특정 문자열인 노드를 찾는다. 예: "앨범".

getBoundsInScreen으로 Rect 좌표를 얻는다.

performAction(ACTION_CLICK)으로 실제 클릭을 즉시 실행한다.

> 이 단계 검증은 **본인 계정 / 본인과의 채팅방**에서 한다. 잘못 눌린 전송은 취소할 수 없으므로 다른 사람 대화방에서 실험하지 않는다.

| **2차 성공 기준** 목표 문자열을 지정하기만 해도 카카오톡 화면의 실제 버튼이 자동으로 눌린다. |
| --- |

## Phase 4. LLM 연결

모든 UI를 보내지 말고 클릭 가능한/의미 있는 노드만 추린다.

각 노드에 session 내 임시 ID를 부여한다.

사용자 goal + 현재 앱 + UI JSON을 backend로 전송.

LLM 응답은 targetNodeId, action(click/setText), value, confidence, status 정도의 고정 JSON 형식으로 제한.

전송/보내기 계열 라벨이어도 자동 실행 대상에서 제외하지 않는다 — 대화방·사진 선택과 동일하게 자동 클릭 대상이며, 전송 화면의 요소도 동일한 루프로 계속 진행된다.

**status가 ASK_USER면 클릭하지 않는다.** targetNodeId는 null이고 instruction에 사용자에게 물어볼 질문이 담겨 있다. Android는 이 문장을 TTS로 재생하고 STT 답변을 받아 goal에 이어붙여 재요청하는 로직을 이 단계에서 함께 구현한다.

## Phase 5. 반복 루프

TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOW_CONTENT_CHANGED 등 이벤트 발생 감지.

Debounce를 적용해 화면이 안정된 뒤 새 UI Tree를 수집.

같은 goal을 유지한 채 LLM에 새 상태를 전달하고 자동 실행을 반복 — 전송 버튼까지 멈추지 않는다. status가 ASK_USER인 동안은 되묻기 왕복(Phase 4)을 반복하고, 정보가 채워지면 다시 CONTINUE로 자동 클릭이 재개된다.

status가 DONE이 되면(카카오톡 대화방에 사진이 전송된 것이 확인되면) accessibility 자동 클릭 루프를 멈춘다.

## Phase 6. 실제 전송 자동 실행

카카오톡 앱 자체의 첨부(+) → 앨범 → 사진 선택 → 전송 버튼까지 AI가 동일한 자동 클릭 루프로 실행한다. 우리 앱이 별도의 전송 화면을 만들거나 메시지 API를 직접 호출하지 않는다 — 카카오톡이 이미 갖고 있는 전송 플로우를 그대로 자동 조작할 뿐이다. 최종적으로 대화방에 사진이 올라간 상태가 곧 종료 상태다.

| **구분** | **In-Scope (구현)** | **Out-of-Scope (미구현)** |
| --- | --- | --- |
| 수신자 선택 | 카카오톡 채팅 목록/검색 화면을 accessibility로 자동 클릭 | 우리 시스템이 연락처를 별도로 저장·관리하는 것 |
| 사진 선택 | 카카오톡이 띄우는 앨범 화면에서 자동 선택 (라벨이 없으면 Phase 7) | 우리 앱이 갤러리를 직접 읽어 사진 파일을 다루는 것 |
| 전송 | 카카오톡의 실제 전송 버튼을 accessibility로 자동 클릭 | 우리 backend/Android가 메시지 전송 API를 직접 호출하는 별도 연동 |

Android 클라이언트는 별도의 Mock 저장소나 로컬 상태만으로 전송을 흉내내지 않는다 — accessibility 자동 클릭이 실제 카카오톡 전송 플로우를 끝까지 진행시킨다. **전송은 취소할 수 없다.** 반복 테스트로 같은 사진이 여러 번 나가지 않도록 세션 상태(이미 전송 완료된 목표인지)를 반드시 확인하고, 리허설은 본인과의 채팅방에서 한다.

## Phase 7. Vision fallback

아이콘이 text=null, contentDescription=null로 노출되거나 커스텀 UI(예: 사진 목록 썸네일이 라벨 없는 이미지)라 Accessibility Tree만으로 의미를 알 수 없을 때 화면 캡처/비전 모델을 보조적으로 사용한다. "어제 찍은 사진" 같은 지시는 썸네일에 라벨이 없으면 이 경로 없이는 확정할 수 없다.

# 6. 서버/LLM 프롬프트 설계

프롬프트는 "전체 작업을 계획해라"보다 "현재 화면에서 다음으로 실행할 요소 하나만 고르라"가 안전하다.

| **입력** | **내용** |
| --- | --- |
| goal | 사용자가 최종적으로 하고 싶은 일 |
| app | 현재 package/app name |
| elements | id, text, contentDescription, class, clickable, bounds |
| history | 최근 진행 스텝 요약 (되묻기로 채워진 슬롯도 goal에 누적되어 여기 반영됨) |
| safety | 민감정보(비밀번호/주민번호/계좌번호 등)는 LLM 전송 전 서버단에서 마스킹. confidence가 임계값 미만이면 서버가 status를 ASK_USER로 강제 override |

| **출력 필드** | **예시** |
| --- | --- |
| targetNodeId | 17 (ASK_USER일 때는 보통 null) |
| action | click / setText |
| value | "부산" (setText인 경우) |
| confidence | 0.91 |
| status | CONTINUE / DONE / ASK_USER / UNSUPPORTED |

**프롬프트 지시사항에 명시할 것**

1. "현재 화면 요소와 지금까지의 goal만으로 다음에 클릭/입력할 요소를 확정할 수 없다면(예: 수신자나 어떤 사진인지가 아직 말해지지 않음), targetNodeId를 추측하지 말고 status="ASK_USER"와 함께 instruction에 사용자에게 물어볼 질문 한 문장을 담아 반환하라."
2. "goal이 가리키는 대상과 비슷한 항목이 화면에 둘 이상이면(예: 이름이 비슷한 대화방이 여러 개) 그중 하나를 임의로 고르지 말고 후보를 짚어서 되물어라."

이 지시가 없으면 LLM이 불확실한 슬롯을 임의로 채워 **잘못된 사람에게 사진을 보내고, 그건 되돌릴 수 없다.** 실제 프롬프트는 `backend/services/prompt.py`(현재 v2)에 있으며, 프롬프트를 바꾸면 `PROMPT_VERSION`을 올려 로그로 회귀를 추적한다.

# 7. 안전 설계

대화방·사진 선택은 물론 전송 단계까지 AI가 performAction(ACTION_CLICK)으로 중단 없이 자동 실행하는 것을 MVP 원칙으로 한다.

**이 시나리오의 핵심 위험은 "취소 불가능한 전송"이다.** 전송은 카카오톡의 실제 전송 플로우를 그대로 타므로, 잘못된 상대에게 사진이 한 번 나가면 되돌릴 방법이 없다. 예매 시나리오에서는 수수료를 내고서라도 취소라는 선택지가 있었지만 여기엔 그마저 없다 — 오판의 비용이 즉시, 그리고 영구히 발생한다는 것을 항상 전제로 개발한다. 게다가 잘못 나간 것이 사진이라면 사생활 유출이기도 하다.

따라서 되묻기가 이 시나리오의 1차 안전장치다. 수신자 후보가 둘 이상이면 임의 선택 없이 반드시 되묻고, LLM confidence가 낮으면 자동 실행 대신 진행을 멈추고 "지금 화면에서 원하는 대화방이 보이지 않아요"처럼 질문한다.

비밀번호, 주민번호, 계좌번호, 메시지 내용 등 민감 text는 서버 전송 전에 마스킹할 수 있도록 노드 필터를 둔다. 대화방 화면에는 지난 대화 내용이 노드로 올라오므로 특히 주의한다.

가능하면 전체 UI Tree를 저장하지 않고 추론 직후 폐기한다. 사진 자체(썸네일 이미지)도 마찬가지로 저장하지 않는다.

개발·리허설 단계에서는 본인 계정 / 본인과의 채팅방을 사용해 실제 상대에게 사진이 나가지 않도록 한다.

접근성 권한을 요청하기 전에 화면 정보를 왜 읽는지, 어디에 쓰는지 명확히 설명한다.

# 8. Google Play 정책 관점

AccessibilityService로 대화방·사진 선택·전송까지 자동 실행하는 것은 Play가 제한하는 "일반 앱의 자율적 계획·실행 자동화"에 해당할 소지가 있다. 시나리오가 바뀌어도 이 리스크는 낮아지지 않는다 — 접근성 목적(디지털 취약계층 지원)을 정책 심사에서 명확히 소명해야 한다.

| **정책상 유리한 제품 원칙** AI는 대화방~사진~전송까지 자동 실행하되, 우리 시스템이 메시지 전송 경로를 직접 갖지 않는다(카카오톡 앱 자체 플로우만 조작). 보안 통제를 우회하지 않는다. 필요한 화면 정보만 최소한으로 처리한다. |
| --- |

우리 시스템은 카카오톡의 실제 전송 화면을 그대로 accessibility로 자동 클릭할 뿐, 별도의 메시지 UI를 만들거나 메시징 API와 직접 연동하지 않는다는 점을 정책 심사·발표에서 명확히 설명한다. 실제 전송이 발생한다는 사실 자체를 숨기지 않는다.

# 9. 카카오톡 MVP 추천 시나리오

| **우선순위** | **시나리오** | **이유** |
| --- | --- | --- |
| 1 | "엄마한테 사진 보내줘" — 최근 사진 1장 전송 | 대표성 있고 대화방·사진·전송 흐름이 짧고 명확 |
| 2 | 수신자를 말하지 않은 발화("사진 좀 보내줘")에서 되묻기 | 슬롯필링(ASK_USER) 왕복을 그대로 보여줄 수 있음 |
| 3 | "어제 찍은 사진" 등 사진을 조건으로 지정 | 썸네일에 접근성 라벨이 없으면 Vision fallback이 필요해 난이도가 올라감 |

# 10. 코딩 에이전트에게 줄 작업 순서

"Kotlin Android 프로젝트에서 Porcupine wake word 엔진을 등록하고 화면 Off 상태에서도 상시 감지되는 Foreground Service를 구현해줘."

"AccessibilityService를 등록하고 접근성 설정으로 이동하는 버튼을 구현해줘."

"현재 rootInActiveWindow의 AccessibilityNodeInfo tree를 재귀 탐색해 text/contentDescription/className/clickable/bounds/packageName을 Logcat에 출력해줘."

"현재 UI에서 text 또는 contentDescription이 지정한 문자열인 clickable node를 찾고 performAction(ACTION_CLICK)으로 즉시 클릭을 실행해줘."

"clickable 또는 의미 있는 노드만 JSON으로 serialize하고 session-local node id를 부여해줘."

"goal + elements JSON을 backend로 보내고 targetNodeId/action/value/confidence/status JSON을 받도록 구현해줘."

"응답 status가 CONTINUE면 즉시 performAction으로 실행해줘. 전송/보내기 라벨이어도 동일하게 실행해."

"응답 status가 ASK_USER면 자동 클릭을 멈추고 instruction 문장을 TTS로 재생해줘. 이어서 STT로 사용자 답변을 받아서 goal 뒤에 이어붙인 다음, 같은 session_id로 다시 요청을 보내줘."

"민감 텍스트 마스킹과 지원 제외 package/화면 정책을 추가해줘."

"전송 단계 요소(라벨이 전송/보내기 계열)도 동일한 accessibility 자동 클릭 루프로 실행해줘 — 카카오톡의 첨부·앨범·전송 버튼까지 그대로 눌러서 전송을 완료시켜야 해. 별도의 Mock 전송 화면은 만들지 않아."

# 11. 해커톤 1차 구현 체크리스트

□ 화면 Off 상태에서 wake word 인식

□ 카카오톡 자동 실행

□ 대화방(수신자) 자동 선택 성공

□ 정보 부족한 발화("사진 좀 보내줘")에서 되묻기(ASK_USER) → TTS 질문 재생 → STT 답변 → goal 누적 재요청 왕복 성공

□ 이름이 비슷한 대화방이 여럿일 때 임의 선택하지 않고 되묻는지 확인

□ 첨부(+) → 앨범 → 사진 자동 선택 성공

□ 전송 버튼까지 자동 클릭 중단 없이 도달, 실제 전송 완료

□ 대화방에 사진이 올라간 것 확인

□ 리허설/개발용 테스트 대상 준비 (본인과의 채팅방 — 반복 테스트로 인한 오전송 방지)

□ 오류/중단 버튼

# 12. 구현 난이도 평가

| **범위** | **난이도** | **설명** |
| --- | --- | --- |
| 본인과의 채팅방에서 대화방~사진~전송 자동 진행 | 5/10 | 오전송 리스크 없이 전체 흐름 검증 가능 (개발 초기 단계용) |
| 실제 상대 대상 1개 시나리오 자동 진행(전송 포함) + LLM | 8/10 | 전송이 취소 불가이므로 오판에 따른 오전송·사진 썸네일 접근성 이슈를 모두 감당해야 함 |
| 실 카카오톡 안정화 (전송 자동 진행 포함) | 8~9/10 | 화면 전환·예외처리·정책 방어·중복 전송 방지까지 필요 |
| 모든 메신저 앱 범용 지원 | 9/10 | UI 품질·앱 업데이트·Vision fallback 필요 |

> (팀 확인 필요) 위 난이도 숫자는 이전 예매 시나리오 기준으로 매긴 값을 범위만 바꿔 이어받은 것이다. 슬롯이 6개에서 2개로 줄어든 만큼 실제 난이도는 달라질 수 있으니, Phase 2 실기기 덤프 이후 다시 매길 것.

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
UI 노드의 text, contentDescription, boundsInScreen 등 접근성 정보를 조회할 수 있음.

**[S6] Google Play AccessibilityService 정책**
https://support.google.com/googleplay/android-developer/answer/10964491?hl=ko
접근성 API 사용 시 선언·공개·동의 요구. 일반 앱의 자율적 계획·실행 자동화는 제한되며 장애 지원이 핵심인 접근성 도구는 별도 기준 적용.

**[S7] Google Play 민감 권한/API 정책**
https://support.google.com/googleplay/android-developer/answer/16558241
Accessibility API는 보안 통제 우회·기만적 UI 조작 등에 사용할 수 없고 목적에 필요한 최소 데이터만 처리해야 함.
