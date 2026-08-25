# CLAUDE.md - AI Digital Guide Layer (모노레포)

## 1. 프로젝트 개요

- 핵심 철학: "Guide, not Autopilot" — AI는 UI를 해석해 다음 위치를 안내하고, 실제 클릭·결제·승인 등 최종 행동은 항상 사용자가 한다.
- 타겟 환경: Android Mobile (Kotlin), 초기 MVP 대상 앱: 코레일+ (KTX 예매 시나리오 — 예: 서울→부산, 출발역/도착역/날짜/좌석 선택 후 결제 대기 화면까지 자동 진행)
- **이 레포는 백엔드(FastAPI)와 Android 클라이언트(Kotlin)를 함께 포함하는 모노레포다.** 폴더로 담당 영역이 나뉜다.

### 데이터 흐름
[사용자 목표] → [Android 접근성 서비스] → [컨텍스트 빌더(JSON)] → [백엔드 API] → [LLM] → [오버레이] → [사용자 클릭] → (반복)

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
> (작성 필요 — AI 담당자)
- 사용 모델:
- 호출 방식 (백엔드 경유 / 클라이언트 직접 호출):
- 프롬프트 템플릿 위치:

## 4. 안전 원칙 (절대 준수 — 전체 프로젝트 공통)

1. **금융/거래 자동 실행 금지**: 송금·결제·인증·계정삭제 관련 요소는 LLM에 넘기기 전 백엔드가 차단.
2. **화면 데이터 비영속화**: UI Tree/노드 데이터는 요청 처리 중에만 메모리에 존재, 추론 직후 폐기. DB/파일 저장 금지.
3. **보안 통제 우회 금지**: Google Play 접근성 API 정책 준수 범위 내에서만 동작.
4. **신뢰도 게이트**: LLM confidence가 임계값 미만이면 백엔드가 응답을 무시하고 `status=ASK_USER`로 강제 override.
5. **민감정보 마스킹**: 비밀번호/주민번호/계좌번호 등은 LLM 전송 전 서버단에서 마스킹.

## 4-1. 결제·예매 연동 범위 (In-Scope / Out-of-Scope)

이번 해커톤 스코프에서는 실제 결제망에 연결하지 않는다. `docs/planning/`의 관련 섹션과 동일한 경계를 따른다.

- **In-Scope**: 앱에 등록해 둔 결제수단(Mock)이 예매 앱과 연동되어 결제·예매가 완료되는 것처럼 보이는 UI/UX 데모 플로우. Android 클라이언트 로컬 상태 전환만으로 구현(예: `PaymentMockRepository.completePayment()`가 delay 후 성공 결과를 반환).
- **Out-of-Scope**: 실제 PG사 결제 승인/취소 연동, 외부 예매 플랫폼(코레일·SRT 등)과의 Real-time API 결제 확정. backend는 이런 연동을 구현하지 않는다.
- Mock 결제 완료 화면도 반드시 "결제 대기 확인 → 사용자 확인 탭" 이후에만 진행한다. 안전 원칙 1·2번(자율 클릭 금지, 금융/거래 자동 실행 금지)은 Mock 플로우에도 동일하게 적용되며, Mock 화면은 실제 예매 앱의 결제 화면을 accessibility로 조작해 만들지 않고 우리 앱 자체 오버레이에서만 렌더링한다.

## 5. API 계약 — `POST /api/v1/decide`

### Request

```python
class ElementDTO(BaseModel):
    id: int
    text: str | None
    content_description: str | None
    class_name: str
    clickable: bool
    bounds: list[int]  # [left, top, right, bottom]

class HistoryEntry(BaseModel):
    step: int
    selected_text: str

class DecideRequest(BaseModel):
    session_id: str
    goal: str
    app_package: str
    elements: list[ElementDTO]
    history: list[HistoryEntry] | None = None
```

### Response

```python
class DecideResponse(BaseModel):
    target_node_id: int | None
    instruction: str
    confidence: float
    status: Literal["CONTINUE", "DONE", "ASK_USER", "UNSUPPORTED"]
    reason: str | None
```

> id는 string이 아니라 int. camelCase 초안은 폐기, 이 스키마로 통일한다. Android 쪽 `model/Types.kt`와 필드명이 정확히 일치하는지 Android 담당자가 대조 확인할 것.

## 6. 요청 처리 파이프라인 (백엔드)

1. 요청 수신 및 pydantic 검증 (`elements` 빈 배열 체크, `bounds` 4개 정수·`left<right`·`top<bottom` 검증)
2. safety 필터링 — 위험 키워드(송금/결제/삭제/인증 등) 매칭 element를 LLM 전달 목록에서 제외
3. 세션 로드 — `session_id`로 history(최근 2~3개) 조회
4. 민감 텍스트 마스킹
5. LLM 호출 (`services/ai_client.py`)
6. confidence 게이트 — 임계값 미만이면 `ASK_USER`로 강제 override
7. 응답 검증 — `target_node_id`가 원본 elements에 실재하는지 확인, 없으면 `UNSUPPORTED`
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
│ │ ├── ai_client.py # LLM 호출
│ │ ├── safety.py # 필터링, confidence 게이트, 마스킹
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

> (작성 필요 — AI 담당자)

- 사용 모델 및 API:
- 프롬프트 템플릿 위치 및 버전 관리 방식:
- 백엔드 `services/ai_client.py`와의 연동 방식:
- confidence 산출 방식 (모델이 직접 출력 / 별도 계산):
- Vision fallback 사용 여부 및 트리거 조건:
- 알려진 제약/이슈:

## 10. 실행 명령어

### 백엔드
- `uvicorn backend.main:app --reload --port 8000`
- `pip install -r requirements.txt`
- `pytest backend/tests`

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
- 실제 PG/외부 예매 플랫폼 결제 확정 API 연동 금지 — 결제는 Android 로컬 Mock으로만 구현 (4-1 참고)
- confidence 임계값, 위험 키워드 목록 하드코딩 금지 — config.py에서 관리
- 빈 elements 리스트 등 예외 상황에서 서버가 죽지 않고 항상 에러 포맷으로 응답하게 할 것
- 다른 담당자 폴더(backend가 아니면 android/, 그 반대도 마찬가지)의 코드를 사전 협의 없이 수정하지 말 것