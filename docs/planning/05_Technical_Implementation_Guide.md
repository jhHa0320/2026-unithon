**구현방법 및 기술설계**

Android 개발을 처음 접하는 팀원이 코딩 에이전트와 함께 MVP를 만들 수 있도록 단계별로 설명

UNWORK Hackathon · 2026.08

# **1. 먼저 이해해야 할 핵심**

|**핵심 구조**<br>Android Accessibility API가 현재 화면의 “이름표와 좌표”를 제공하고, LLM이 그 정보와 사용자 목표를 보고 다음 UI 요소를 선택한다. Overlay는 그 위치를 화면 위에서 가리키며, 실제 클릭은 사용자가 한다.|
| :- |

![](Aspose.Words.413c0aee-41b2-4ebf-a2f3-e0066f861e47.001.png)

[아키텍처] Accessibility → Context → LLM → Overlay → User
# **2. Accessibility API가 실제로 주는 정보**
AccessibilityService는 현재 활성 창의 UI를 AccessibilityNodeInfo 트리 형태로 읽을 수 있다. 앱이 접근성 정보를 잘 제공한다면 각 노드에서 다음과 같은 정보를 얻을 수 있다.

|**필드**|**예시**|**용도**|
| :- | :- | :- |
|text|"송금", "채팅", "친구"|버튼/텍스트의 화면상 라벨|
|contentDescription|"더보기", "검색"|아이콘처럼 글자가 없는 UI의 접근성 설명|
|className|Button, TextView, ImageButton|UI 종류 추정|
|clickable|true/false|사용자가 눌러야 하는 후보인지 필터링|
|boundsInScreen|[left, top, right, bottom]|Overlay를 정확한 위치에 표시|
|packageName|com.kakao.talk|현재 앱 식별|
|actions|click, scroll, setText 등|지원 가능한 상호작용 파악|
|parent/children|UI hierarchy|현재 화면의 구조와 문맥 파악|

Android가 “이 버튼은 송금 기능이다”라는 비즈니스 의미를 직접 주는 것은 아니다. text/contentDescription과 주변 UI 구조를 제공하고, LLM이 언어적 의미와 사용자 목표를 연결한다.
# **3. 예시: AI는 어떻게 다음 버튼을 고르는가**
사용자 목표: “차단한 친구를 다시 풀고 싶어.”

현재 화면에서 수집한 클릭 가능한 UI가 [친구, 채팅, 더보기, 검색]이라면, 서버는 이를 단순 JSON으로 만들고 LLM에게 “다음으로 선택할 element ID 하나만 반환”하도록 요청한다. LLM은 일반적인 UI 의미를 바탕으로 설정/더보기 경로를 선택할 수 있다. 다음 화면이 열리면 다시 UI를 읽고 같은 판단을 반복한다.

![](Aspose.Words.413c0aee-41b2-4ebf-a2f3-e0066f861e47.002.png)

[에이전트 루프] 한 번에 한 단계만 판단하여 오류 전파를 줄인다.
# **4. 권장 기술 스택**

|**영역**|**추천**|**이유**|
| :- | :- | :- |
|Android|Kotlin + Android Studio|AccessibilityService와 시스템 API를 가장 직접적으로 다룸|
|UI|Jetpack Compose 또는 기본 View|초보자는 Compose로 자체 앱 UI, Overlay는 View 기반도 가능|
|Accessibility|AccessibilityService / AccessibilityNodeInfo|다른 앱의 접근성 UI 구조 읽기|
|Overlay|TYPE\_ACCESSIBILITY\_OVERLAY 또는 접근성 overlay API|대상 버튼 위 하이라이트|
|AI|LLM API|goal + UI elements → targetNodeId|
|Backend|간단한 FastAPI/Node 서버|API 키 보호, 프롬프트/로그 제어|
|Vision fallback|선택적 Screenshot + multimodal model|접근성 라벨이 없는 UI 보완|
|Speech|Android STT/TTS 또는 클라우드|MVP 후반에 추가|

# **5. 개발 단계 — 반드시 이 순서로**
## **Phase 0. Android 앱 실행**
- Android Studio 설치 후 Kotlin Empty Activity 생성.
- 실제 Android 폰에서 디버깅 연결.
- 우리 앱에 “접근성 권한 설정으로 이동” 버튼 하나 구현.
## **Phase 1. AccessibilityService 등록**
- Manifest에 AccessibilityService 선언.
- accessibility\_service\_config.xml에 canRetrieveWindowContent=true 등 필요한 설정.
- 사용자가 시스템 설정에서 직접 서비스를 켜도록 함.
- onAccessibilityEvent에서 현재 packageName과 이벤트 타입을 Logcat에 출력.
## **Phase 2. UI Tree 덤프**
- rootInActiveWindow에서 루트 노드를 얻음.
- 재귀 함수로 children 탐색.
- text, contentDescription, className, clickable, boundsInScreen을 출력.
- 카카오톡을 열고 실제로 어떤 데이터가 노출되는지 확인.

|**1차 성공 기준**<br>카카오톡을 열었을 때 Logcat에 “채팅”, “검색”, “설정/더보기” 등 현재 화면의 UI 정보와 좌표가 나타난다. 이 단계가 안 되면 AI를 붙이지 않는다.|
| :- |

## **Phase 3. AI 없이 하이라이트**
- text/contentDescription이 특정 문자열인 노드를 찾는다. 예: “설정”.
- getBoundsInScreen으로 Rect 좌표를 얻는다.
- Accessibility Overlay를 띄워 그 좌표에 반투명 테두리를 그린다.
- Overlay는 가능하면 터치를 가로채지 않아 사용자가 아래 앱을 직접 누르게 한다.

|**2차 성공 기준**<br>카카오톡 화면의 특정 버튼 위에 우리 앱의 테두리가 정확히 겹쳐 보이고, 사용자가 실제 카카오톡 버튼을 누를 수 있다.|
| :- |

## **Phase 4. LLM 연결**
- 모든 UI를 보내지 말고 클릭 가능한/의미 있는 노드만 추린다.
- 각 노드에 session 내 임시 ID를 부여한다.
- 사용자 goal + 현재 앱 + UI JSON을 backend로 전송.
- LLM 응답은 targetNodeId, instruction, confidence 정도의 고정 JSON 형식으로 제한.
- targetNodeId의 원래 bounds를 찾아 overlay를 이동.
## **Phase 5. 반복 루프**
- 사용자가 클릭하면 TYPE\_WINDOW\_STATE\_CHANGED/TYPE\_WINDOW\_CONTENT\_CHANGED 등 이벤트 발생.
- Debounce를 적용해 화면이 안정된 뒤 새 UI Tree를 수집.
- 같은 goal을 유지한 채 LLM에 새 상태를 전달.
- 완료 상태를 AI가 감지하거나 사용자가 “완료”를 누르면 세션 종료.
## **Phase 6. 음성 추가**
- 처음에는 텍스트 입력으로 충분.
- 동작 안정 후 STT로 goal 입력.
- instruction을 TTS로 읽어 접근성을 개선.
## **Phase 7. Vision fallback**
아이콘이 text=null, contentDescription=null로 노출되거나 커스텀 UI라 Accessibility Tree만으로 의미를 알 수 없을 때만 화면 캡처/비전 모델을 보조적으로 사용한다. 모든 화면을 계속 비전 모델에 보내는 구조는 비용·속도·프라이버시 면에서 불리하다.
# **6. 서버/LLM 프롬프트 설계**
프롬프트는 “전체 작업을 계획해라”보다 “현재 화면에서 다음으로 누를 요소 하나만 고르라”가 안전하다.

|**입력**|**내용**|
| :- | :- |
|goal|사용자가 최종적으로 하고 싶은 일|
|app|현재 package/app name|
|elements|id, text, contentDescription, class, clickable, bounds|
|history|직전 2~3단계의 선택 결과만 간단히|
|safety|결제/송금/삭제/권한 변경은 guide-only 또는 stop|

|**출력 필드**|**예시**|
| :- | :- |
|targetNodeId|17|
|instruction|“오른쪽 위 더보기를 눌러주세요.”|
|confidence|0\.91|
|status|CONTINUE / DONE / ASK\_USER / UNSUPPORTED|
|reason|디버깅용 짧은 근거; 사용자에게는 필요 시만 표시|

# **7. 안전 설계**
- AI가 performAction(ACTION\_CLICK)나 gesture dispatch로 자동 클릭하지 않는 것을 MVP 원칙으로 한다.
- 송금, 결제, 삭제, 인증, 권한 변경 등 민감 action은 안내만 하거나 아예 지원하지 않는다.
- LLM confidence가 낮으면 임의 하이라이트 대신 “지금 화면에서 원하는 메뉴가 보이지 않아요”처럼 질문한다.
- 비밀번호, 주민번호, 계좌번호, 메시지 내용 등 민감 text는 서버 전송 전에 마스킹할 수 있도록 노드 필터를 둔다.
- 가능하면 전체 UI Tree를 저장하지 않고 추론 직후 폐기한다.
- 접근성 권한을 요청하기 전에 화면 정보를 왜 읽는지, 어디에 쓰는지 명확히 설명한다.
# **8. Google Play 정책 관점**
AccessibilityService 사용 자체가 금지된 것은 아니다. 하지만 민감 API이므로 Play Console 선언, 인앱 명시적 공개·동의, 사용 목적의 정당성이 중요하다. Google Play는 접근성 API를 사용해 일반 앱이 자율적으로 작업을 시작·계획·실행하는 형태를 제한한다. 반면 장애 지원이 핵심 목적인 접근성 도구는 isAccessibilityTool 기준을 충족할 경우 별도 정책 틀이 적용된다. 우리 제품은 고령/인지·학습상의 디지털 어려움을 직접 지원한다는 목적과 “guide-only” 설계가 정책 방어에 중요하다.

|**정책상 유리한 제품 원칙**<br>AI는 화면을 읽고 안내한다. 사용자가 실제로 행동한다. 보안 통제를 우회하지 않는다. 필요한 화면 정보만 최소한으로 처리한다.|
| :- |

# **9. 카카오톡 MVP 추천 시나리오**

|**우선순위**|**시나리오**|**이유**|
| :- | :- | :- |
|1|채팅방 알림 끄기|위험도가 낮고 몇 단계의 UI 전환 데모 가능|
|2|차단 친구 해제|설정 계층 탐색 능력을 보여주기 좋음|
|3|글자 크기/테마 관련 설정|고령자 타겟과 잘 맞고 민감도가 낮음|

# **10. 코딩 에이전트에게 줄 작업 순서**
1. “Kotlin Android 프로젝트에서 AccessibilityService를 등록하고 접근성 설정으로 이동하는 버튼을 구현해줘.”
1. “현재 rootInActiveWindow의 AccessibilityNodeInfo tree를 재귀 탐색해 text/contentDescription/className/clickable/bounds/packageName을 Logcat에 출력해줘.”
1. “현재 UI에서 text 또는 contentDescription이 ‘설정’인 clickable node를 찾고 boundsInScreen을 반환해줘.”
1. “해당 bounds 위에 TYPE\_ACCESSIBILITY\_OVERLAY 기반 반투명 테두리를 그려줘. 사용자의 터치는 아래 앱으로 전달되어야 해.”
1. “clickable 또는 의미 있는 노드만 JSON으로 serialize하고 session-local node id를 부여해줘.”
1. “goal + elements JSON을 backend로 보내고 targetNodeId/instruction/confidence/status JSON을 받도록 구현해줘.”
1. “응답 node의 bounds로 overlay를 이동하고 AccessibilityEvent 발생 시 debounce 후 같은 loop를 반복해줘.”
1. “민감 텍스트 마스킹과 지원 제외 package/화면 정책을 추가해줘.”
# **11. 해커톤 1차 구현 체크리스트**
- □ Android 실기기에서 앱 실행
- □ 접근성 권한 켜기
- □ 카카오톡 UI Tree 로그 확인
- □ 특정 버튼 bounds 획득
- □ Overlay 하이라이트 성공
- □ 사용자가 아래 버튼 직접 클릭 가능
- □ 화면 변화 감지
- □ 텍스트 goal → LLM targetNodeId 반환
- □ 2~3단계 연속 가이드 성공
- □ 오류/취소 버튼
- □ 민감 행동 자동 클릭 없음
# **12. 구현 난이도 평가**

|**범위**|**난이도**|**설명**|
| :- | :- | :- |
|고정 문자열 버튼 하이라이트|3/10|AI 없이 Accessibility+Overlay 연습|
|카톡 1개 시나리오 + LLM|5~6/10|해커톤 MVP로 현실적|
|카톡 3개 시나리오 안정화|6~7/10|화면 전환·예외처리 필요|
|모든 일반 앱 범용 지원|9/10|UI 품질·앱 업데이트·Vision fallback 필요|
|금융앱 범용 지원|9~10/10|보안·정책·키패드·민감정보 이슈|

# **참고 자료**
**[S1] AI 스마트폰 도우미 - Google Play**\
https://play.google.com/store/apps/details?id=ai.fluiz.ditestbed\_agent\
2026-07-21 업데이트. 고령자 대상 접근성 앱으로, 음성 요청 후 Android Accessibility Service를 이용해 화면을 읽고 버튼 클릭·텍스트 입력·화면 이동을 수행한다고 설명.

**[S2] Gemini Live - Android Help**\
https://support.google.com/gemini/answer/15274899\
Android에서 화면 공유를 켜고 다른 앱을 사용하면서 Live 대화를 계속할 수 있음. 화면에 대한 실시간 도움을 제공.

**[S3] 손주도움 - Google Play**\
https://play.google.com/store/apps/details?id=com.guideops.app\
스마트폰 화면·키오스크·약봉투 등을 카메라로 비추면 다음 버튼과 사용법을 음성 안내.

**[S4] Android AccessibilityService API**\
https://developer.android.com/reference/android/accessibilityservice/AccessibilityService\
접근성 서비스는 UI 이벤트를 받고 활성 창의 UI 트리를 조회할 수 있으며 접근성 오버레이를 그릴 수 있음.

**[S5] Android AccessibilityNodeInfo API**\
https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo\
UI 노드의 text, contentDescription, boundsInScreen 등 접근성 정보를 조회할 수 있음.

**[S6] Google Play AccessibilityService 정책**\
https://support.google.com/googleplay/android-developer/answer/10964491?hl=ko\
접근성 API 사용 시 선언·공개·동의 요구. 일반 앱의 자율적 계획·실행 자동화는 제한되며 장애 지원이 핵심인 접근성 도구는 별도 기준 적용.

**[S7] Google Play 민감 권한/API 정책**\
https://support.google.com/googleplay/android-developer/answer/16558241\
Accessibility API는 보안 통제 우회·기만적 UI 조작 등에 사용할 수 없고 목적에 필요한 최소 데이터만 처리해야 함.
