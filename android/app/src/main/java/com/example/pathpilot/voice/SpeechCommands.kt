package com.example.pathpilot.voice

/**
 * STT로 받은 사용자 발화를 분류하는 순수 함수 모음.
 *
 * 접근성 서비스 밖으로 뺀 이유: 이 판정이 틀리면 시연이 그대로 무너지는데(§[isStopCommand]),
 * 안드로이드 프레임워크에 묶여 있으면 실기기 없이는 확인할 방법이 없다. 여기 있는 것들은
 * 전부 String -> 판정이라 JVM 단위 테스트로 검증한다 (`SpeechCommandsTest`).
 */
object SpeechCommands {

    /** 사용자 답변의 종류. CLAUDE.md §5-1: 정보 제공형은 goal에 누적, 확인 응답은 user_speech로 일회성 전달. */
    enum class AnswerType { INFO, CONFIRMATION }

    /**
     * "그만"/"취소"/"중단"류의 **중단 명령**인지.
     *
     * 예전에는 단순 포함 매칭이라 **목표 문장을 잡아먹었다** — 코레일 시나리오에서 "예매 취소해줘"라고
     * 말하면 "취소"가 걸려 세션이 즉시 중단됐다. 이 검사는 목표를 받는 자리에서도 돌기 때문에
     * 실제로 밟게 되는 지뢰다.
     *
     * 그래서 두 가지로 좁혔다:
     * - **짧은 발화만** 본다([STOP_MAX_LENGTH]자). 중단 명령은 원래 짧고 목표 문장은 길다.
     * - **키워드로 시작해야** 한다. "취소해줘"는 중단이지만 "예매취소해줘"는 목표다 —
     *   앞에 목적어가 붙어 있으면 그건 무언가를 취소해 달라는 *요청*이다.
     * - 다만 "아니 취소해줘", "이제 그만"처럼 앞에 붙는 짧은 군말([STOP_PREFIXES])은 걷어내고 본다.
     */
    fun isStopCommand(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isEmpty() || normalized.length > STOP_MAX_LENGTH) return false
        val core = STOP_PREFIXES
            .firstOrNull { normalized.startsWith(it) && normalized.length > it.length }
            ?.let { normalized.removePrefix(it) }
            ?: normalized
        return STOP_KEYWORDS.any { core.startsWith(it) }
    }

    /**
     * 짧은 예/아니오류 답변만 [AnswerType.CONFIRMATION]으로 보고 나머지는 전부 [AnswerType.INFO]로 본다.
     *
     * 예전에는 완전 일치만 봐서 대부분이 INFO로 새어나갔다. STT는 "네." "네 맞아요" "응 보내줘"처럼
     * 구두점이나 뒷말을 붙여 돌려주는 일이 흔한데, 그게 전부 INFO로 분류되면 호출부가 goal에
     * 영구히 누적해서 몇 턴 만에 goal이 "사진 보내줘. 엄마한테요. 네. 응 맞아요."가 되고
     * LLM 판단이 흔들린다.
     *
     * **첫 낱말**만 보는 이유: 통째로 이어붙인 문자열에 접두 매칭을 하면 "예매 취소해줘"의 "예"가
     * 걸려 확인 응답으로 오분류되지만, 낱말 단위로 보면 첫 낱말이 "예매"라 걸리지 않는다.
     */
    fun classifyAnswer(text: String): AnswerType {
        val words = text.replace(PUNCTUATION_REGEX, " ").trim()
            .split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > CONFIRMATION_MAX_WORDS) return AnswerType.INFO
        return if (words.first() in CONFIRMATION_ANSWERS) AnswerType.CONFIRMATION else AnswerType.INFO
    }

    private fun normalize(text: String) =
        text.replace(PUNCTUATION_REGEX, "").replace(WHITESPACE_REGEX, "")

    /** 중단으로 인정하는 발화의 최대 길이(공백·구두점 제거 후). */
    private const val STOP_MAX_LENGTH = 8

    /** 확인 응답으로 인정하는 최대 낱말 수. */
    private const val CONFIRMATION_MAX_WORDS = 3

    private val PUNCTUATION_REGEX = Regex("[.,!?~…\"'`()\\[\\]]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    /** 정규화 후 이 중 하나로 **시작**해야 중단 명령으로 본다. */
    private val STOP_KEYWORDS = setOf(
        "취소", "그만", "중단", "멈춰", "멈춰줘", "스톱", "스탑", "하지마", "종료",
    )

    /** 중단 명령 앞에 흔히 붙는 군말. "아니 취소해줘", "이제 그만"을 놓치지 않으려고 걷어낸다. */
    private val STOP_PREFIXES = listOf("아니요", "아니오", "아니", "이제", "그냥", "저기", "야", "어")

    // 취소/그만류는 isStopCommand가 먼저 잡으므로 여기엔 없다.
    // 구두점을 떼고 첫 낱말만 비교하므로 여기 항목에는 공백을 넣지 않는다.
    private val CONFIRMATION_ANSWERS = setOf(
        "응", "네", "예", "넵", "웅", "맞아", "맞아요", "그래", "그래요",
        "좋아", "좋아요", "오케이", "콜", "진행", "진행해줘", "진행해주세요",
        "아니", "아니요", "아니오", "노", "안돼", "싫어",
    )
}
