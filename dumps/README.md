# dumps/

Android 실기기에서 추출한 UI Tree 덤프와 API 예제를 모아 두는 폴더.

| 파일 | 내용 | 상태 |
|---|---|---|
| `API_SPEC.md` | `/api/v1/decide` v2 계약의 실제 JSON 예제 | 최신 |
| `TODO.md` | 멤버별 개발 체크리스트 (10시간 데모 스코프) | 최신 |
| `s1.xml` | UI Tree 덤프 | ⚠️ **`com.android.settings`(안드로이드 설정) 화면 덤프라 프롬프트 개발에 쓸 수 없다.** 카카오톡 덤프로 교체 필요 |

## 덤프를 추가할 때

파일명에 **어떤 앱의 어떤 화면인지** 넣을 것. 예: `kakaotalk_friends.xml`, `kakaotalk_chatroom.xml`, `kakaotalk_photo_picker.xml`.

특히 **사진 첨부 그리드 덤프**가 데모 성패를 가른다 — 각 사진 셀에 `content-desc`가 붙어 있는지 확인하고, 없으면 즉시 팀에 공유할 것 (Vision fallback 또는 백업 시나리오 전환 판단이 필요하다).

> UI 덤프에는 실제 대화 상대 이름 등 사적인 정보가 포함될 수 있다. 커밋 전에 한 번 훑어볼 것.
