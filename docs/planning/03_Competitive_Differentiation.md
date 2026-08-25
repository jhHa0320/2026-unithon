# 기존 제품 및 차별점 분석

시장에 이미 있는 제품과 겹치는 부분을 인정하고, 우리만의 제품 경계를 명확히 정의

UNWORK Hackathon · 2026.08

# 1. 결론 요약

| **핵심 결론** 화면을 켜고 앱을 대신 조작해주는 AI 도우미는 이미 있고, 2026년에는 Google이 Android 기본 기능으로 밀어넣는 중이다. 우리 차별점은 "화면 Off 상태에서 wake word 한마디로 진입하고, 정보가 부족하면 추측하지 않고 한 문장씩 되물어서, 카카오톡 사진 보내기 같은 일상 작업 하나를 끝까지 완수한다"는 진입 방식과 대화 경계에 있다. |
| --- |

[포지셔닝] 범용 자율 에이전트 ↔ 전 과정 사용자 조작 사이에서, "화면 Off 음성 진입 + 확실할 때만 클릭하고 불확실하면 되묻기 + 단일 시나리오 완수" 영역

대표 시나리오: **"엄마한테 사진 보내줘"** — 화면이 꺼진 상태에서 발화 → 카카오톡 자동 실행 → 대화방 선택·사진 첨부·전송까지 접근성 API로 자동 진행, 부족한 정보(누구에게/어떤 사진)는 음성으로 되묻는다.

# 2. Google의 Android 기본 탑재 흐름 (가장 직접적인 위협)

경쟁 대상 중 우리와 가장 겹치는 것은 별도 앱이 아니라 **Google이 Android OS에 직접 넣고 있는 기능**이다. 이 문서에서 가장 먼저 다뤄야 할 비교 대상이다.

| **제품/기능** | **발표 시점** | **우리와 겹치는 지점** |
| --- | --- | --- |
| Gemini Autonomous Task Engine | 2026년 3월 | Android 시스템에 통합되는 자율 작업 엔진. 로컬 데이터에 접근하고 **접근성 샌드박스를 통해 버튼을 클릭**해 예약·문서 작성 같은 멀티스텝 워크플로를 수행. 우리가 쓰는 접근성 기반 UI 자동 조작과 기술적 접근이 사실상 동일 |
| Gemini Intelligence | 2026년 5월 (Google I/O) | 예약·검색·요약·정리 같은 멀티스텝 액션을 앱 간에 자동화하고 사용자 대신 웹을 탐색. Galaxy S26·Pixel 10부터 순차 적용 후 다른 Android 기기로 확대 |
| Gemini 3.5 Flash의 Computer Use | 2026년 | 브라우저·모바일·데스크톱 UI를 스크린샷으로 보고 클릭/입력을 생성하는 기능이 모델에 네이티브로 탑재. 우리의 Vision fallback 경로와 겹침 |

**정직하게 인정할 것**: "앱을 대신 조작한다"는 기능 자체는 더 이상 우리만의 것이 아니며, OS 기본 탑재라는 유통 우위까지 감안하면 기능 경쟁으로는 이길 수 없다. 우리가 지킬 수 있는 것은 기능이 아니라 **진입 방식·대화 방식·타겟**이다.

# 3. Gemini Live

Gemini Live는 Android에서 화면 공유를 켜면 현재 화면에 대해 실시간으로 대화할 수 있고, Live를 백그라운드로 두고 다른 앱을 사용할 수도 있다. 사용자가 대화로 도움을 요청하고 답을 듣는 경험이 중심이며, 자동으로 UI를 조작하지는 않는다. 우리 제품은 화면 Off 상태에서 진입해 실제 UI 노드를 조작까지 자동 실행한다는 점에서 조작 대행 범위가 다르다.

# 4. 손주도움

손주도움은 스마트폰 화면, 키오스크, 가전제품 등을 카메라로 비추면 AI가 다음에 눌러야 할 버튼과 사용 방법을 음성으로 안내한다. 조작은 사용자가 직접 한다. 우리 제품은 안내가 아니라 조작 자체를 자동 실행한다는 점에서 자동화 수준이 다르다.

# 5. 차별점의 강도 평가

| **차별점 후보** | **강도** | **설명** |
| --- | --- | --- |
| 화면 Off wake word 상시 대기 진입 | 강 | 대부분의 경쟁 서비스와 Gemini Intelligence는 사용자가 기기를 켜고 호출하는 흐름을 전제로 함. 우리는 화면이 꺼진 상태에서 발화만으로 진입 |
| 고령자 특화 되묻기 UX | 강 | 정보가 부족하면 추측해서 진행하지 않고, 단계마다 한 문장씩 음성으로 되묻는다. 범용 에이전트는 맥락을 스스로 채워 진행하는 쪽을 선호하므로 고령자에게는 실패가 눈에 보이지 않게 누적됨 |
| 단일 시나리오 깊이 | 중~강 | 범용 자동화가 아니라 카카오톡 사진 보내기 하나를 끝까지 확실히 해내는 데 집중. 커버리지 대신 완수율로 경쟁 |
| 기존 앱 수정 없이 접근성 레이어 위에서 동작 | 중 | 카카오톡 등 대상 앱의 별도 연동·SDK 없이 동작. 단 Google도 동일한 접근성 경로를 쓰므로 우리만의 강점은 아님 |
| UI Tree 우선 + Vision fallback | 중 | 정확도·비용·속도 측면의 구현 차별화 |

**실측 근거**: 모호한 목표(예: "사진 보내줘"처럼 대상과 사진이 특정되지 않은 발화)에서 되묻기 성공률 100%를 달성했다.

> (팀 확인 필요 — 위 100% 수치의 측정 표본 수·테스트 조건을 발표 자료에 함께 명시할 것. 표본 없이 100%만 제시하면 심사에서 신뢰를 잃는다.)

# 6. 예상 심사 질문 — "구글이 곧 기본 탑재하는 것 아닌가?"

이 질문은 **나올 가능성이 높다.** 회피하지 말고 먼저 인정한 뒤 아래 세 가지로 답한다.

| **질문** | **우리 답변** |
| --- | --- |
| 기능이 겹치는가? | 겹친다. Gemini Autonomous Task Engine도 접근성 샌드박스로 버튼을 클릭한다. 부정하지 않는다 |
| 그럼 왜 필요한가? (1) 진입 | Gemini Intelligence는 기본적으로 사용자가 기기를 켜고 호출하는 흐름이다. 화면을 켜고 앱을 찾는 것 자체가 부담인 사용자에게는 그 앞단이 남아 있다. 우리는 화면 Off 상태에서 wake word로 진입한다 |
| 그럼 왜 필요한가? (2) 대화 | 범용 에이전트는 부족한 정보를 스스로 추론해 진행한다. 고령자 대상에서는 이것이 오히려 위험하다. 우리는 추측 대신 단계마다 한 문장씩 되묻는다 |
| 그럼 왜 필요한가? (3) 깊이 | 범용 커버리지 대신 하나의 시나리오를 끝까지 완수하는 데 집중한다. 넓게 되는 것보다 이 한 가지가 반드시 되는 것이 타겟 사용자에게 더 중요하다 |
| 장기적으로 어떻게 되는가 | OS 기본 기능이 고령자 UX까지 흡수하면 독립 앱의 여지는 줄어든다. 이 리스크는 인정하고, `04_Business_Model.md` 8장에 리스크 항목으로 명시한다 |

# 7. 발표에서 피해야 할 주장

"구글이 못 하는 것을 우리가 한다" → 사실과 다름. 조작 자동화 자체는 Google이 이미 OS 레벨에서 하고 있다. 차이는 진입 방식·되묻기 대화·타겟 특화다.

"모든 앱에서 다 된다" → 접근성 정보·보안 정책 차이 때문에 과장. 현재 검증된 것은 카카오톡 사진 보내기 시나리오다.

"매크로가 아니다" → 기술적으로는 자동 조작과 유사한 패턴이므로, 개인 사용 목적과 정보 부족 시 되묻는 안전장치로 차별점을 설명해야 함.

"플러그인" → 모바일 OS 차원에서 일반적인 플러그인 개념은 아님. "Android 접근성 기반 음성 자동조작 레이어"가 정확함.

"카카오톡과 제휴/연동돼 있다" → 사실과 다름. 대상 앱의 별도 연동 없이 접근성 계층에서 동작할 뿐이다.

# 8. 추천 포지셔닝

| **추천 문장** 앱을 대신 조작하는 AI는 이제 OS에 기본으로 들어온다. 우리는 그 앞단 — 화면을 켜는 것조차 부담인 사람이 말 한마디로 진입하고, AI가 추측 대신 되물어 확인하는 구간 — 을 고령자 기준으로 다시 설계한다. |
| --- |

# 9. 방어 가능한 제품 경계

앱 개발사의 별도 연동 없이 Android 접근성 계층에서 동작하는 것을 목표로 함.

화면 Off 상태의 wake word 상시 대기를 제품의 기본 진입점으로 삼음 — 이것이 OS 기본 에이전트와의 가장 뚜렷한 UX 경계.

정보가 부족한 상황에서 자동 추론으로 진행하지 않고 `ASK_USER`로 전환해 한 문장씩 되묻는 것을 기본 동작으로 고정.

접근성 정보가 부족한 화면에서만 Vision을 보조적으로 사용하거나 대체 UI로 전환함.

지원 시나리오/기능별 품질 점수를 관리하여 확신이 낮은 경우 자동 실행 대신 "지원 불가/추가 질문(되묻기)"으로 안전하게 실패함.

화면 데이터·사진은 요청 처리 중에만 메모리에 두고 추론 직후 폐기 — 저장하지 않음.

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

> [S8]·[S9]는 이전 KTX 예매 시나리오 전용 자료(코레일 매크로 탐지 관련 보도)였으므로 시나리오 변경에 따라 제거함. 번호는 다른 기획 문서와의 대조를 위해 재사용하지 않는다.

**[S10] Gemini Autonomous Task Engine (2026년 3월)**
https://vucense.com/ai-intelligence/agentic-ai/gemini-autonomous-task-engine-android-2026/
Android 시스템에 통합되는 자율 작업 엔진. 로컬 데이터에 접근하고 접근성 샌드박스(Sandboxed Accessibility Service)를 통해 버튼을 클릭해 예약·문서 작성 같은 멀티스텝 워크플로를 수행. 최종 단계에는 사용자 확인을 두는 안전장치가 있다고 설명됨.

**[S11] Gemini Intelligence — Google 공식 블로그 (2026년 5월, Google I/O)**
https://blog.google/products-and-platforms/platforms/android/gemini-intelligence/
예약·검색·요약·정리 같은 멀티스텝 액션을 앱 간에 자동화하고 사용자 대신 웹을 탐색. Galaxy S26 시리즈와 Pixel 10부터 순차 적용된 뒤 다른 Android 기기로 확대.

**[S12] Introducing computer use in Gemini 3.5 Flash — Google 공식 블로그**
https://blog.google/innovation-and-ai/models-and-research/gemini-models/introducing-computer-use-gemini-3-5-flash/
브라우저·모바일·데스크톱 UI를 스크린샷으로 보고 클릭·입력·스크롤 명령을 생성하는 computer use 기능이 별도 모델이 아니라 Gemini 3.5 Flash에 네이티브로 탑재됨.
