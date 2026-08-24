**구현방법 및 기술설계**

Android 개발을 처음 접하는 팀원이 코딩 에이전트와 함께 MVP를 만들 수 있도록 단계별로 설명

UNWORK Hackathon · 2026.08

# 1. 먼저 이해해야 할 핵심

| **핵심 구조**                                                                                                                                                       |
| --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Android Accessibility API가 현재 화면의 **UI 이름표와 위치**를 제공하고, LLM이 그 정보와 사용자의 목표를 보고 다음 UI 요소를 선택한다. Overlay는 사용자가 눌러야 할 위치를 화면 위에서 가리키며, 실제 클릭·입력·결제·승인은 사용자가 직접 한다. |

**전체 흐름**

`사용자 목표`
→ `Android AccessibilityService`
→ `현재 UI Tree 수집`
→ `Context JSON 생성`
→ `FastAPI Backend`
→ `LLM이 다음 UI 선택`
→ `Overlay로 하이라이트`
→ `사용자가 직접 클릭`
→ `새 화면 분석`
→ 반복

**MVP 예시 목표**

> “서울에서 부산 가는 내일 오전 기차표를 예매하고 싶어.”

PathPilot은 코레일 앱을 대신 조작하지 않는다.

현재 코레일 화면을 읽고,

> “출발역을 먼저 선택해주세요.”

> “이제 도착역을 눌러주세요.”

처럼 다음 행동만 안내한다.

---

# 2. Accessibility API가 실제로 주는 정보

AccessibilityService는 현재 활성화된 앱의 UI를 `AccessibilityNodeInfo` 트리 형태로 읽을 수 있다.

코레일 앱이 접근성 정보를 정상적으로 제공한다면 각 노드에서 다음과 같은 정보를 얻을 수 있다.

| **필드**             | **예시**                        | **용도**              |
| ------------------ | ----------------------------- | ------------------- |
| text               | `"승차권 예매"`, `"출발"`, `"도착"`    | 화면에 표시되는 UI 라벨      |
| contentDescription | `"날짜 선택"`, `"메뉴"`             | 아이콘처럼 글자가 없는 UI의 의미 |
| className          | Button, TextView, ImageButton | UI 종류 추정            |
| clickable          | true / false                  | 클릭 후보 필터링           |
| boundsInScreen     | `[left, top, right, bottom]`  | Overlay 위치 지정       |
| packageName        | 코레일 앱 package                 | 현재 앱 확인             |
| actions            | click, scroll, setText 등      | 해당 노드가 지원하는 동작      |
| parent / children  | UI hierarchy                  | 화면 구조와 문맥 파악        |

중요한 점은 Android가

> “이 버튼은 서울역을 선택하는 버튼이다.”

처럼 비즈니스 의미까지 알려주는 것은 아니라는 것이다.

Android가 제공하는 것은:

* `"출발"`
* `"도착"`
* `"조회"`
* `"승차권"`
* `"일반실"`
* `"결제"`

같은 텍스트와 화면 구조다.

LLM이 이를 사용자의 목적과 연결해서

> “현재는 출발역을 선택해야 한다.”

라고 판단한다.

---

# 3. 예시: AI는 코레일에서 어떻게 다음 버튼을 고르는가

사용자 목표:

> “내일 서울에서 부산 가는 오전 기차표를 예매하고 싶어.”

현재 화면에서 수집한 UI:

```text
1. 승차권 예매
2. 출발
3. 도착
4. 날짜
5. 인원
6. 조회
```

서버는 이를 JSON으로 정리한다.

```json
{
  "goal": "내일 서울에서 부산 가는 오전 기차표를 예매하고 싶어",
  "elements": [
    {
      "id": 1,
      "text": "승차권 예매",
      "clickable": true
    },
    {
      "id": 2,
      "text": "출발",
      "clickable": true
    },
    {
      "id": 3,
      "text": "도착",
      "clickable": true
    },
    {
      "id": 4,
      "text": "날짜",
      "clickable": true
    },
    {
      "id": 5,
      "text": "인원",
      "clickable": true
    },
    {
      "id": 6,
      "text": "조회",
      "clickable": true
    }
  ]
}
```

LLM에게는:

> 사용자의 최종 목표를 달성하기 위해 **현재 화면에서 다음으로 선택해야 할 UI 하나만 고르라.**

라고 요청한다.

LLM 응답:

```json
{
  "target_node_id": 2,
  "instruction": "출발역을 선택해주세요.",
  "confidence": 0.94,
  "status": "CONTINUE"
}
```

Android 앱은 `target_node_id=2`의 원래 좌표를 찾아 화면 위에 하이라이트를 표시한다.

사용자가 직접 누르면 다음 화면을 다시 분석한다.

---

# 4. 코레일 MVP에서 목표로 하는 전체 예매 흐름

초기 MVP에서는 코레일 전체 기능을 지원하지 않는다.

하나의 대표적인 승차권 검색·예매 흐름만 검증한다.

## 목표 시나리오

> 서울 → 부산
> 내일 오전
> 성인 1명
> KTX 검색
> 원하는 열차 선택
> 결제 직전까지 안내

예상 흐름:

```text
승차권 예매
↓
출발역 선택
↓
도착역 선택
↓
날짜 선택
↓
시간대 선택
↓
인원 확인
↓
열차 조회
↓
열차 목록 확인
↓
사용자가 원하는 열차 판단
↓
좌석/객실 선택
↓
결제 화면
↓
PathPilot 안내 종료
```

여기서 매우 중요한 원칙이 있다.

**AI가 할 일**

* 메뉴 찾기
* 다음 버튼 찾기
* 현재 화면 설명
* 어디를 눌러야 하는지 하이라이트

**사용자가 할 일**

* 서울/부산이라는 목적 판단
* 날짜 선택
* 시간대 선택
* 열차 선택
* 좌석 선택
* 가격 확인
* 결제
* 인증
* 최종 구매

---

# 5. 권장 기술 스택

| **영역**          | **추천**                                       | **이유**                                   |
| --------------- | -------------------------------------------- | ---------------------------------------- |
| Android         | Kotlin + Android Studio                      | AccessibilityService와 Android 시스템 API 사용 |
| 자체 앱 UI         | Jetpack Compose                              | 초보자가 상태 기반 UI를 만들기 쉬움                    |
| Accessibility   | AccessibilityService / AccessibilityNodeInfo | 코레일 앱의 UI Tree 읽기                        |
| Overlay         | TYPE_ACCESSIBILITY_OVERLAY                   | 눌러야 할 UI 위치 표시                           |
| AI              | LLM API                                      | goal + 현재 UI → 다음 UI 선택                  |
| Backend         | FastAPI                                      | API Key 보호, 안전 필터, 세션 관리                 |
| Vision fallback | Screenshot + multimodal model                | Accessibility 정보가 부족한 UI 보완              |
| Speech          | Android STT/TTS                              | 후반 단계에서 음성 입력·안내 추가                      |

---

# 6. 개발 단계 — 반드시 이 순서로

## Phase 0. Android 앱 실행

첫 목표는 AI가 아니다.

우선 PathPilot 자체 앱을 실제 Android 기기에서 실행한다.

자체 앱 화면 예시:

```text
PathPilot

무엇을 하고 싶으신가요?

[ 서울에서 부산 가는 기차표 예매 ]

[ 안내 시작 ]

접근성 권한: 사용 중
```

해야 할 일:

* Android Studio 설치
* Kotlin Empty Activity 생성
* 실제 Android 휴대폰 USB 디버깅 연결
* “접근성 권한 설정” 버튼 구현

---

## Phase 1. AccessibilityService 등록

해야 할 일:

* `AndroidManifest.xml`에 AccessibilityService 등록
* `accessibility_service_config.xml` 생성
* `canRetrieveWindowContent=true`
* 사용자에게 시스템 설정에서 접근성 서비스를 직접 활성화하도록 안내
* `onAccessibilityEvent()`에서 현재 packageName과 event type을 Logcat 출력

**성공 기준**

PathPilot 접근성 서비스를 켠 뒤 코레일 앱을 실행했을 때:

```text
package = ...
event = TYPE_WINDOW_STATE_CHANGED
```

같은 로그가 찍힌다.

---

## Phase 2. 코레일 UI Tree 덤프

`rootInActiveWindow`에서 현재 화면의 root node를 가져온다.

재귀적으로 child node를 탐색해서 다음 정보를 출력한다.

* text
* contentDescription
* className
* clickable
* boundsInScreen
* packageName

예상 로그:

```text
text=승차권 예매
clickable=true
bounds=[45,180][410,280]

text=출발
clickable=true
bounds=[70,420][500,560]

text=도착
clickable=true
bounds=[580,420][1010,560]

text=조회
clickable=true
bounds=[80,1200][1000,1340]
```

**1차 성공 기준**

> 코레일 승차권 예매 화면을 열었을 때 주요 UI의 text/contentDescription과 좌표가 Logcat에 실제로 출력된다.

이 단계가 안 되면 LLM을 연결하지 않는다.

---

# 7. Phase 3. AI 없이 코레일 버튼 하이라이트

처음부터 AI가

> “출발역 버튼을 찾아라.”

라고 판단하게 하지 않는다.

처음에는 하드코딩한다.

예:

```text
text == "출발"
```

인 노드를 찾는다.

그 node의:

```text
boundsInScreen
```

을 가져온다.

그리고 해당 영역 위에 `TYPE_ACCESSIBILITY_OVERLAY`로 반투명 테두리를 표시한다.

예:

```text
┌──────────────────────────────┐
│ 승차권 예매                   │
│                              │
│  ┌───────────┐   ┌─────────┐ │
│  │ ① 출발    │   │ 도착    │ │
│  └───────────┘   └─────────┘ │
│                              │
│          [ 조회 ]            │
└──────────────────────────────┘
```

안내:

> “먼저 출발역을 눌러주세요.”

Overlay는 사용자의 터치를 막지 않아야 한다.

실제 클릭은 코레일 앱이 받는다.

**2차 성공 기준**

> 코레일의 실제 출발역 버튼 위에 PathPilot 테두리가 정확히 겹쳐 보이고, 사용자가 아래의 코레일 버튼을 직접 누를 수 있다.

---

# 8. Phase 4. LLM 연결

AI 없이 Overlay까지 성공했다면 LLM을 연결한다.

Android에서 모든 UI node를 서버로 보내지 않는다.

의미 있는 노드만 추린다.

예:

```json
{
  "session_id": "abc123",
  "goal": "내일 서울에서 부산 가는 오전 KTX를 예매하고 싶어",
  "app_package": "현재 코레일 package",
  "elements": [
    {
      "id": 1,
      "text": "출발",
      "content_description": null,
      "class_name": "TextView",
      "clickable": true,
      "bounds": [70, 420, 500, 560]
    },
    {
      "id": 2,
      "text": "도착",
      "content_description": null,
      "class_name": "TextView",
      "clickable": true,
      "bounds": [580, 420, 1010, 560]
    },
    {
      "id": 3,
      "text": "조회",
      "content_description": null,
      "class_name": "Button",
      "clickable": true,
      "bounds": [80, 1200, 1000, 1340]
    }
  ]
}
```

백엔드가 LLM에 전달한다.

응답:

```json
{
  "target_node_id": 1,
  "instruction": "출발역을 먼저 선택해주세요.",
  "confidence": 0.95,
  "status": "CONTINUE",
  "reason": "사용자 목표의 출발지가 아직 설정되지 않음"
}
```

Android는 node 1의 좌표에 Overlay를 표시한다.

---

# 9. Phase 5. 코레일 화면 전환 반복 루프

사용자가 출발역을 누르면 코레일 화면이 바뀐다.

AccessibilityService에서 다음과 같은 이벤트를 감지한다.

* `TYPE_WINDOW_STATE_CHANGED`
* `TYPE_WINDOW_CONTENT_CHANGED`

화면 전환 직후에는 UI가 여러 번 변경될 수 있으므로 Debounce를 적용한다.

예:

```text
UI 변경
↓
300~700ms 대기
↓
UI Tree 안정화
↓
새 Context 생성
↓
LLM 호출
```

그리고 다시:

`현재 화면`
→ `다음 UI 판단`
→ `Overlay`
→ `사용자 클릭`

을 반복한다.

---

# 10. 코레일 MVP 예시 Agent Loop

사용자:

> “서울에서 부산 가는 내일 오전 KTX 예매하고 싶어.”

### Step 1

현재 화면:

```text
출발
도착
날짜
조회
```

AI:

> “출발역을 선택해주세요.”

Overlay → `출발`

### Step 2

역 검색 화면:

```text
최근 검색
역 이름 검색
서울
용산
광명
...
```

AI:

> “서울역을 선택해주세요.”

Overlay → `서울`

### Step 3

메인 화면 복귀:

```text
출발: 서울
도착
날짜
조회
```

AI:

> “도착역을 선택해주세요.”

Overlay → `도착`

### Step 4

도착역 선택:

AI:

> “부산역을 선택해주세요.”

### Step 5

날짜:

AI:

> “탑승 날짜를 선택해주세요.”

### Step 6

열차 조회:

AI:

> “설정한 조건으로 열차를 조회해주세요.”

### Step 7

열차 목록:

```text
08:10 KTX
09:00 KTX
09:40 KTX
10:20 KTX
```

여기서는 AI가 임의로 열차를 골라서는 안 된다.

안내:

> “이제 원하는 출발 시간과 가격을 확인한 뒤 열차를 직접 선택해주세요.”

**사람의 판단 영역으로 전환한다.**

---

# 11. 결제 단계에서의 안전 원칙

코레일 MVP에서 가장 중요한 경계다.

다음과 같은 UI가 등장하면:

* 결제
* 카드
* 비밀번호
* 인증
* 구매
* 최종 확인

AI는 이를 자동으로 진행하지 않는다.

예:

```json
{
  "target_node_id": null,
  "instruction": "이후 단계는 결제 및 구매와 관련되어 있어 직접 진행해주세요.",
  "confidence": 1.0,
  "status": "DONE",
  "reason": "민감 거래 단계 진입"
}
```

즉 PathPilot의 역할은:

```text
기차표를 어떻게 예매하는지 찾아가는 과정
```

까지다.

최종 거래 책임은 사용자에게 남긴다.

---

# 12. Phase 6. 음성 추가

초기 MVP에서는 텍스트 입력이면 충분하다.

예:

```text
[ 서울에서 부산 가는 내일 오전 기차표 예매하고 싶어 ]
```

동작 안정 후 음성을 추가한다.

사용자:

> “내일 오전 부산 가는 기차표 끊고 싶어.”

STT:

```text
내일 오전 부산 가는 기차표 끊고 싶어
```

LLM goal로 전달.

Overlay와 함께 TTS:

> “출발역을 먼저 눌러주세요.”

---

# 13. Phase 7. Vision fallback

코레일 앱의 일부 UI가:

```text
text = null
contentDescription = null
```

로 노출되면 Accessibility Tree만으로 기능을 파악하기 어렵다.

그때만:

```text
Accessibility Tree
+
현재 Screenshot
+
사용자 Goal
```

을 Vision 모델로 전달한다.

Vision 모델:

> “화면 하단의 파란색 버튼은 열차 조회 버튼으로 보입니다.”

단, 처음부터 모든 화면을 비전 모델로 보내지 않는다.

이유:

* 응답 속도
* API 비용
* 개인정보
* 화면 데이터 처리량

때문이다.

**Accessibility 우선, Vision은 fallback**이 기본 원칙이다.

---

# 14. 서버 / LLM 프롬프트 설계

프롬프트는:

> “기차표를 예매해라.”

가 아니다.

반드시:

> “현재 화면에서 다음으로 안내할 UI 하나만 고르라.”

로 제한한다.

## 입력

| **입력**   | **내용**                                                 |
| -------- | ------------------------------------------------------ |
| goal     | 사용자가 최종적으로 하고 싶은 일                                     |
| app      | 현재 코레일 앱 package                                       |
| elements | id, text, contentDescription, class, clickable, bounds |
| history  | 직전 2~3단계 선택                                            |
| safety   | 결제·인증·구매 단계에서는 stop                                    |

## 출력

| **필드**         | **예시**                                   |
| -------------- | ---------------------------------------- |
| target_node_id | 17                                       |
| instruction    | `"출발역을 눌러주세요."`                          |
| confidence     | 0.91                                     |
| status         | CONTINUE / DONE / ASK_USER / UNSUPPORTED |
| reason         | 디버깅용 짧은 설명                               |

---

# 15. 안전 설계

* AI가 `performAction(ACTION_CLICK)`으로 코레일 버튼을 대신 클릭하지 않는다.
* `dispatchGesture()`를 통한 자동 조작도 MVP에서는 사용하지 않는다.
* 사용자가 직접 UI를 누른다.
* AI는 기차 시간, 가격, 객실 등 사용자의 선택을 임의로 결정하지 않는다.
* 결제·구매·인증 단계는 자동 안내를 중단하거나 최소한의 설명만 제공한다.
* LLM confidence가 낮으면 잘못된 버튼을 표시하지 않는다.
* 낮은 confidence에서는:

> “현재 화면에서 다음 단계를 확실히 판단하기 어렵습니다.”

라고 표시한다.

* 비밀번호, 카드번호, 주민번호 등 민감 정보는 LLM에 전송하지 않는다.
* UI Tree 원본은 추론 후 폐기한다.
* 전체 화면 데이터를 DB에 저장하지 않는다.

---

# 16. Google Play 정책 관점

AccessibilityService 사용 자체가 금지된 것은 아니다.

하지만 Accessibility API는 민감한 API이므로:

* 사용 목적 공개
* 사용자 동의
* 최소 데이터 처리
* 보안 통제 우회 금지

가 중요하다.

PathPilot은 특히 다음 원칙을 유지한다.

> **AI는 코레일 앱을 대신 조작하지 않는다.**

> **AI는 현재 화면을 해석해서 다음 위치를 안내한다.**

> **사용자가 실제 행동과 판단을 한다.**

이 구조는 제품 철학인:

> **Guide, not Autopilot**

과 일치한다.

---

# 17. 코레일 MVP 추천 시나리오

| **우선순위** | **시나리오**            | **이유**              |
| -------- | ------------------- | ------------------- |
| 1        | 출발역 → 도착역 → 날짜 → 조회 | 가장 기본적인 예매 탐색 흐름    |
| 2        | 조회된 열차 중 사용자가 직접 선택 | 사람의 판단을 남기는 모습을 보여줌 |
| 3        | 좌석/객실 화면까지 안내       | 여러 화면 전환 테스트 가능     |
| 제외       | 결제 완료               | 거래·인증·민감정보 문제       |
| 제외       | 카드정보 입력             | 보안 리스크              |
| 제외       | 자동 구매               | Guide-only 철학 위배    |

MVP 데모에서는:

> **서울 → 부산 → 내일 → 열차 검색 → 특정 열차 선택 화면까지**

정도가 적절하다.

---

# 18. 코딩 에이전트에게 줄 작업 순서

1. “Kotlin Android 프로젝트에서 AccessibilityService를 등록하고 접근성 설정으로 이동하는 버튼을 구현해줘.”

2. “현재 `rootInActiveWindow`의 `AccessibilityNodeInfo` tree를 재귀 탐색해서 `text`, `contentDescription`, `className`, `clickable`, `bounds`, `packageName`을 Logcat에 출력해줘.”

3. “코레일 앱 승차권 예매 화면에서 현재 노출되는 AccessibilityNodeInfo를 확인할 수 있도록 UI Tree dump 기능을 만들어줘.”

4. “현재 UI에서 text 또는 contentDescription이 ‘출발’인 clickable node를 찾고 `boundsInScreen`을 반환해줘.”

5. “해당 bounds 위에 `TYPE_ACCESSIBILITY_OVERLAY` 기반 반투명 테두리를 표시해줘. 사용자의 터치는 아래 코레일 앱으로 전달되어야 해.”

6. “clickable 또는 의미 있는 node만 JSON으로 serialize하고 session-local int id를 부여해줘.”

7. “사용자의 goal + elements JSON을 FastAPI backend의 `/api/v1/decide`로 보내도록 구현해줘.”

8. “backend에서 반환한 `target_node_id`, `instruction`, `confidence`, `status`를 받아 해당 node 위치에 Overlay를 표시해줘.”

9. “AccessibilityEvent가 발생하면 debounce 후 새로운 UI Tree를 읽고 같은 loop를 반복해줘.”

10. “결제, 카드, 인증, 구매, 비밀번호 등 민감 UI가 감지되면 자동으로 안내 세션을 종료하거나 STOP 상태로 처리해줘.”

---

# 19. 해커톤 1차 구현 체크리스트

* [ ] Android 실기기에서 PathPilot 실행
* [ ] 접근성 권한 활성화
* [ ] 코레일 앱 실행
* [ ] 코레일 UI Tree Logcat 출력
* [ ] 출발역 버튼 node 확인
* [ ] 해당 node bounds 획득
* [ ] 코레일 앱 위 Overlay 표시
* [ ] Overlay 아래의 실제 코레일 버튼 직접 클릭 가능
* [ ] 화면 변화 감지
* [ ] 출발역 선택 화면 UI Tree 재수집
* [ ] 텍스트 goal → LLM target_node_id 반환
* [ ] 출발 → 도착 → 날짜 등 3단계 이상 연속 안내
* [ ] confidence 낮을 때 ASK_USER 처리
* [ ] 결제 단계 진입 시 안내 종료
* [ ] 자동 클릭 없음
* [ ] 민감정보 LLM 전송 없음

---

# 20. 1차 성공 기준

### 기술 성공 기준

다음 장면을 실제 Android 휴대폰에서 보여줄 수 있으면 핵심 기술 검증 성공이다.

1. 사용자가 PathPilot에:

> “서울에서 부산 가는 기차표 예매하고 싶어.”

입력

2. 코레일 앱으로 이동

3. PathPilot이 실제 코레일 화면의 **출발 버튼 위에 하이라이트**

4. 사용자가 직접 클릭

5. 바뀐 화면에서 **서울역 위치를 다시 하이라이트**

6. 선택 후 다시 도착역 버튼 안내

즉,

> **한 개의 고정 UI를 강조하는 것이 아니라, 실제 타사 앱의 화면이 바뀔 때마다 AI가 UI를 다시 이해하고 다음 단계의 Overlay 위치를 바꾼다.**

이걸 보여주는 것이 MVP의 핵심이다.

---

# 21. 구현 난이도 평가

| **범위**              | **난이도** | **설명**                     |
| ------------------- | ------- | -------------------------- |
| 코레일 특정 버튼 하이라이트     | 3/10    | Accessibility + Overlay 검증 |
| 출발→도착→조회 고정 플로우     | 4~5/10  | AI 없이도 가능                  |
| 코레일 1개 예매 플로우 + LLM | 5~6/10  | 해커톤 MVP로 현실적               |
| 날짜/시간/열차 목록까지 안정화   | 6~7/10  | 다양한 UI 구조 처리               |
| 코레일 대부분의 기능 범용 안내   | 8~9/10  | 예외 상황·화면 다양성 증가            |
| 여러 교통 앱 범용 지원       | 9/10    | 앱별 UI 차이와 fallback 필요      |

---

# 22. 최종 제품 원칙

PathPilot은 기차표를 대신 구매하는 AI가 아니다.

사람에게 남기는 것:

* 어디로 갈 것인지
* 언제 갈 것인지
* 어떤 열차를 탈 것인지
* 얼마를 지불할 것인지
* 실제 구매할 것인지

AI에게 넘기는 것:

* 메뉴가 어디 있는지 찾기
* 다음에 어떤 화면으로 가야 하는지 파악하기
* 복잡한 UI 구조 이해하기
* 사용법 검색하기
* 자녀에게 “이거 어디 눌러?”라고 물어보기

따라서 핵심 문장은 다음과 같다.

> **기차표를 대신 예매해주는 AI가 아니라, 기차표 예매 방법을 알아내야 하는 일을 없앤다.**

또는:

> **AI는 목적지를 결정하지 않는다. 목적지까지 가는 UI의 길만 보여준다.**
