# 작업일지 — 멤버 A(Infra/Voice/UX) TTS-STT 대화 로직(§5-1 되묻기) + 화면 꺼짐 웨이크업 구현

- 날짜: 2026-08-25
- 담당: 멤버 A (`dumps/TODO.md` 기준 — Infra/Voice/UX), 이어서 작업
- 상태: **코드 작성 및 정독 검증 완료. 실기기/Gradle 빌드 검증은 못 함(이 세션 환경 제약, §4 참고). 커밋 전.**
- 이전 작업: `2026-08-25-member-a-android-bootstrap.md` (같은 날 앞서 작성) — `VoiceInteractionManager`/`TestAccessibilityService` 최초 구현 및 실기기 검증까지 마친 상태에서 이어받음.
- 이 문서는 같은 세션 안에서 두 단계로 나눠 작업한 걸 함께 기록한다: (1) §1~4 TTS-STT 대화 로직, (2) §5 화면 꺼짐 상태 웨이크업.

---

## 0. API 키 필요 여부

**필요 없음.** 이번 작업은 Android 클라이언트의 TTS(`android.speech.tts.TextToSpeech`)/STT(`android.speech.SpeechRecognizer`)만 다뤘고, 둘 다 API 키 없이 동작하는 OS 내장 기능이다. 이 프로젝트에서 키가 필요한 유일한 지점은 백엔드의 `GEMINI_API_KEY`(`backend/config.py`)인데, 이번 작업은 그쪽을 건드리지 않았다.

---

## 1. TL;DR

부트스트랩 세션에서 TTS/STT 배관(`VoiceInteractionManager`)과 `ASK_USER` 되묻기 루프(`TestAccessibilityService`)까지는 이미 동작했지만, `CLAUDE.md` §5-1이 요구하는 **"답변이 정보 제공형이면 `goal`에 영구 누적, 예/아니오 확인이면 `user_speech`로 일회성 전달"** 분기가 빠져 있었다(항상 `user_speech`로만 보내서 `goal`이 절대 안 바뀌는 상태). 이번 세션에서 그 분기와, 검증 과정에서 같이 드러난 부수 문제 세 가지(TTS 재생 중 마이크가 켜지는 경합, STT 실패 시 재시도 없음, 무한 되묻기 방지 없음, 마이크 권한 미확인)를 함께 고쳤다.

---

## 2. 변경 파일

```
수정 (§1~4, TTS-STT 대화 로직):
  android/app/src/main/java/com/example/pathpilot/testkit/TestAccessibilityService.kt
  android/app/src/main/java/com/example/pathpilot/voice/VoiceInteractionManager.kt

수정/신규 (§5, 화면 꺼짐 웨이크업):
  android/app/build.gradle.kts                                                          (수정 — buildFeatures.buildConfig = true)
  android/app/src/main/AndroidManifest.xml                                              (수정 — 권한/컴포넌트 등록)
  android/app/src/main/java/com/example/pathpilot/testkit/TestAccessibilityService.kt   (수정 — pendingGoal, startSessionAndCaptureGoal, isAwaitingGoal 추가, 위와 중복)
  android/app/src/main/java/com/example/pathpilot/wakeup/WakeAndLaunchActivity.kt        (신규)
  android/app/src/main/java/com/example/pathpilot/wakeup/WakeTriggerReceiver.kt          (신규 — BuildConfig.DEBUG 게이트 포함)

문서:
  docs/worklog/2026-08-25-member-a-tts-stt-conversation-loop.md (이 문서, 신규)
```

## 3. 무엇을, 왜 고쳤나

### 3-1. `goal` / `user_speech` 분기 (§5-1 핵심 갭)

- `classifyAnswer(text)`: STT 결과가 짧은 예/아니오류 확정 응답 집합(`CONFIRMATION_ANSWERS` — "응/네/맞아/좋아/아니/취소/그만" 등)에 **정확히 일치**할 때만 `CONFIRMATION`, 그 외엔 전부 `INFO`로 분류.
- `routeAnswer(answer)`: `CONFIRMATION`이면 `collectAndDecide(userSpeech = answer)`(그 턴만), `INFO`면 `goal = "$goal. $answer"`로 이어붙인 뒤 `collectAndDecide(userSpeech = null)`.
- 이건 클라이언트 측 휴리스틱이라 "아니 엄마 말고 아빠한테" 같은 부정어로 시작하는 정보성 답변은 오분류 여지가 있음(전체 문자열이 확정 응답 집합에 없으므로 INFO로 분류되긴 함 — 정확 일치 방식이라 이 케이스는 실제로 안전함). 다만 근본적으로 "이 질문이 예/아니오형인지 개방형인지"는 질문을 만든 LLM만 정확히 아므로, 오작동이 관찰되면 `DecideResponse`에 `expects_confirmation` 같은 필드를 백엔드가 내려주는 방식으로 바꾸는 걸 검토할 것(백엔드 스키마 변경이라 팀 협의 필요, `CLAUDE.md` §12).

### 3-2. TTS 재생 중 마이크가 켜지는 경합 수정

- 기존 `handleResponse`는 `ASK_USER`일 때 `voice.speak(voice_message)`를 먼저 호출하고 곧바로 `voice.listenOnce()`를 호출했다. `speak()`는 비동기라 TTS 재생이 끝나기 전에 `listenOnce()`가 실행돼, 스피커로 나가는 질문 음성을 마이크가 주워들을 수 있는 구조였다.
- `askUserWithRetry()`로 바꿔서 `voice.askAndListen()`(TTS `onDone` 콜백 이후에만 STT 시작, 부트스트랩 세션에서 이미 만들어 뒀던 함수)을 쓰도록 통일.

### 3-3. STT 인식 실패 시 재시도

- 기존엔 인식 실패(`onError`) 시 오버레이 텍스트만 바뀌고 대화가 끊겼다. `askUserWithRetry`에 재귀 재시도를 넣어 같은 질문을 최대 `MAX_ASK_RETRIES`(3)번까지 다시 묻는다.

### 3-4. 무한 되묻기 방지

- `consecutiveAskUserCount`를 추가해 `ASK_USER`가 연속으로 `MAX_CONSECUTIVE_ASK_USER`(5)회를 넘으면 세션을 강제 종료하고 오버레이로 안내. `CONTINUE`/`DONE`/`UNSUPPORTED` 응답이나 새 세션 시작 시 리셋.

### 3-5. 마이크 권한 미확인 크래시 가능성

- `PermissionActivity`는 접근성/마이크/오버레이 3개 권한을 **독립적으로** 켜고 끌 수 있는 화면이라, 접근성만 켜고 마이크는 안 켠 상태에서 `listenOnce()`가 불릴 수 있음을 발견. `VoiceInteractionManager.listenOnce()` 맨 앞에 `ContextCompat.checkSelfPermission(RECORD_AUDIO)` 체크를 추가해, 권한 없으면 `SecurityException` 대신 `onError` 콜백으로 안전하게 빠지도록 함.

---

## 4. 검증 — 못 한 부분과 이유

이번 세션은 **부트스트랩 세션과 다른 환경**(Bash 툴, 실기기 미연결)에서 진행돼 실기기 검증을 하지 못했다. 대신 정적 검토와 컴파일 시도를 했고, 어디서 막혔는지 그대로 남긴다.

1. `JAVA_HOME`이 `C:\Program Files\Java\jdk-24`로 설정돼 있는데 실제로는 존재하지 않음(설치된 건 `jdk-25`). `JAVA_HOME` 오버라이드로 우회.
2. 프로젝트 경로에 비-ASCII(한글) 문자가 있어 AGP가 기본적으로 빌드를 거부함. `-Pandroid.overridePathCheck=true`로 우회(파일은 안 건드림, 커맨드라인 한정 적용).
3. 이 머신엔 `android/local.properties`가 없고 `ANDROID_HOME=C:\Android\Sdk`도 실제로는 존재하지 않는 경로 — Android SDK 자체가 이 환경에 없어서 `compileDebugKotlin`까지 못 감. (부트스트랩 세션 §6-1 기록을 보면 실기기 테스트 당시엔 `C:\Users\WONHO\AppData\Local\Android\Sdk`를 썼다 — 이 세션 환경과 다른 PC/설정으로 보임.)
4. 대신 변경한 두 파일을 전문 재독하며 시그니처·타입 정합성(예: `voice_message: String`이 non-null이라 `.isNotBlank()` 안전, `askAndListen`/`listenOnce` 콜백 시그니처 일치, `DecideRequest.user_speech` 주석이 이번에 구현한 분기와 문자 그대로 일치)을 확인함.

**→ 다음 세션(또는 실기기가 연결된 환경)에서 Android Studio나 `./gradlew assembleDebug`로 실제 빌드 + §9 체크리스트(부트스트랩 문서 참고)로 실기기 재검증 필요.**

---

## 5. 화면 꺼짐 상태 웨이크업 — "깨우기 → 카카오톡으로 이동 → accessibility 로직 시작"

사용자 요청: 화면이 꺼져 있어도 카카오톡(대상 앱 한정)으로 화면을 켜고 이동한 뒤, 그 다음부터는 기존 `TestAccessibilityService` 파이프라인이 이어받도록 구현.

### 5-1. 왜 이게 필요했나

기존 파이프라인은 `onAccessibilityEvent`가 **카카오톡이 이미 화면에 떠 있을 때만** 발동한다. 화면이 꺼져 있으면 카카오톡이 background로 내려가 이벤트 자체가 안 온다. 그래서 "화면을 켜고 카카오톡을 실행하는" 단계를 앞에 하나 추가해야 기존 로직이 이어받을 수 있다.

### 5-2. 새로 만든 것

| 파일 | 역할 |
|---|---|
| `wakeup/WakeAndLaunchActivity.kt` | UI 없는 중계 Activity. `onCreate`에서 `PARTIAL_WAKE_LOCK`을 짧게(10초) 잡고, API 27+에선 `setShowWhenLocked`/`setTurnScreenOn`+`KeyguardManager.requestDismissKeyguard`, API 26 폴백은 `WindowManager` 플래그(`FLAG_SHOW_WHEN_LOCKED`/`FLAG_TURN_SCREEN_ON`/`FLAG_DISMISS_KEYGUARD`)로 화면을 켠 뒤, 카카오톡 실행 인텐트를 쏘고 스스로 `finish()` |
| `wakeup/WakeTriggerReceiver.kt` | adb로 이 흐름을 눌러보기 위한 테스트용 `BroadcastReceiver`. `adb shell am broadcast -a com.example.pathpilot.action.WAKE_AND_LAUNCH_KAKAO --es goal "..."` |

`TestAccessibilityService`에는 `@Volatile companion var pendingGoal: String?`을 추가했다. `WakeAndLaunchActivity`가 카카오톡을 띄우기 직전에 여기 goal을 세팅해두면, 다음 `onAccessibilityEvent`가 새 세션을 열 때 `pendingGoal ?: DEFAULT_GOAL`로 소비하고 즉시 null로 되돌린다. 같은 프로세스 안에서만 오가는 값이라 Intent extra 대신 정적 필드로 넘겼다.

Manifest에 추가한 것: `WAKE_LOCK` 권한, `WakeAndLaunchActivity`(`exported=false`, `excludeFromRecents`, `singleInstance`, `taskAffinity=""`, 반투명 테마), `WakeTriggerReceiver`(**임시로 `exported=true`** — adb 테스트용, PermissionActivity 때와 같은 패턴).

### 5-3. 알고 있어야 할 한계 — 반드시 읽을 것

1. **보안 잠금은 우회 못 한다.** 기기가 PIN/패턴/생체인증으로 잠겨 있으면 화면 위에 카카오톡을 띄우는 것까지만 되고, 실제 잠금 해제는 사용자가 직접 해야 한다. Android가 의도적으로 막아둔 부분이라 우회 방법 자체가 없다(`CLAUDE.md` §4-2 "보안 통제 우회 금지"와도 방향이 일치). 잠금 화면 위에서 실제로 클릭 자동화까지 되는지는 실기기(잠금 켠 상태)로 검증 필요 — 기종/OS 버전별로 동작이 갈릴 수 있다. **→ 후속 작업: 실기기 검증만 남음, 코드로 더 할 수 있는 게 없다.**
2. **"화면 꺼짐 상태에서 이 흐름을 맨 처음 누가 시작시키는가"는 여전히 미해결.** `WakeTriggerReceiver`는 adb 테스트용일 뿐이고, 상시 웨이크워드 감지 등 진짜 트리거는 이번 작업 범위 밖이다. 다만 이 열린 구멍 자체(다른 앱이 브로드캐스트로 화면을 켤 수 있는 위험)는 §5-3-1에서 `BuildConfig.DEBUG` 게이트로 이미 막았다. **→ 후속 작업: 진짜 트리거 방식을 정하는 건 팀 전체 설계 논의가 더 필요 (웨이크워드 엔진 도입 여부, 배터리/권한 트레이드오프).**
3. **실기기 미검증.** §4와 같은 이유(이 세션엔 Android SDK 없음)로 빌드/실행을 못 해봤다. 특히 `KeyguardManager.requestDismissKeyguard`, API 26 폴백 플래그, 그리고 아래 §5-3-2에서 새로 넣은 `isAwaitingGoal` 경합 방지 로직은 기종별 차이나 타이밍 이슈가 있을 수 있는 영역이라 실기기 검증이 특히 중요하다. **→ 후속 작업: 실기기 확보되면 §5-4 체크리스트로 검증.**

### 5-3-1. 보안 구멍 완화 — `WakeTriggerReceiver` release 빌드 무력화

위 2번 한계 중 "지금 당장 코드로 막을 수 있는 부분"만 먼저 처리했다. `exported=true`인 채로 팀 통합하면 다른 임의의 앱이 이 브로드캐스트를 보내 화면을 켜고 카카오톡 자동화를 시작시킬 수 있는데, signature 권한으로 잠그면 adb shell도 같이 막혀 테스트가 안 되는 문제가 있었다. 대신 `WakeTriggerReceiver.onReceive()`에 `if (!BuildConfig.DEBUG) return` 가드를 넣어 **릴리스 빌드에서는 스스로 무력화**되도록 했다(`android/app/build.gradle.kts`에 `buildFeatures { buildConfig = true }` 추가 필요했음 — AGP 9.x부터 기본값이 꺼져 있음). adb 테스트는 디버그 빌드 그대로 계속 가능하다.

### 5-3-2. 트리거 설계 관련 추가 논의 및 반영 — "무엇을 도와드릴까요?" 즉시 청취

한계 2번("진짜 트리거가 없음")을 사용자와 논의한 결과, 상시 웨이크워드 감지 같은 무거운 기능 대신 **"앱(카카오톡)이 뜬 직후 바로 마이크가 켜져서 목표를 되묻는"** 방식으로 결정. 구현 방식 변경:

- `TestAccessibilityService.onAccessibilityEvent`가 새 세션을 열 때 더 이상 `DEFAULT_GOAL`을 바로 쓰지 않고, `startSessionAndCaptureGoal()`을 호출한다.
- `startSessionAndCaptureGoal()`: `pendingGoal`이 미리 세팅돼 있으면(adb 테스트 등으로 goal을 이미 아는 경우) 그대로 쓰고, 없으면(보통의 실사용 경로) `voice.askAndListen("무엇을 도와드릴까요?")`로 목표를 직접 받는다. STT까지 실패하면 그때만 `DEFAULT_GOAL`로 fallback.
- **경합 방지**: "무엇을 도와드릴까요?" 답변을 기다리는 동안 카카오톡 스플래시/전환 애니메이션 때문에 추가 accessibility 이벤트가 계속 들어오는데, 이걸 그대로 두면 아직 안 정해진 `goal`로 `collectAndDecide`가 먼저 실행돼버릴 수 있었다. `isAwaitingGoal` 플래그를 추가해 목표를 기다리는 동안엔 화면 스캔을 막았다. 사용자가 카카오톡을 벗어나면(`isAwaitingGoal`이 true인 채로) `voice.stopListening()`으로 마이크도 정리한다.
- 이 변경으로 `WakeTriggerReceiver`의 `EXTRA_GOAL`은 필수가 아니라 "미리 알고 있으면 생략 가능한 옵션"이 됐다 — 실제 웨이크업 트리거가 나중에 뭐가 되든, goal을 몰라도 앱만 띄우면 알아서 물어보므로 트리거 쪽 구현 부담이 줄었다.

### 5-4. 다음 세션 테스트 방법 (실기기 확보되면, 반드시 디버그 빌드로)

1. **goal을 미리 아는 경로**: 화면을 끄거나 잠근 상태에서 `adb shell am broadcast -a com.example.pathpilot.action.WAKE_AND_LAUNCH_KAKAO --es goal "엄마한테 사진 보내줘"` → 화면이 켜지고 카카오톡이 뜨는지, 오버레이에 "테스트 시작: 엄마한테 사진 보내줘"가 뜨는지(`pendingGoal` 소비 확인).
2. **goal 없이(실사용 경로)**: `adb shell am broadcast -a com.example.pathpilot.action.WAKE_AND_LAUNCH_KAKAO`(goal 생략) → 화면이 켜지고 카카오톡이 뜬 직후 "무엇을 도와드릴까요?"가 TTS로 나오고 마이크가 켜지는지, 답변한 내용이 goal로 잡히는지 확인. 답변 대기 중 화면이 계속 바뀌어도 `collectAndDecide`가 먼저 실행되지 않는지(`isAwaitingGoal` 가드) 로그로 확인.
3. 잠금 켠 상태/안 켠 상태 둘 다 테스트해서 §5-3의 1번(잠금 화면 동작 차이)을 기록해둘 것.
4. 릴리스 빌드로도 한 번 설치해서 같은 브로드캐스트를 보냈을 때 **아무 일도 안 일어나는지**(§5-3-1의 `BuildConfig.DEBUG` 게이트) 확인.

---

## 6. 다음 세션이 볼 것

- [ ] 실기기에서 "사진 보내줘"(정보 부족) → "엄마한테요"(INFO, `goal` 누적 확인) → 후보 여럿일 때 "응 그걸로"(CONFIRMATION, `user_speech` 확인) 시나리오로 goal 누적이 실제로 되는지 로그(`adb logcat` 또는 서버 로그의 `goal` 필드)로 확인.
- [ ] `CONFIRMATION_ANSWERS` 휴리스틱이 실제 되묻기 질문들과 잘 맞는지 — 안 맞으면 §3-1에 적은 대로 백엔드가 질문 타입을 내려주는 방식 전환 논의.
- [ ] §5-4 테스트 절차대로 실기기(잠금/비잠금, 디버그/릴리스 빌드)로 웨이크업 흐름 전체 검증.
- [ ] "화면 꺼짐 상태에서 이 흐름을 맨 처음 누가 시작시키는가"(진짜 트리거)는 아직 미해결 — 팀과 설계 논의 필요(웨이크워드 엔진 도입 여부, 배터리/권한 트레이드오프). 목표 문장 자체는 이제 트리거가 몰라도 되므로(§5-3-2) 트리거 쪽은 "언제 깨울지"만 결정하면 됨.
- [ ] 아직 git add/commit 안 함.

---

## 7. 관련 문서

- 앞선 작업: `docs/worklog/2026-08-25-member-a-android-bootstrap.md`
- 안전 원칙/되묻기 규칙 정본: `CLAUDE.md` §4-1, §5-1
