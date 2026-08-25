# 작업일지 — 웨이크워드 음성 흐름 / 멀티앱(카카오택시) / UI 정리 (세션 인계용)

- 작성일: 2026-08-26
- 작성 이유: 다른 Claude 계정으로 새 세션을 열어 이어가야 해서, 지금까지 한 일과 현재 상태를
  빠짐없이 남긴다. 이 문서 하나만 읽으면 "뭘 왜 이렇게 했고 지금 뭘 하면 되는지" 알 수 있게
  작성했다.
- 관련 문서: `CLAUDE.md`(안전 원칙/API 계약), `docs/worklog/2026-08-25-member-a-android-bootstrap.md`(초기 실기기 검증), `docs/worklog/2026-08-25-member-a-tts-stt-conversation-loop.md`(TTS-STT 루프 배경)

---

## 1. TL;DR — 지금 상태

**동작 확인된 것**: "안녕 손자" 웨이크워드 → 요청 청취 → 카카오톡 실행 → 화면 읽고 LLM 판단 →
클릭 반복 → 사진 전송까지 실행 → 화면이 원래대로 돌아온 걸 보고 스스로 `DONE` 판단하고 멈춤.
카카오톡 시나리오는 **엔드투엔드로 실기기 검증 완료**.

**막 만들어서 아직 실기기로 못 끝까지 검증한 것**: 카카오택시 지원 확장 + 디바운스
starvation 버그 수정 (아래 §5, §7 참고). 코드는 커밋 안 된 working tree 변경 상태로 남아있음.

**세션 전환 시 반드시 다시 해야 하는 것** (모두 재부팅/재연결하면 풀림):
- 백엔드 서버 실행 (`uvicorn`)
- `adb reverse tcp:8000 tcp:8000`
- 접근성 서비스가 꺼져 있으면 다시 켜기

정확한 명령어는 §8 "재현 체크리스트" 참고.

---

## 2. 이번 세션에서 다룬 순서대로 정리

### 2-1. 실기기 파이프라인 첫 검증 (이미 커밋됨: `d19cdfc` 등, 이전 세션)
카카오톡 화면 읽기 → `/decide` 호출 → 클릭 실행까지의 기본 파이프라인은 이전 세션에서 이미
검증됨. 이번 세션은 그 위에 "자연어 발화로 시작 → 완료까지 자동 종료"를 완성하는 작업이었다.

### 2-2. 백엔드 환경 문제 발견 및 수정
- `google-genai` 패키지가 이 PC의 Python 환경에 실제로 설치돼 있지 않았다 — `GEMINI_API_KEY`가
  `.env`에 있어서 서버가 Mock이 아니라 `GeminiAIClient`를 쓰려 하는데, 그 안에서
  `genai_types.GenerateContentConfig(...)` 호출 시 `AttributeError`가 났다. 즉 **`/decide`를
  실제로 호출하면 매번 에러가 나는 상태**였다. `pip install -r requirements.txt`로 해결.
  (`backend/services/ai_client.py`)

### 2-3. Git 병합 충돌 해결
다른 팀원이 웨이크워드 흐름(`MainActivity`, `WakeAndLaunchActivity`, `WakeTriggerReceiver`,
`WakeWordSettings`)을 올려서 내가 만든 것과 충돌. 팀원 것이 기능적으로 상위호환이라 그쪽을
채택하고, 내가 만든 `GoalHolder`는 삭제(팀원 쪽 `TestAccessibilityService.pendingGoal`이 같은
역할). `<queries>` 매니페스트 수정, `performTargetAction`의 로깅 등 겹치지 않는 내 작업은 유지.
머지 커밋: `aae07b4`.

### 2-4. 카카오톡 패키지 가시성(Package Visibility) 버그
`targetSdk 37`(Android 11+)에서는 `<queries>`에 명시하지 않은 다른 앱은 "설치 안 됨"으로
취급된다(`getLaunchIntentForPackage`가 null 반환). "카카오톡이 설치돼 있지 않습니다" 오류의
원인이었다. `AndroidManifest.xml`에 `<queries><package android:name="com.kakao.talk" /></queries>`
추가로 해결.

### 2-5. 음성 인식(STT/TTS) 버그 3종 — 전부 `VoiceInteractionManager.kt`
실기기 테스트 중 "안녕 손자는 인식되는데 그 다음 요청을 못 받는다"는 증상을 로그로 추적해서
아래 3개의 독립된 버그를 찾아 고쳤다:

1. **TTS 콜백이 메인 스레드가 아님**: `TextToSpeech.UtteranceProgressListener`의 콜백은 TTS
   내부 스레드에서 온다. 그 안에서 바로 `SpeechRecognizer.createSpeechRecognizer()`를 부르면
   `RuntimeException: SpeechRecognizer should be used only from the application's main thread`가
   **조용히(화면에 아무 표시 없이) 터졌다.** `Handler(Looper.getMainLooper()).post()`로 감싸서 해결.
2. **utteranceId 미검증으로 콜백 중복 호출**: `setOnUtteranceProgressListener`는 TTS 인스턴스
   전체에 리스너 하나만 걸리는 API라, 이전 발화가 `QUEUE_FLUSH`로 밀려나며 뒤늦게 오는
   콜백까지 지금 리스너가 받는다. `utteranceId`를 비교 안 해서 `listenOnce()`가 16ms 간격으로
   두 번 불리고, 먼저 연 세션이 거의 즉시 "무음"으로 오판 → 방금 연 진짜 세션까지 같이
   destroy되는 사고로 이어졌다. 발화별 id를 비교해서 남의 콜백은 무시하도록 수정.
3. **마이크 재오픈 레이스**: 이전 recognizer를 `destroy()`한 직후 바로 새로 열면 일부 기기(이
   삼성 실기기 포함)에서 오디오 리소스가 안 풀려서 새 세션이 즉시 "무음"으로 오판됐다. 250ms
   텀(`MIC_REOPEN_DELAY_MS`)을 두고 열도록 수정. 이 지연 콜백이 중복 예약되지 않도록
   `pendingStart` 가드도 추가.

→ 이 세 가지가 겹쳐서 "웨이크워드는 되는데 그 다음 요청을 못 받는다"는 증상으로 나타났었다.

### 2-6. "반복 실행" 버그 — 근본 원인 파악 및 수정 (제일 중요)
전송 버튼을 누르고도 같은 절차(사진 첨부→전송)를 계속 반복 실행하는 사고가 있었다(최소
2번 실제로 중복 전송됨 — 다행히 테스트 계정).

**시도했다가 되돌린 접근**: 클라이언트(Android)에서 "전송"/"보내기" 라벨 버튼을 클릭하면
강제로 세션을 잠그는 하드 스톱(`isSessionLocked`, `confirmCompletion()` 등). 처음엔
`contains()` 매칭이라 "+"(첨부) 버튼의 content-description("사진·동영상 전송")에도 걸려서
**갤러리도 안 연 시점에 완료로 오판**하는 사고가 남; `text/desc == "전송"` 정확 일치로
고쳐서 실제로는 잘 작동했지만, **사용자가 "이 방식 말고 근본 원인을 고치자"고 판단**해서
이 하드 스톱 코드는 전부 삭제(원 상태로 복구)했다. `git diff`로 확인: 지금 남은 차이는
클래스 주석 한 줄과 로그에 `desc=` 필드 추가뿐, 기능 코드는 없음.

**진짜 근본 원인**: `backend/routers/decide.py`의 `_history_summary()`가 클릭이 있었던
스텝의 history를 `"[CONTINUE] node=29 action=CLICK"`처럼 **의미 없는 숫자**로 저장했다.
`target_node_id`는 화면을 스캔할 때마다 1부터 다시 매기는 임시 번호라, 다음 턴의 "node=29"는
완전히 다른 요소를 가리킨다. 즉 **LLM에게 "내가 방금 전송 버튼을 눌렀다"는 사실 자체가
전달되지 않았다.** 게다가 `MAX_HISTORY=3`이라 4~5단계짜리 전송 흐름 중간에 앞 단계가
창밖으로 밀려날 수도 있었다.

**수정 (3곳, 전부 백엔드)**:
- `backend/routers/decide.py`: `_history_summary()`가 `target_node_id` 유무와 무관하게
  항상 LLM이 남긴 자연어 근거(`instruction`, 예: "최원호 님에게 사진을 보내기 위해 전송
  버튼을 클릭합니다")를 담도록 수정.
- `backend/services/session.py`: `MAX_HISTORY` 3 → 5.
- `backend/services/prompt.py`: `SYSTEM_INSTRUCTION` 6번 규칙에 "history에 전송 기록이
  있고 화면이 원래대로 돌아왔으면 DONE" 명시적 지시 추가. `PROMPT_VERSION` v2 → v3.

**검증**: 실제 Gemini 호출로 2턴 시뮬레이션 — 1턴째 "전송" 버튼 클릭, 2턴째 같은 화면(전송
직전 상태로 리셋)을 다시 보내니 `status: "DONE"`, instruction: "이전 단계에서 사진 전송
버튼을 눌렀고 현재 대화방 기본 화면으로 돌아왔으므로 전송이 완료되어 목표를 달성했습니다."
세 가지가 다 같이 있어야 작동한다(history 내용만 고치고 프롬프트 규칙이 없으면 여전히
무시하고 반복하는 것도 실측으로 확인했음).

이 세 커밋에 해당: `f4c879f`, `ddc3944` 근처 (정확한 커밋 대응은 `git log -p`로 확인 필요 —
커밋 메시지가 이 세션에서 직접 붙인 게 아니라 다른 시점에 스쿼시됐을 수 있음).

### 2-7. UI 개선 (커밋: `6b54c90`)
사용자 요청 5가지 반영:
1. `TestAccessibilityService`의 "화면 분석 중… (N개 요소)" 텍스트 제거. 대신 **3초 넘게
   걸리는 요청에만** 스피너 표시(`scheduleAnalyzingIndicator()`/`showAnalyzing()`). 대부분
   요청(2~3초대)은 조용히 지나가서 깜빡임 없음.
2. `StatusOverlayManager`: 각진 회색 배경 → 둥근 모서리 반투명 다크 배경(`GradientDrawable`),
   글씨 14sp → 20sp.
3. 추가 질문(ASK_USER) 후 사용자 답변이 항상 오버레이에 "입력: (답변)"으로 뜨도록
   `askUserWithRetry`의 `onAnswer`에서 표시.
4. `MainActivity` 첫 화면의 "라고 들었어요 (웨이크 문구 아님...)" 설명 문구 제거, 사용자가
   말한 내용만 따옴표로 표시(`main_status_heard_not_wake` 문자열 단순화).
5. `activity_main.xml` 전체 폰트 크기 상향(핵심 상태 텍스트 24sp, 앱 이름 26sp, 라벨/버튼
   16~18sp).

### 2-8. 멀티앱 확장 — 카카오택시 지원 (★ 아직 커밋 안 됨, working tree에 있음)
사용자가 "카카오톡이 3중으로 하드코딩돼 있다"는 원인 설명을 듣고 "카카오택시도 지원하자"고
확장 요청. 4곳 수정:
1. `res/xml/test_accessibility_service_config.xml`: `android:packageNames`를
   `"com.kakao.talk"` → `"com.kakao.talk,com.kakao.taxi"`. **이게 진짜 핵심** — 이 목록 밖
   앱은 OS가 접근성 이벤트 자체를 안 보내준다.
2. `AndroidManifest.xml`: `<queries>`에 `com.kakao.taxi` 추가.
3. `WakeAndLaunchActivity.kt`: `resolveTargetPackage(goal)` 추가 — goal에 "택시"라는
   단어가 있으면 카카오택시, 없으면 기본값 카카오톡. **아주 단순한 키워드 매칭이라
   정식 구현 아님** — "택시 사진 보내줘" 같은 애매한 문장은 오판 가능.
4. `TestAccessibilityService.kt`: 단일 `TARGET_PACKAGE` 상수 → `TARGET_PACKAGES` 집합.
   `app_package`를 하드코딩 대신 실제 이벤트가 들어온 패키지(`currentPackage`)로 백엔드에
   전송. 지원 앱 사이를 이동해도(카카오톡→카카오택시) 새 세션으로 인식하도록
   `packageChanged` 체크 추가.

### 2-9. 디바운스 starvation 버그 발견 및 수정 (★ 아직 커밋 안 됨, 2-8과 같은 diff에 포함)
카카오택시로 실기기 테스트 중 "팝업 닫은 뒤 목적지 입력~결제까지 진행이 안 됨" 증상을 로그로
추적. **원인**: 카카오택시 지도 화면이 초당 5~6번(약 170ms 간격)씩 계속 콘텐츠가 바뀌는데,
`scheduleCollectAndDecide()`의 디바운스가 이벤트마다 500ms 타이머를 계속 리셋하기만 해서
**500ms의 조용한 순간이 영영 안 와 `/decide` 호출이 완전히 멈추는 starvation**이었다.
카카오톡은 화면이 대체로 정적이라 안 드러났던 버그. 로그로 확인: 16초 넘게 이벤트만 오고
서버 호출 0번.

**수정**: "디바운스 + 최대 대기시간" 패턴. `firstEventInBurstAt`으로 burst 시작 시각을
추적해서, `MAX_BURST_WAIT_MS`(1.5초)가 지나면 계속 이벤트가 들어오는 중이어도 지연 0ms로
강제 실행. 정적 화면(카카오톡)에서는 기존과 동일하게 동작.

**미검증**: 이 수정을 넣고 재설치까지는 했지만, 카카오택시로 목적지 입력~결제까지 끝까지
가는 실기기 테스트는 아직 사용자 확인을 못 받은 상태에서 세션 전환 요청이 들어옴.
**다음 세션에서 제일 먼저 할 일.**

---

## 3. 지금 git 상태

```
On branch main, up to date with origin/main

Changes not staged for commit:
  android/app/src/main/AndroidManifest.xml
  android/app/src/main/java/com/example/pathpilot/testkit/TestAccessibilityService.kt
  android/app/src/main/java/com/example/pathpilot/wakeup/WakeAndLaunchActivity.kt
  android/app/src/main/res/xml/test_accessibility_service_config.xml
```

이 4개 파일 = §2-8(카카오택시 지원) + §2-9(디바운스 수정) 내용. **아직 커밋도 push도 안 됨.**
카카오택시 테스트가 끝나고 문제없으면 커밋할 것 (커밋 메시지 예시: `feat: support
KakaoTaxi + fix debounce starvation on live-updating screens`).

최근 원격에 이미 올라간 커밋(과거 세션에서 push):
```
6b54c90 UI 간결수정(폰트크기 키우고 멘트간결)
ddc3944 반복요청성공, 아직 카카오톡만 성공
f4c879f 웨이크문구부터 카톡전송까지 확인(동작루프 해결 못함)
aae07b4 Merge branch 'main' of https://github.com/jhHa0320/2026-unithon
```

---

## 4. 지금 코드가 실제로 하는 일 (아키텍처 요약)

```
사용자: "안녕 손자" (MainActivity가 SpeechRecognizer로 반복 청취 중)
  → 매치되면 "네, 말씀하세요" TTS 후 자동으로 마이크 켬 (VoiceInteractionManager.askAndListen)
  → 사용자 요청 발화를 goal로 캡처
  → WakeAndLaunchActivity 실행: 화면 켜고, goal 보고 카카오톡/카카오택시 중 실행할 앱 결정,
    TestAccessibilityService.pendingGoal에 goal 저장, 앱 실행
  → TestAccessibilityService가 그 앱 창을 감지 → pendingGoal 소비해서 세션 시작
  → 화면 요소 스캔(ElementDTO 목록) → POST /api/v1/decide (goal, app_package, elements, history)
  → 백엔드: 민감정보 마스킹 → 세션 history 조회 → Gemini 호출 → confidence 게이트 →
    응답 검증 → history 갱신(이번 스텝 instruction 저장) → 응답 반환
  → CONTINUE면 target_node_id 클릭/입력 실행, 화면 바뀌면 반복
  → DONE이면 TTS로 완료 안내, 세션 종료(대기 상태로)
```

---

## 5. 알려진 이슈 / 미해결 항목

| 항목 | 상태 |
|---|---|
| 카카오택시 목적지 입력~결제 전체 흐름 | **미검증** — 디바운스 수정 후 재테스트 필요 (최우선) |
| 앱 선택 로직("택시" 키워드 매칭) | 단순 휴리스틱, 오탐 가능성 있음 |
| 완료 후 "잘 됐나요?" 확인 질문 STT 실패 시 재시도 없음 | 한 번 물어보고 응답 인식 실패하면 바로 "확인했습니다"로 종료. 되묻기(`askUserWithRetry`)처럼 재시도 로직 추가 제안했었으나 미착수 (사용자가 완료 감지 하드스톱 자체를 되돌리면서 이 질문도 같이 없어짐 — 지금은 서버 DONE 판단에만 의존, §2-6 참고) |
| 실시간 웨이크워드(화면 꺼짐/백그라운드 상태) | 미구현 — `MainActivity`가 포그라운드에 떠 있을 때만 웨이크워드 청취 (`onPause`에서 `stopWakeListening()`) |
| `WakeTriggerReceiver` | 디버그 전용 테스트 트리거, 실제 웨이크워드 감지로 교체 필요(설계 미정) |
| 로컬 Python 환경에 `google-genai` 재설치 필요 여부 | 이 세션에서 설치함(§2-2). 새 세션이 다른 venv/환경이면 다시 설치 필요할 수 있음 |

---

## 6. 재현/이어서 테스트하는 방법

### 6-1. 백엔드
```bash
cd /c/users/WONHO/2026-unithon
python -m uvicorn backend.main:app --host 0.0.0.0 --port 8000
```
`GEMINI_API_KEY`는 `.env`에 이미 있음(비어있으면 Mock으로 폴백하니 확인). `pip install -r
requirements.txt` 안 했으면 먼저 실행(§2-2 문제 재발 방지).

### 6-2. 안드로이드 빌드/설치
JDK 21 경로가 `JAVA_HOME` 기본값과 다르면 매번 지정해야 함(이 PC 기준):
```bash
cd /c/users/WONHO/2026-unithon/android
JAVA_HOME="/c/Program Files/Java/jdk-21.0.10" ./gradlew.bat installDebug --console=plain
```

### 6-3. adb 관련 (Git Bash에서 경로 자동변환 주의 — `/F`, `/PID`, `/sdcard` 등은
`export MSYS_NO_PATHCONV=1` 먼저 걸 것)
```bash
export MSYS_NO_PATHCONV=1
ADB="/c/Users/WONHO/AppData/Local/Android/Sdk/platform-tools/adb.exe"

"$ADB" devices                          # 실기기 연결 확인 (SM-S911N, R3CWA0J3J8Z)
"$ADB" reverse tcp:8000 tcp:8000         # USB 케이블 뽑으면 매번 다시 걸어야 함
"$ADB" shell dumpsys accessibility | grep "Enabled services"   # pathpilot 서비스 켜져 있는지 확인
```

접근성 서비스가 꺼져 있으면(재설치하면 꺼질 수 있음) 아래로 강제로 켤 수 있음(다른 계정
서비스도 같이 나열해줘야 덮어쓰지 않음 — 지금 이 폰엔 `com.fluiz.fluizgpt`,
`ai.fluiz.ditestbed_agent` 접근성 서비스가 이미 켜져 있음, 그대로 유지):
```bash
"$ADB" shell settings put secure enabled_accessibility_services \
"com.fluiz.fluizgpt/com.fluiz.seoulai.FluidGPTAccessibilityService:ai.fluiz.ditestbed_agent/ai.fluiz.ditestbed_agent.FluidGPTAccessibilityService:com.example.pathpilot/com.example.pathpilot.testkit.TestAccessibilityService"
```

### 6-4. 로그 확인
```bash
"$ADB" logcat -c   # 테스트 직전에 비우기
# ... 폰에서 테스트 ...
"$ADB" logcat -d -s TestA11yService:* VoiceInteraction:*
```
백엔드 판단 과정은 `/tmp/uvicorn.log`(이 세션 기준 경로) 또는 서버를 띄운 터미널 출력에서
`decide request processed` 라인으로 확인 (session_id, target_node_id, status, latency_ms 등).

### 6-5. 앱 실행
```bash
"$ADB" shell am start -n com.example.pathpilot/.MainActivity
```
화면에 "웨이크 문구 대기 중" 뜨면 "안녕 손자" 발화 → "네, 말씀하세요" → 요청 발화.

---

## 7. 다음 세션에서 할 일 (우선순위 순)

1. **카카오택시 재테스트** — "카카오택시로 OO역까지 택시 불러줘"로 목적지 입력~호출~결제까지
   끝까지 진행되는지 확인 (§2-9 디바운스 수정 검증).
2. 문제없으면 §3의 미커밋 변경사항 커밋 + push.
3. 카카오택시 화면에서 LLM이 실제로 잘 판단하는지(전용 프롬프트 튜닝 필요 여부) 확인 —
   지금 프롬프트는 도메인 중립이라 이론상은 되지만 실측 안 됨.
4. (선택) 완료 확인 질문 STT 재시도 로직 추가 여부 재논의 — §5 표 참고.
