# 작업일지 — 멤버 A(Infra/Voice/UX) Android 부트스트랩 + 실기기 검증

- 날짜: 2026-08-25
- 담당: 멤버 A (`dumps/TODO.md` 기준 — Infra/Voice/UX)
- 상태: **코드는 실기기에서 동작 검증 완료. 아직 git 커밋 전(미푸시).**
- 다른 세션이 이어받을 때 이 문서 하나만 읽으면 "무엇을 왜 이렇게 했는지 + 지금 뭘 하면 되는지"가 다 나오도록 작성했다.

---

## 1. TL;DR

`android/`가 빈 템플릿이던 상태에서, 멤버 A 담당 범위(권한 화면, 네트워킹, 음성 TTS/STT, 상태 오버레이)를 구현하고, 거기에 더해 **정식 AccessibilityService(멤버 C 담당)가 아직 없어도 파이프라인을 실기기에서 눈으로 검증할 수 있는 테스트용 AccessibilityService**를 만들어 실제 폰 + 실제 카카오톡 + 실제 백엔드로 end-to-end 동작을 확인했다.

최종적으로 확인된 것: **폰이 카카오톡 화면을 실제로 읽고 → 백엔드에 실제로 HTTP 요청을 보내고 → 백엔드 응답에 따라 실제로 화면을 클릭하고 → 화면이 바뀌면 자동으로 반복한다.** (LLM은 아직 Mock이라 "똑똑한 목표 수행"은 안 되고 "규칙 기반 첫 클릭 가능 요소 클릭"만 된다 — 이건 의도된 현재 상태다.)

---

## 2. 배경 — 이 작업 전 상태

- `docs/ARCHITECTURE.md`, `dumps/API_SPEC.md`(v2.0), `dumps/TODO.md`가 이미 존재. 팀 3인 역할: 멤버 A(Infra/Voice/UX), 멤버 B(AI/Backend), 멤버 C(Auto/Action).
- 백엔드(`backend/`)는 이미 v2.0 스키마로 동작 중: `action_type`(CLICK/SET_TEXT), `input_value`, `voice_message`, `user_speech` 필드가 확정 상태. LLM은 아직 `MockAIClient`(규칙 기반: 클릭 가능한 첫 요소를 클릭, EditText면 "서울" 입력, `user_speech`로 긍정/부정 판정)로 대체돼 있음.
- `android/`는 Android Studio 기본 템플릿(`com.example.pathpilot`, `MainActivity.kt`가 "Hello World" 뿐)만 있던 상태.

---

## 3. 이번에 만든 것 — 파일별 정리

모두 `android/app/src/main/java/com/example/pathpilot/` 아래, **기존 파일은 건드리지 않고 새 패키지만 추가**하는 방식으로 작업해서 다른 담당자 작업과 충돌 여지를 최소화했다.

| 파일 | 역할 |
|---|---|
| `model/Types.kt` | `ElementDTO`, `HistoryEntry`, `DecideRequest`, `DecideResponse`, `DecideStatus`, `ActionType`. 백엔드 `dumps/API_SPEC.md`(v2.0)와 필드명 1:1(snake_case 그대로, Gson이 매핑) |
| `network/ApiService.kt` | Retrofit 인터페이스, `POST api/v1/decide` |
| `network/RetrofitClient.kt` | OkHttp+Gson 기반 싱글턴 클라이언트. `BASE_URL` 하드코딩 — §6 "환경별 접속 설정" 참고 |
| `voice/VoiceInteractionManager.kt` | TTS 재생 → 끝나면 자동으로 STT 시작하는 `askAndListen()`. `ASK_USER` 되묻기(`CLAUDE.md` §5-1)에 재사용 목적 |
| `overlay/StatusOverlayManager.kt` | `TYPE_APPLICATION_OVERLAY` 창으로 진행상황 텍스트 표시. Mock 결제 화면이 아니라 순수 상태 안내용 |
| `ui/permission/PermissionActivity.kt` + `res/layout/activity_permission.xml` | Accessibility/Microphone(RECORD_AUDIO)/Overlay(SYSTEM_ALERT_WINDOW) 3종 권한 안내·요청·상태 표시 화면 |
| `testkit/TestAccessibilityService.kt` + `res/xml/test_accessibility_service_config.xml` | **테스트 전용** — 카카오톡(`com.kakao.talk`)만 대상으로 실제 화면 읽기+클릭 실행. 상세는 §5 |

### 공유 파일에 한 변경 (추가만 함)

- `AndroidManifest.xml`: `INTERNET`/`RECORD_AUDIO`/`SYSTEM_ALERT_WINDOW` 권한, `PermissionActivity` 및 `TestAccessibilityService` `<service>` 등록, `android:networkSecurityConfig` 참조 추가
- `gradle/libs.versions.toml`, `app/build.gradle.kts`: Retrofit, OkHttp(logging-interceptor), Gson, kotlinx-coroutines-android, lifecycle-runtime-ktx 의존성 추가 (이전엔 네트워킹 라이브러리가 하나도 없었음)
- `res/values/strings.xml`: 권한 화면/테스트 서비스용 문자열 추가
- `res/xml/network_security_config.xml` (신규): 개발 서버 대상 cleartext(평문 HTTP) 허용

---

## 4. `MainActivity.kt`는 의도적으로 안 건드림

앱 진입점이라 B/C도 손댈 가능성이 있어서, `PermissionActivity`/`TestAccessibilityService`로의 배선(예: 앱 시작 시 권한 화면으로 이동)은 하지 않았다. 지금은 전부 `adb shell am start` 등으로 직접 실행해서 테스트했다. **팀과 상의 후 마지막에 한 커밋으로 배선할 것.**

---

## 5. `TestAccessibilityService`란 무엇이고 왜 만들었나

멤버 C가 담당할 정식 AccessibilityService(`service/` 패키지)가 아직 없어서, 멤버 A가 만든 네트워킹/음성/오버레이 레이어가 실기기에서 실제로 동작하는지 검증할 방법이 없었다. 그래서 **"화면 읽기 → /decide 호출 → 클릭 실행 → 반복"의 최소 구현**을 직접 만들어 카카오톡 하나만 대상으로 붙여봤다.

- 카카오톡(`com.kakao.talk`)에만 반응하도록 `res/xml/test_accessibility_service_config.xml`에서 `packageNames` 제한 — 실수로 다른 앱(특히 실결제가 자동 실행되는 코레일+ 앱)에 걸리지 않게 하기 위한 안전장치.
- 목표 문장은 하드코딩: `"카카오톡에서 가장 최근에 찍은 사진 보내줘"` (companion object의 `DEFAULT_GOAL`).
- **멤버 C가 정식 서비스를 만들면 이 파일들(`testkit/TestAccessibilityService.kt`, `res/xml/test_accessibility_service_config.xml`, Manifest의 관련 `<service>` 블록)은 삭제 대상.** 코드 자체에도 이 안내를 주석으로 남겨뒀다.

### 알려진 한계 (테스트용이라 감수한 것)

- `nodeMap`(id → `AccessibilityNodeInfo`)은 서버 응답을 기다리는 사이 화면이 바뀌면 무효화될 수 있음. `performAction`이 조용히 실패하면 원인일 가능성 높음.
- 세션은 카카오톡에 들어올 때마다 새로 시작되고 목표 문장은 고정.

---

## 6. 실기기 테스트 세션 — 문제와 해결 (연대순, 제일 중요한 부분)

폰(Samsung SM-S911N, USB 연결)에 실제로 설치해서 카카오톡으로 테스트하는 과정에서 총 5번의 재빌드가 필요했다. 다음 세션에서 같은 삽질을 반복하지 않도록 원인과 해결을 그대로 남긴다.

### 6-1. Gradle: `SDK location not found`
- 원인: `android/local.properties`가 없었음(당연히 `.gitignore` 대상이라 커밋된 적 없음).
- 해결: `sdk.dir=C:\Users\WONHO\AppData\Local\Android\Sdk`로 새로 생성.
- **다른 PC/세션에서는 각자의 SDK 경로로 새로 만들어야 한다.**

### 6-2. `PermissionActivity`를 adb로 못 띄움
- 증상: `adb shell am start -n .../.ui.permission.PermissionActivity` → `SecurityException: not exported`.
- 원인: `android:exported="false"`였는데, 최신 OS(이 폰 기준)에서는 adb shell도 non-exported 액티비티를 못 띄움.
- 해결: **임시로** `android:exported="true"`로 변경. 코드에 "adb 테스트용 임시 설정, 팀 통합 시 false로 되돌릴 것" 주석 남김. **→ §8 정리 필요 항목.**

### 6-3. 평문 HTTP(cleartext) 차단
- 증상: `java.net.UnknownServiceException: CLEARTEXT communication to <IP> not permitted by network security policy`
- 원인: Android 9+ 기본 정책. 로컬 개발 서버가 아직 HTTPS가 아님.
- 해결: `res/xml/network_security_config.xml` 신설, 개발 서버 주소만 `cleartextTrafficPermitted="true"` 예외 처리. Manifest에 `android:networkSecurityConfig` 연결.

### 6-4. Windows 방화벽이 8000번 포트를 막음
- 증상: 첫 시도는 `SocketTimeoutException`(연결 자체가 안 됨).
- 원인: 인바운드 규칙이 아예 없었음.
- 해결: `New-NetFirewallRule -DisplayName "PathPilot Backend 8000" -Direction Inbound -LocalPort 8000 -Protocol TCP -Action Allow -Profile Private,Domain` (관리자 권한 필요 — 사용자가 직접 실행).

### 6-5. 그래도 안 됨 — Wi-Fi가 Public 프로필이었음
- 원인: `Get-NetConnectionProfile`로 확인해보니 연결된 Wi-Fi(`SSU-WIFI`)가 **Public**으로 분류. 6-4의 규칙은 Private/Domain에만 적용돼서 여전히 막힘.
- 해결: `Set-NetFirewallRule -DisplayName "PathPilot Backend 8000" -Profile Domain,Private,Public`로 확장(사용자 승인 하에 진행 — 공용 Wi-Fi에 포트를 여는 트레이드오프 고지함).

### 6-6. 그래도 타임아웃 — 학교 공용 Wi-Fi의 client isolation (추정)
- 증상: 방화벽 다 열고 cleartext 예외도 넣었는데 여전히 `SocketTimeoutException`("Socket closed" — OkHttp read timeout이 스스로 소켓을 닫으면서 나는 증상).
- 원인 추정: 공용/학교 Wi-Fi AP가 같은 네트워크 안에서도 기기 간 통신(P2P)을 막아두는 경우가 흔함(client isolation). PC 쪽 소프트웨어는 다 열려 있어도 AP 레벨에서 막히면 답이 없음.
- **해결(최종, 안정적)**: Wi-Fi를 포기하고 **USB + `adb reverse tcp:8000 tcp:8000`**로 우회. 폰의 `127.0.0.1:8000`이 USB를 통해 PC의 `127.0.0.1:8000`으로 그대로 터널링된다. `RetrofitClient.BASE_URL`을 `http://127.0.0.1:8000/`로 변경, `network_security_config.xml`에 `127.0.0.1` 도메인 추가.
- **주의**: `adb reverse`는 USB 케이블을 뽑거나 폰을 재부팅하면 풀린다. 세션마다 다시 실행해야 함 (§9 참고).

### 6-7. 백엔드 프로세스가 두 개 떠서 헷갈렸던 삽질
- 처음에 (이 대화 세션의) Bash 툴로 uvicorn을 띄웠는데, PowerShell에서 확인한 `Get-NetTCPConnection -LocalPort 8000`이 빈 결과를 반환해서 "서로 다른 네트워크 네임스페이스가 아닌가" 의심하고 PowerShell에서 서버를 하나 더 띄우려다 포트 충돌로 실패했다.
- 실제로는: Bash로 띄운 프로세스(PID 25808)가 진짜였고(`netstat -ano`로 재확인하니 정상적으로 `0.0.0.0:8000 LISTENING`), `Get-NetTCPConnection`의 첫 결과가 우연히 비어 보였던 것뿐이었다. **이 부분은 확정된 결론이 아니라 당시 진단 과정의 혼선이므로, 다음에 비슷한 상황이면 `netstat -ano | Select-String :8000`으로 교차 확인할 것.**
- 정리: 지금 떠 있는 서버는 **PID 25808, Bash 툴에서 `python -m uvicorn backend.main:app --host 0.0.0.0 --port 8000` 로 시작**한 것. 로그 파일은 `/tmp/uvicorn.log` (Bash 기준 경로).

### 6-8. `TestAccessibilityService`가 스플래시 화면에서 매번 422로 막힘
- 증상: 카카오톡 스플래시("TALK" 로고) 화면에서 오버레이에 `서버 호출 실패: HTTP 422 Unprocessable Entity` 표시. 클릭이 전혀 안 일어남.
- 원인: 아직 레이아웃이 안 잡힌 노드가 `bounds=[0,0,0,0]`(폭/높이 0)으로 잡히는데, 백엔드 `ElementDTO.bounds` validator가 `left<right`, `top<bottom`을 요구해서 **요소 하나만 잘못돼도 요청 전체가 422로 거부**됨.
- 재현: `curl`로 `bounds:[0,0,0,0]`인 요소 하나만 넣어서 보내보니 동일한 `"bounds requires left < right"` 에러 확인.
- 해결: `TestAccessibilityService.visit()`에서 `bounds.left < bounds.right && bounds.top < bounds.bottom`인 노드만 수집하도록 필터 추가. **이건 정식 AccessibilityService(멤버 C)를 만들 때도 반드시 넣어야 하는 방어 로직이다 — 스플래시/전환 애니메이션 중인 화면에서 흔히 발생함.**

---

## 7. 최종 검증 결과 (증거)

수정 후 카카오톡을 콜드 스타트해서 캡처한 결과:

1. **T0(스플래시 직후)**: 오버레이 `"테스트 시작: 카카오톡에서 가장 최근에 찍은 사진 보내줘"` — 422 없음.
2. 3초 뒤 **T1**: 화면이 **채팅 목록 → 검색 화면(키보드 열림, 커서 깜빡임)**으로 실제 전환됨. 오버레이 `"다음 단계로 넘어갈게요."`
3. 서버 로그(`/tmp/uvicorn.log`)로 교차 확인:
   - 채팅목록(65개 요소) → `target_node_id=2, action_type=CLICK` → 200 OK
   - 검색화면(28개 요소) → `target_node_id=1, action_type=CLICK` → 200 OK (연속 2회)

**결론: 실제 화면 읽기 → 실제 네트워크 왕복 → 실제 클릭 실행 → 자동 반복까지 전 구간이 실기기에서 검증됨.** LLM이 Mock이라 "최근 사진 보내기"라는 목표를 이해하고 수행하지는 못하고, 클릭 가능한 첫 요소를 계속 누르는 동작만 한다 — 이건 정상이고 예상된 동작이다 (`MockAIClient`가 원래 그렇게 설계됨, `backend/services/ai_client.py` 참고).

---

## 8. 다음 세션이 정리해야 할 것 (팀 통합 전 되돌리기)

- [ ] `PermissionActivity`의 `android:exported="true"` → 원래 `false`로 되돌리기 (adb 테스트 전용이었음). 되돌린 뒤에는 앱 안에서 직접 실행할 진입점(버튼 등)을 만들어야 접근 가능.
- [ ] `MainActivity`에서 `PermissionActivity`로 연결하는 배선 추가 (팀 상의 후).
- [ ] `testkit/TestAccessibilityService.kt` 관련 3종 파일은 **멤버 C의 정식 AccessibilityService가 준비되면 삭제**.
- [ ] `RetrofitClient.BASE_URL`이 지금 `127.0.0.1`(adb reverse 전제)로 고정돼 있음 — 팀원마다 환경이 다르므로 최종적으로는 빌드 variant나 설정 파일로 분리하는 게 좋음 (지금은 하드코딩, §6 주석에 대안 3가지 적어둠: 에뮬레이터/실기기+adb reverse/같은 Wi-Fi 직결).
- [ ] Windows 방화벽 규칙(`PathPilot Backend 8000`, Public 포함)은 이 PC에만 있는 로컬 설정 — 다른 팀원 PC에서 테스트하려면 각자 만들어야 함. 이 문서의 §6-4, §6-5 명령어 그대로 쓰면 됨.
- [ ] 아직 **git add/commit 안 함**. 커밋 전 `git status`로 아래 §10 파일 목록과 대조 확인할 것.

---

## 9. 다음에 이어서 테스트할 때 — 체크리스트

매번 새로 켤 때 다시 해야 하는 것들:

1. 백엔드 서버 실행: `uvicorn backend.main:app --host 0.0.0.0 --port 8000` (PowerShell에서, 프로젝트 루트에서)
2. 폰을 USB로 연결, `adb devices`로 잡히는지 확인
3. **`adb reverse tcp:8000 tcp:8000`** — 이거 안 하면 위 §6-6 문제 재발함
4. 접근성 서비스가 꺼져 있으면(재설치하면 꺼질 수 있음) 다시 켜기: `adb shell am start -n com.example.pathpilot/.ui.permission.PermissionActivity` → 폰에서 버튼 눌러 3개 권한 확인
5. 카카오톡 열기 (수동으로 열거나 `adb shell monkey -p com.kakao.talk -c android.intent.category.LAUNCHER 1`)
6. 확인 방법: `adb logcat -d | Select-String "TestA11yService"` (에러 없으면 정상), 서버 로그(`decide request processed` 라인), 또는 `adb shell screencap`으로 스크린샷

---

## 10. 이번 세션에서 변경된 파일 전체 목록

```
신규:
  android/app/src/main/java/com/example/pathpilot/model/Types.kt
  android/app/src/main/java/com/example/pathpilot/network/ApiService.kt
  android/app/src/main/java/com/example/pathpilot/network/RetrofitClient.kt
  android/app/src/main/java/com/example/pathpilot/voice/VoiceInteractionManager.kt
  android/app/src/main/java/com/example/pathpilot/overlay/StatusOverlayManager.kt
  android/app/src/main/java/com/example/pathpilot/ui/permission/PermissionActivity.kt
  android/app/src/main/java/com/example/pathpilot/testkit/TestAccessibilityService.kt
  android/app/src/main/res/layout/activity_permission.xml
  android/app/src/main/res/xml/network_security_config.xml
  android/app/src/main/res/xml/test_accessibility_service_config.xml
  docs/worklog/2026-08-25-member-a-android-bootstrap.md (이 문서)

수정:
  android/app/build.gradle.kts             (Retrofit/OkHttp/Gson/Coroutines 의존성)
  android/app/src/main/AndroidManifest.xml  (권한, PermissionActivity, TestAccessibilityService)
  android/app/src/main/res/values/strings.xml
  android/gradle/libs.versions.toml

로컬 전용 (gitignore 대상, 커밋 안 됨):
  android/local.properties  (sdk.dir)
```

---

## 11. 관련 문서

- 팀 역할/파일 소유권: `dumps/TODO.md`, `docs/ARCHITECTURE.md` §2
- API 계약 정본: `dumps/API_SPEC.md` (v2.0)
- 안전 원칙/파이프라인 규칙: `CLAUDE.md`
- 전체 아키텍처: `docs/ARCHITECTURE.md`
