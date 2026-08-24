# CLAUDE.md - AI Digital Guide Layer / PathPilot 백엔드 레포

## 1. 프로젝트 개요

- 핵심 철학: "Guide, not Autopilot" — AI는 UI를 해석해 다음 위치를 안내하고, 실제 클릭·결제·승인 등 최종 행동은 항상 사용자가 한다.
- 타겟 환경: Android Mobile (Kotlin), 초기 MVP 대상 앱: 카카오톡 (채팅방 알림 끄기, 차단 친구 해제)
- **이 레포는 백엔드(FastAPI)만 담당한다.** Android 클라이언트(AccessibilityService, Overlay, LLM 호출부)는 별도 레포(PathPilot, `com.unithon.pathpilot`)에 있다.

### 데이터 흐름
[사용자 목표] → [Android 접근성 서비스] → [컨텍스트 빌더(JSON)] → [백엔드 API(이 레포)] → [LLM] → [오버레이] → [사용자 클릭] → (반복)

## 2. 기술 스택 (이 레포)

- Python 3.11+, FastAPI, Pydantic v2, uvicorn
- 세션: in-memory dict + TTL (Redis 아님, 해커톤 스코프상 불필요)
- AI 모델: 노드 선택용 LLM(텍스트/구조 우선) + 비전 모델(라벨 없는 커스텀 뷰용 fallback, 후순위)

## 3. 안전 원칙 (절대 준수)

1. **자율 클릭 금지**: 백엔드는 클릭/제스처를 실행하는 어떤 지시도 만들지 않는다. 항상 안내 정보만 반환.
2. **금융/거래 자동 실행 금지**: 송금·결제·인증·계정삭제 관련 요소는 LLM에 넘기기 전 백엔드가 차단.
3. **화면 데이터 비영속화**: UI Tree/노드 데이터는 요청 처리 중에만 메모리에 존재, 추론 직후 폐기. DB/파일 저장 금지.
4. **보안 통제 우회 금지**: Google Play 접근성 API 정책 준수 범위 내에서만 동작.
5. **신뢰도 게이트**: LLM confidence가 임계값 미만이면 백엔드가 응답을 무시하고 `status=ASK_USER`로 강제 override.
6. **민감정보 마스킹**: 비밀번호/주민번호/계좌번호 등은 LLM 전송 전 서버단에서 마스킹.

## 4. API 계약 — `POST /api/v1/decide`

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

> id는 string이 아니라 int. 이전 초안(camelCase)은 폐기하고 이 스키마로 통일한다.

## 5. 요청 처리 파이프라인

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

## 6. 폴더 구조 (이 레포)
backend/
main.py
config.py
routers/decide.py
services/
ai_client.py # LLM 호출
safety.py # 필터링, confidence 게이트, 마스킹
session.py # session_id 기반 history 관리
schemas/
request.py # DecideRequest, ElementDTO, HistoryEntry
response.py # DecideResponse
core/
logging.py # 민감정보 제외 로깅
errors.py # 공통 에러 포맷
tests/


## 7. 실행 명령어

- `uvicorn backend.main:app --reload --port 8000`
- `pip install -r requirements.txt`
- `pytest backend/tests`

## 8. 코드 스타일

- 모든 함수/클래스에 타입 힌트 필수
- pydantic 모델로 입출력 검증, dict 그대로 주고받지 않기
- 비즈니스 로직은 router가 아니라 services/에
- 커밋: 기능 단위로 짧게 (예: `feat: add confidence gate`)

## 9. 하지 말 것

- PathPilot Android 레포(`service/`, `llm/`, `overlay/`, `model/`)의 Kotlin 코드를 대신 수정하지 말 것
- Redis, 외부 DB 등 스코프 밖 인프라 도입 금지
- confidence 임계값, 위험 키워드 목록 하드코딩 금지 — config.py에서 관리
- 빈 elements 리스트 등 예외 상황에서 서버가 죽지 않고 항상 에러 포맷으로 응답하게 할 것