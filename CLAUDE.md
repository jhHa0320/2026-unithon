
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
- confidence 임계값, 위험 키워드 목록 하드코딩 금지 — config.py에서 관리
- 빈 elements 리스트 등 예외 상황에서 서버가 죽지 않고 항상 에러 포맷으로 응답하게 할 것
- 다른 담당자 폴더(backend가 아니면 android/, 그 반대도 마찬가지)의 코드를 사전 협의 없이 수정하지 말 것