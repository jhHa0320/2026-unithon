# 2026-unithon — 음성 앱 대행 에이전트

2026 유니톤 23팀 프로젝트

**어르신이 앱 하나만 켜고 말하면, AI가 알맞은 앱을 스스로 찾아 열고 목표를 끝까지 수행한다.**
되돌릴 수 없는 행동(전송·결제·삭제) 직전에만 구두로 동의를 구하고, 동의를 받으면 그 버튼까지 AI가 누른다.

해커톤 데모 시나리오: **"영희한테 카톡으로 방금 찍은 사진 보내줘"**
→ 카카오톡 실행 → 친구 검색 → 대화방 → 사진 첨부 → 최근 사진 선택 → 구두 동의 → 전송

> 카카오톡은 데모 사례일 뿐이다. 앱 이름은 코드 어디에도 없고, 어떤 앱을 열지조차 AI가 판단한다.

## 문서 (읽는 순서)

| 문서 | 내용 |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | **규칙·API 계약의 최종 소스.** 다른 문서와 충돌하면 이게 이긴다 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | 시스템 구조, E2E 워크스루, 구현 Phase, 알려진 갭 |
| [`docs/planning/`](docs/planning/) | PRD·제품설명서·경쟁분석·BM·기술구현 가이드 |
| [`dumps/API_SPEC.md`](dumps/API_SPEC.md) | `/api/v1/decide` 실제 JSON 예제 |
| [`dumps/TODO.md`](dumps/TODO.md) | 멤버별 체크리스트 (10시간 스코프) |

## 실행

```bash
pip install -r requirements.txt
uvicorn backend.main:app --reload --port 8000   # 세션이 메모리에 있으므로 단일 워커
pytest backend/tests
```

API 키 없이도 `MockAIClient`로 동작하므로 Android 개발은 지금 바로 시작할 수 있다.
`.env`에 `GEMINI_API_KEY`를 넣고 재시작하면 실제 판단으로 바뀐다 (키가 있는 사람만 하면 된다).

```bash
python -m backend.dev.check_llm
```
키를 넣은 뒤 이걸 돌리면 실제로 한 번 호출해서 되는지 30초 안에 알려준다.

### 개발용 하네스 — http://localhost:8000/dev

브라우저에서 가상 폰 화면을 실제 `/api/v1/decide`에 흘려보내며 에이전트 루프를 눈으로 확인한다.
어떤 스텝이 **규칙으로 처리**되고 어떤 스텝이 **LLM을 호출**했는지, `ASK_USER`·`CONFIRM_REQUIRED`
게이트가 언제 뜨는지 그대로 보인다. 실기기 `uiautomator dump` XML을 붙여넣어 그 화면으로
판단시켜 볼 수도 있어 프롬프트 튜닝 도구로 쓴다. Android가 못 끝났을 때 데모 대비책이기도 하다.

Android 실행 명령어는 Android 담당자가 채울 것 (`CLAUDE.md` §10).

## 구조

```
backend/     FastAPI — /api/v1/decide, 안전 게이트, 세션, LLM 연동
android/     Kotlin — 마이크/STT/TTS, AccessibilityService, 자동 조작
docs/        아키텍처 + 기획 문서
dumps/       UI Tree 덤프, API 예제, TODO
```
