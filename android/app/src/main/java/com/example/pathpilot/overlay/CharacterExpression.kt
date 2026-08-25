package com.example.pathpilot.overlay

import androidx.annotation.DrawableRes
import com.example.pathpilot.R

/**
 * 상태별 캐릭터 표정. `char/` 원본 1~5.png를 의미 기준으로 분류한 것이다 (image.png 시안 참고).
 *
 * 시안의 세 예시와의 대응:
 * - "카카오톡을 찾고있어요"   -> [FOCUSED]  (차분하게 정면 응시)
 * - "카카오톡으로 이동할게요"  -> [HAPPY]    (눈웃음 + 손 모음)
 * - "누구에게 카톡을 보낼까요?" -> [CURIOUS] (고개 갸웃, 눈 동그랗게)
 */
enum class CharacterExpression(@DrawableRes val drawableRes: Int) {
    /** 손 흔들며 인사 (1.png) — 앱 메인 화면 대기 상태. */
    GREETING(R.drawable.char_greeting),

    /** 고개 갸웃, 궁금한 표정 (2.png) — 되묻기(ASK_USER), 답변 대기. */
    CURIOUS(R.drawable.char_curious),

    /** 차분한 집중 표정 (3.png) — 화면 분석 중, 대상 찾는 중. */
    FOCUSED(R.drawable.char_focused),

    /** 눈 크게 뜨고 경청/놀람 (4.png) — 마이크 열림, 예상 밖 상황(재시도). */
    LISTENING(R.drawable.char_listening),

    /** 활짝 웃으며 손 모음 (5.png) — 동작 실행, 완료. */
    HAPPY(R.drawable.char_happy),
}
