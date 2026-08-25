# 📋 멤버별 개발 체크리스트 (10시간 데모 스코프)

전체 타임박스와 의존성은 [`docs/planning/05`](../docs/planning/05_Technical_Implementation_Guide.md) §6을 본다.
구현 순서의 상세 완료 기준은 [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) §4를 본다.

## 🧑‍💻 멤버 A (음성 / UX / 네트워크)
- [ ] **1. 마이크 버튼 화면 + 권한** — `RECORD_AUDIO`, 접근성 설정 이동 버튼, 권한 사유 설명 화면
- [ ] **2. STT/TTS** — `SpeechRecognizer` 한국어 인식, `TextToSpeech` 재생. 인식 결과를 화면에 크게 표시
- [ ] **3. Retrofit 네트워킹 + `model/Types.kt`** — `CLAUDE.md` §5 스키마와 필드명 1:1 (snake_case 유지) 🔗 *Mock 서버가 이미 동작하므로 지금 바로 시작 가능*
- [ ] **4. 되묻기·동의 왕복 로직** — `ASK_USER`/`CONFIRM_REQUIRED` 수신 시 루프 정지 → TTS → STT → `goal` 누적 후 재요청
- [ ] **5. 진행 상황 오버레이** — `TYPE_ACCESSIBILITY_OVERLAY` (`SYSTEM_ALERT_WINDOW` 불필요)

## 🧑‍💻 멤버 B (백엔드 / AI)
- [x] **1. Mock 서버** — `MockAIClient` 동작 중. A·C가 대기 없이 통신 개발 가능
- [ ] **2. Anthropic Claude 연동** — `ClaudeAIClient`를 `get_ai_client()`에 연결, `ANTHROPIC_API_KEY` 설정
- [ ] **3. 시스템 프롬프트 튜닝** — `backend/prompts/decide_system.md` 🔗 *의존: 멤버 C의 카카오톡 UI 덤프*
- [ ] **4. 되묻기·동의 프롬프트 검증** — 정보 부족 시 `ASK_USER`, 전송/결제 앞 `CONFIRM_REQUIRED`가 실제로 나오는지 실측
- [ ] **5. confidence 임계값 튜닝** — 실측 분포를 보고 `CONFIDENCE_THRESHOLD` 조정

## 🧑‍💻 멤버 C (접근성 / 자동조작)
- [ ] **1. AccessibilityService 등록** — Manifest `<service>` + `accessibility_service_config.xml`
- [ ] **2. 카카오톡 UI Tree 덤프** 🔗 **멤버 B에게 즉시 전달 필요 — 프롬프트 개발이 여기서 막힌다**
      - ⚠️ **사진 첨부 그리드에 contentDescription이 있는지 반드시 확인.** 없으면 Vision fallback 또는 텍스트 메시지 백업 시나리오로 전환해야 한다
- [ ] **3. 자동 실행부** — `ACTION_CLICK`, `ACTION_SET_TEXT`, `ACTION_SCROLL_FORWARD`, `GLOBAL_ACTION_BACK`
- [ ] **4. `<queries>` + 앱 실행** — `PackageManager`로 설치 앱 목록 수집, `getLaunchIntentForPackage` 실행
      - ⚠️ **`<queries>` 없으면 Android 11+에서 목록이 비어 앱 선택이 통째로 실패한다. 1순위 함정**
- [ ] **5. 노드 필터링 + 요청 전송** — 의미 있는 노드만 추려 session-local id 부여
- [ ] **6. 반복 루프** — 화면 변경 이벤트 500ms debounce, `DONE`이면 종료, 동일 응답 3회 시 강제 중단

---

## 🔗 주요 의존성 흐름
1. **B(Mock 서버, 완료)** → A, C가 지금 바로 통신 테스트 시작 가능
2. **C(카카오톡 UI 덤프)** → B가 실제 프롬프트 개발 시작 가능 ← **현재 최대 병목**
3. **A(통신 모듈 + Types.kt)** → C가 자동화 루프에 결합 가능

## ⚠️ 데모 전 반드시
- [ ] 테스트용 대화방 준비 (실제로 메시지가 전송된다 — 자기 자신과의 채팅 등)
- [ ] 백업 시나리오(텍스트 메시지 전송) 동작 확인
- [ ] 리허설 3회
