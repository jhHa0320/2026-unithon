**기존 제품 및 차별점 분석**

시장에 이미 있는 제품과 겹치는 부분을 인정하고, 우리만의 제품 경계를 명확히 정의

UNWORK Hackathon · 2026.08

# **1. 결론 요약**

|**핵심 결론**<br>유사 문제를 푸는 제품은 이미 있다. 따라서 “고령자 스마트폰 AI 도우미”만으로는 차별화가 약하다. 우리 차별점은 자율 조작이 아니라 현재 앱 위에 직접 표시되는 단계별 UI 가이드와 사용자 통제 원칙에 둔다.|
| :- |

![](Aspose.Words.27e6d14e-1450-42cb-bb93-d4c1b49265bb.001.png)

[포지셔닝] 자동 조작 ↔ 범용 대화 ↔ 카메라 안내 사이의 Guide-only 영역
# **2. AI 스마트폰 도우미 (fluiz)**
Google Play에 실제 배포되어 있으며 2026년 7월 업데이트된 접근성 앱이다. 공개 설명에 따르면 고령 사용자가 음성 요청을 하면 Android Accessibility Service로 현재 화면을 읽고 버튼 클릭, 텍스트 입력, 화면 이동까지 사용자를 대신해 수행한다. 다만 초대된 참가자 로그인을 전제로 한 제한적 운영 형태다.

|**비교 항목**|**AI 스마트폰 도우미**|**우리**|
| :- | :- | :- |
|핵심 행동|AI가 직접 클릭/입력/이동|AI가 하이라이트하고 사용자가 직접 클릭|
|목표|복잡한 조작을 음성만으로 완료|UI를 이해해야 하는 부담 제거|
|안전성|자동 실행 범위가 넓을 수 있음|민감 행동을 사람에게 남김|
|해커톤 차별점|이미 유사 에이전트 존재|Guide-only + overlay UX + 설명 가능성|

# **3. Gemini Live**
Gemini Live는 Android에서 화면 공유를 켜면 현재 화면에 대해 실시간으로 대화할 수 있고, Live를 백그라운드로 두고 다른 앱을 사용할 수도 있다. 범용 멀티모달 AI라는 점에서 “화면을 이해하고 도움을 준다”는 문제 영역이 겹친다. 하지만 사용자가 대화로 도움을 요청하고 답을 듣는 경험이 중심이며, 우리 제품은 현재 UI 노드의 실제 좌표와 연결해 다음 버튼을 화면 위에서 직접 가리키는 접근을 핵심으로 한다.
# **4. 손주도움**
손주도움은 스마트폰 화면, 키오스크, 가전제품 등을 카메라로 비추면 AI가 다음에 눌러야 할 버튼과 사용 방법을 음성으로 안내한다. “어르신에게 다음 버튼을 알려준다”는 사용자 문제는 매우 유사하다. 다만 카메라 기반으로 외부 화면을 비추는 방식이 중심이고, 우리가 구상하는 MVP는 동일 스마트폰의 현재 앱 UI 트리와 좌표를 직접 사용해 앱 위에 오버레이하는 구조다.
# **5. 차별점의 강도 평가**

|**차별점 후보**|**강도**|**설명**|
| :- | :- | :- |
|고령자 타겟|약함|기존 제품도 동일 타겟이 존재|
|음성 요청|약함|이미 흔함|
|현재 화면 이해|약~중|Gemini/기존 에이전트가 이미 가능|
|앱 위 정확한 UI 하이라이트|중~강|대화 설명보다 직접적이며 accessibility bounds와 결합 가능|
|사용자 직접 클릭 원칙|강|자동 에이전트와 다른 안전·책임 철학|
|UI Tree 우선 + Vision fallback|중|정확도·비용·속도 측면의 구현 차별화|
|민감 행동 분리 정책|중~강|결제/송금/삭제 등에 명시적 human-in-the-loop 적용|

# **6. 발표에서 피해야 할 주장**
- “세상에 없는 최초의 고령자 AI 스마트폰 도우미” → 사실과 다름.
- “모든 앱에서 다 된다” → 접근성 정보·보안 정책 차이 때문에 과장.
- “금융앱 송금까지 안전하게 안내/자동화한다” → 데모/정책 검증 전에는 위험.
- “플러그인” → 모바일 OS 차원에서 일반적인 플러그인 개념은 아님. “Android 접근성 기반 AI 가이드 레이어/오버레이”가 정확함.
# **7. 추천 포지셔닝**

|**추천 문장**<br>기존 GUI Agent는 사용자를 대신해 스마트폰을 조작하려 한다. 우리는 사용자의 권한과 판단은 그대로 두고, “어디를 눌러야 하는지 알아내는 노동”만 없앤다.|
| :- |

# **8. 방어 가능한 제품 경계**
- 앱 개발사의 별도 연동 없이 Android 접근성 계층에서 동작하는 것을 목표로 함.
- 한 단계에 하나의 추천만 제공하여 오류 전파를 줄임.
- AI가 실제 클릭을 하지 않으므로 사용자가 항상 실행 전 상태를 확인할 수 있음.
- 접근성 정보가 부족한 화면에서만 Vision을 사용하여 비용과 프라이버시 노출을 줄임.
- 지원 앱/기능별 품질 점수를 관리하여 확신이 낮은 앱에서는 “지원 불가/추가 질문”으로 안전하게 실패.
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
