# 구현방법 및 기술설계

Android 개발을 처음 접하는 팀원이 코딩 에이전트와 함께 MVP를 만들 수 있도록 단계별로 설명

UNWORK Hackathon · 2026.08

# 1. 먼저 이해해야 할 핵심

| **핵심 구조** Wake word가 화면 Off 상태에서도 음성을 감지하고, Android Accessibility API가 현재 화면의 UI 정보를 제공하면, LLM이 사용자 목표를 보고 다음 UI 요소를 선택해 AI가 직접 클릭까지 실행한다. 결제·송금 등 금전 실행 action만 서버의 하드코딩된 규칙으로 자동 실행을 막고 사용자 확인을 요구한다. |
| --- |

[아키텍처] Wake Word → Accessibility → Context → LLM → Auto Action → (결제 직전) Payment Gate → User

# 2. Accessibility API가 실제로 주는 정보

AccessibilityService는 현재 활성 창의 UI를 AccessibilityNodeInfo 트리 형태로 읽을 수 있다. 앱이 접근성 정보를 잘 제공한다면 각 노드에서 다음과 같은 정보를 얻을 수 있다.

| **필드** | **예시** | **용도** |
| --- | --- | --- |
| text | "조회", "좌석선택", "결제하기" | 버튼/텍스트의 화면상 라벨 |
| contentDescription | "더보기", "검색" | 아이콘처럼 글자가 없는 UI의 접근성 설명 |
| className | Button, TextView, ImageButton | UI 종류 추정 |
| clickable | true/false | AI가 실행할 후보인지 필터링 |
| boundsInScreen | [left, top, right, bottom] | performAction 실행 및 좌표 확인 |
| packageName | com.korail.talk | 현재 앱 식별 |
| actions | click, scroll, setText 등 | 지원 가능한 상호작용 파악 |
| parent/children | UI hierarchy | 현재 화면의 구조와 문맥 파악 |

Android가 "이 버튼은 결제 기능이다"라는 비즈니스 의미를 직접 주는 것은 아니다. text/contentDescription과 주변 UI 구조를 제공하고, LLM이 언어적 의미와 사용자 목표를 연결한다.

# 3. 예시: AI는 어떻게 다음 동작을 고르고 실행하는가

사용자 목표: "내일 아침 서울에서 부산 가는 KTX 예매해줘."

현재 화면에서 수집한 클릭 가능한 UI가 [출발역, 도착역, 날짜, 조회]라면, 서버는 이를 JSON으로 만들고 LLM에게 "다음으로 선택할 element ID와 입력값"을 반환하도록 요청한다. 반환된 targetNodeId에 대해 즉시 performAction(ACTION_CLICK) 또는 setText를 실행한다. 단, targetNodeId의 라벨이 결제/구매/확정 계열로 분류되면 서버 단에서 강제로 실행을 차단하고 결제 대기 화면으로 전환한다.

[에이전트 루프] 한 번에 한 단계만 판단하여 오류 전파를 줄인다.

# 4. 권장 기술 스택

| **영역** | **추천** | **이유** |
| --- | --- | --- |
| Wake Word | Porcupine 등 온디바이스 엔진 | 화면 Off 상태에서도 오프라인으로 상시 감지 가능 |
| Android | Kotlin + Android Studio | AccessibilityService와 시스템 API를 가장 직접적으로 다룸 |
| UI | Jetpack Compose 또는 기본 View | 결제 대기 확인 화면 등 자체 UI 구성 |
| Accessibility | AccessibilityService / AccessibilityNodeInfo + performAction | 다른 앱의 UI 구조 읽기 및 자동 조작 실행 |
| AI | LLM API | goal + UI elements → targetNodeId + action + 결제여부 판단 |
| Backend | 간단한 FastAPI/Node 서버 | API 키 보호, 결제 관련 action 강제 차단 규칙 관리 |
| Vision fallback | 선택적 Screenshot + multimodal model | 좌석맵 등 접근성 라벨이 없는 UI 보완 |
| Speech | Android STT/TTS 또는 클라우드 | 목표 입력 및 상태 안내 |

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

코레일+ 앱을 열고 실제로 어떤 데이터가 노출되는지 확인.

| **1차 성공 기준** 화면이 꺼진 상태에서 wake word를 말하면 코레일+가 실행되고, Logcat에 "출발역", "도착역", "조회" 등 현재 화면의 UI 정보와 좌표가 나타난다. 이 단계가 안 되면 AI를 붙이지 않는다. |
| --- |

## Phase 3. AI 없이 자동 클릭 검증

text/contentDescription이 특정 문자열인 노드를 찾는다. 예: "조회".

getBoundsInScreen으로 Rect 좌표를 얻는다.

performAction(ACTION_CLICK)으로 실제 클릭을 즉시 실행한다.

| **2차 성공 기준** 목표 문자열을 지정하기만 해도 코레일+ 화면의 실제 버튼이 자동으로 눌린다. |
| --- |

## Phase 4. LLM 연결

모든 UI를 보내지 말고 클릭 가능한/의미 있는 노드만 추린다.

각 노드에 session 내 임시 ID를 부여한다.

사용자 goal + 현재 앱 + UI JSON을 backend로 전송.

LLM 응답은 targetNodeId, action(click/setText), value, confidence, status 정도의 고정 JSON 형식으로 제한.

**결제 대기 게이트**: 서버는 targetNodeId의 라벨이 결제/구매/확정 계열 키워드와 매치되면 LLM 판단과 무관하게 status를 PAYMENT_GATE로 강제 전환하고 자동 실행을 중단한다.

## Phase 5. 반복 루프

TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOW_CONTENT_CHANGED 등 이벤트 발생 감지.

Debounce를 적용해 화면이 안정된 뒤 새 UI Tree를 수집.

같은 goal을 유지한 채 LLM에 새 상태를 전달하고 자동 실행을 반복.

status가 PAYMENT_GATE가 되면 루프를 멈추고 결제 대기 확인 화면으로 전환한다.

## Phase 6. 결제 대기 확인 화면

최종 금액, 경로, 시간을 명확히 표시.

사용자가 별도 확인 탭을 해야만 실제 결제 화면으로 진행.

AI는 이 단계의 어떤 버튼도 대신 누르지 않는다.

## Phase 7. Vision fallback

아이콘이 text=null, contentDescription=null로 노출되거나 커스텀 UI(예: 좌석맵이 canvas/이미지 기반)라 Accessibility Tree만으로 의미를 알 수 없을 때만 화면 캡처/비전 모델을 보조적으로 사용한다.

# 6. 서버/LLM 프롬프트 설계

프롬프트는 "전체 작업을 계획해라"보다 "현재 화면에서 다음으로 실행할 요소 하나만 고르라"가 안전하다.

| **입력** | **내용** |
| --- | --- |
| goal | 사용자가 최종적으로 하고 싶은 일 |
| app | 현재 package/app name |
| elements | id, text, contentDescription, class, clickable, bounds |
| history | 직전 2~3단계의 실행 결과만 간단히 |
| safety | 결제/송금/삭제/권한 변경 키워드는 서버 규칙으로 항상 PAYMENT_GATE/STOP 처리하며, LLM이 이 규칙을 우회할 수 없음 |

| **출력 필드** | **예시** |
| --- | --- |
| targetNodeId | 17 |
| action | click / setText |
| value | "부산" (setText인 경우) |
| confidence | 0.91 |
| status | CONTINUE / PAYMENT_GATE / DONE / ASK_USER / UNSUPPORTED |

# 7. 안전 설계

목적지·시간·좌석 선택 단계까지는 AI가 performAction(ACTION_CLICK)으로 자동 실행하는 것을 MVP 원칙으로 한다.

결제, 송금, 삭제, 인증, 권한 변경 등 금전·민감 action은 LLM 판단과 무관하게 서버 규칙으로 항상 차단하고 사용자 확인을 요구한다.

LLM confidence가 낮으면 자동 실행 대신 진행을 멈추고 "지금 화면에서 원하는 메뉴가 보이지 않아요"처럼 질문한다.

비밀번호, 주민번호, 계좌번호, 메시지 내용 등 민감 text는 서버 전송 전에 마스킹할 수 있도록 노드 필터를 둔다.

가능하면 전체 UI Tree를 저장하지 않고 추론 직후 폐기한다.

실 서버 대상 반복 호출은 매크로 탐지 대상이 될 수 있으므로, 개발·리허설 단계에서는 자체 제작 목업을 우선 사용하고 실 서버 호출 빈도를 최소화한다.

접근성 권한을 요청하기 전에 화면 정보를 왜 읽는지, 어디에 쓰는지 명확히 설명한다.

# 8. Google Play 정책 관점

AccessibilityService로 목적지·시간·좌석까지 자동 실행하는 것은 Play가 제한하는 "일반 앱의 자율적 계획·실행 자동화"에 해당할 소지가 있다. 결제 등 금전 action에는 반드시 사용자 확인을 두고, 접근성 목적(디지털 취약계층 지원)을 정책 심사에서 명확히 소명해야 한다.

| **정책상 유리한 제품 원칙** AI는 목적지~좌석까지만 자동 실행한다. 결제 실행은 항상 사용자가 한다. 보안 통제를 우회하지 않는다. 필요한 화면 정보만 최소한으로 처리한다. |
| --- |

# 9. 코레일+ MVP 추천 시나리오

| **우선순위** | **시나리오** | **이유** |
| --- | --- | --- |
| 1 | 서울→부산 KTX 예매 | 대표성 있고 목적지·시간·좌석 흐름이 명확 |
| 2 | 반복 이용 노선 예매 | 선호 좌석 학습 데모 가능 |
| 3 | 취소표 실시간 확보 | 매크로 탐지·법적 리스크가 가장 크므로 해커톤 MVP에서 제외 추천 |

# 10. 코딩 에이전트에게 줄 작업 순서

"Kotlin Android 프로젝트에서 Porcupine wake word 엔진을 등록하고 화면 Off 상태에서도 상시 감지되는 Foreground Service를 구현해줘."

"AccessibilityService를 등록하고 접근성 설정으로 이동하는 버튼을 구현해줘."

"현재 rootInActiveWindow의 AccessibilityNodeInfo tree를 재귀 탐색해 text/contentDescription/className/clickable/bounds/packageName을 Logcat에 출력해줘."

"현재 UI에서 text 또는 contentDescription이 지정한 문자열인 clickable node를 찾고 performAction(ACTION_CLICK)으로 즉시 클릭을 실행해줘."

"clickable 또는 의미 있는 노드만 JSON으로 serialize하고 session-local node id를 부여해줘."

"goal + elements JSON을 backend로 보내고 targetNodeId/action/value/confidence/status JSON을 받도록 구현해줘."

"응답 status가 CONTINUE면 즉시 performAction으로 실행하고, PAYMENT_GATE면 결제 대기 확인 화면으로 전환해줘."

"결제/송금/삭제 관련 라벨은 LLM 응답과 무관하게 서버에서 강제로 PAYMENT_GATE 처리하는 규칙을 추가해줘."

"민감 텍스트 마스킹과 지원 제외 package/화면 정책을 추가해줘."

# 11. 해커톤 1차 구현 체크리스트

□ 화면 Off 상태에서 wake word 인식

□ 코레일+ 자동 실행

□ 목적지/날짜 자동 입력 성공

□ 열차/좌석 자동 선택 성공

□ 결제 대기 확인 화면 도달

□ 결제 버튼 자동 클릭 없음 확인

□ 매크로 탐지 회피용 목업 환경 준비

□ 오류/취소 버튼

# 12. 구현 난이도 평가

| **범위** | **난이도** | **설명** |
| --- | --- | --- |
| 목업 환경에서 목적지~좌석 자동 진행 | 5/10 | 실 서버 리스크 없이 전체 흐름 검증 가능 |
| 실 코레일+ 1개 시나리오 자동 진행 + LLM | 7/10 | 매크로 탐지·좌석맵 접근성 이슈 존재 |
| 실 코레일+ 안정화 + 결제 게이트 | 7~8/10 | 화면 전환·예외처리·정책 방어 필요 |
| 모든 예매 앱 범용 지원 | 9/10 | UI 품질·앱 업데이트·Vision fallback 필요 |

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

**[S8] 코레일 매크로 승차권 불법거래 대응 관련 보도**
매크로 프로그램을 이용한 승차권 부당 확보·재판매는 철도사업법상 단속 대상이며, 코레일은 빅데이터 기반 모니터링과 매크로 탐지 솔루션으로 비정상 접속을 실시간 차단하고 있다고 보도됨.

**[S9] 코레일 매크로 탐지 솔루션 운영 관련 보도**
설·추석 등 예매 성수기에 매크로 탐지 솔루션을 가동해 비정상적 접속 시도 수만 건을 차단한 사례가 보도됨.
