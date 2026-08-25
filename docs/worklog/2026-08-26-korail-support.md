# 작업일지 — 코레일톡(코레일) 앱 지원 확장

- 작성일: 2026-08-26
- 브랜치: `feature/korail-support` (사용자 요청으로 main이 아닌 별도 브랜치에서 진행)
- 선행 작업: `41aa9ec` feat: support KakaoTaxi — 이 커밋이 만든 멀티앱 패턴을 그대로 확장했다.
  배경은 `docs/worklog/2026-08-26-handoff-voice-multiapp-ui.md` §2-8 참고.

## 1. 목표

카카오톡·카카오택시에서 동작이 확인된 음성 자동화 파이프라인("안녕 손자" → 요청 발화 →
앱 실행 → 화면 읽기 → LLM 판단 → 클릭 반복)을 코레일톡에서도 쓸 수 있게 한다.
예: "부산 가는 KTX 예매해줘".

## 2. 패키지명 확인

실기기(R3CWA0J3J8Z)에 실제 설치된 앱을 `adb shell pm list packages | grep korail`로 확인:
**`com.korail.talk`** (코레일톡). 기획 문서에는 "코레일+"로 적혀 있으나 이 폰에 설치된 앱은
코레일톡이므로 이 패키지를 대상으로 한다.

## 3. 수정 내역 (카카오택시 때와 동일한 4곳 패턴)

1. `res/xml/test_accessibility_service_config.xml` — `android:packageNames`에 `com.korail.talk`
   추가. **이게 핵심** — 이 목록 밖 앱은 OS가 접근성 이벤트 자체를 안 보내준다. 주석도 갱신
   (기존 주석이 "코레일은 결제가 실행되니 제외한다"였는데, 이제 의도적으로 포함하므로
   "취소 가능한 구간까지만 테스트" 경고로 교체).
2. `AndroidManifest.xml` — `<queries>`에 `com.korail.talk` 추가 (targetSdk 37 패키지 가시성,
   없으면 `getLaunchIntentForPackage`가 null 반환).
3. `WakeAndLaunchActivity.kt` — `resolveTargetPackage()`에 코레일 분기 추가.
   키워드: 기차/열차/코레일/ktx/케이티엑스/승차권/무궁화호/새마을호 (소문자 비교).
   STT가 "KTX"를 "케이티엑스"로 받아쓰는 경우가 있어 둘 다 넣었다.
   "택시" 검사를 먼저 하는 기존 순서 유지 — "기차역까지 택시 불러줘"에서 택시가 이겨야 한다.
4. `TestAccessibilityService.kt` — `TARGET_PACKAGES`에 `com.korail.talk` 추가.

백엔드는 **수정 없음** — 프롬프트(v3)는 v2에서 이미 도메인 중립으로 바뀌었고
(KTX 전용 슬롯명 제거가 오히려 v2 작업이었다), `app_package`는 실제 이벤트 패키지를
그대로 전송하는 구조라 코레일톡이어도 추가 코드가 필요 없다.

## 4. 알려진 위험 / 미검증

- **코레일톡은 실제 결제가 실행되는 앱이다.** 프롬프트 규칙 5("전송·결제·확정도 정상 진행")에
  따라 결제 버튼도 자동 클릭된다. 리허설은 반드시 취소 가능한 구간까지만 하거나,
  결제 직전에 수동 개입할 준비를 하고 할 것 (KTX는 출발 전 취소 시 수수료 환불 가능하지만
  손해가 0은 아니다).
- **실기기 엔드투엔드 미검증** — 빌드/설치까지만 확인. 코레일톡 화면(역 선택 캘린더,
  좌석 선택 그리드 등)에서 LLM이 잘 판단하는지, 접근성 라벨 없는 커스텀 뷰가 없는지는
  실기기 테스트로 확인해야 한다. 문제가 보이면 `services/prompt.py` 튜닝(PROMPT_VERSION 올릴 것).
- 코레일톡 메인 화면에 팝업/공지가 자주 뜬다 — 카카오택시 때처럼 "팝업 닫기"를 LLM이
  스스로 판단해야 하는데 미실측.

## 5. 다음 할 일

1. 실기기 테스트: "안녕 손자" → "서울에서 부산 가는 기차표 예매해줘" → 코레일톡이 뜨고
   접근성 이벤트가 들어오는지 (`adb logcat -s TestA11yService:*`에서 package=com.korail.talk 확인).
2. 화면 진행이 되면 결제 직전 단계까지의 판단 품질 확인.
3. 문제없으면 main에 머지.
