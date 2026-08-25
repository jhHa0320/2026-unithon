package com.example.pathpilot.voice

import com.example.pathpilot.voice.SpeechCommands.AnswerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 발화 분류 판정의 회귀 테스트. 여기 있는 케이스들은 전부 실제로 겪었거나 시연 대본에서
 * 밟게 되는 문장이다 — 특히 "예매 취소해줘"는 중단으로 오탐되면 코레일 시나리오가 그 자리에서 끝난다.
 */
class SpeechCommandsTest {

    // --- 중단 명령으로 인정해야 하는 것 -------------------------------------

    @Test
    fun `짧은 중단 명령을 인식한다`() {
        listOf("그만", "취소", "중단", "멈춰", "스톱", "종료해줘", "취소해줘", "그만해줘", "하지마")
            .forEach { assertTrue("'$it'는 중단 명령이어야 한다", SpeechCommands.isStopCommand(it)) }
    }

    @Test
    fun `구두점과 띄어쓰기가 섞여도 인식한다`() {
        listOf("그만!", "취소.", "그만 해줘", "멈춰 줘")
            .forEach { assertTrue("'$it'는 중단 명령이어야 한다", SpeechCommands.isStopCommand(it)) }
    }

    @Test
    fun `앞에 붙는 군말을 걷어내고 인식한다`() {
        listOf("아니 취소해줘", "이제 그만", "아니요 중단해줘", "그냥 취소")
            .forEach { assertTrue("'$it'는 중단 명령이어야 한다", SpeechCommands.isStopCommand(it)) }
    }

    // --- 중단 명령이 아니어야 하는 것 (오탐 방지) ---------------------------

    @Test
    fun `목적어가 앞에 붙은 요청은 중단 명령이 아니다`() {
        // 이게 이 테스트 파일의 존재 이유다. 예전 구현은 부분 일치라 전부 중단으로 잡았고,
        // 코레일 시나리오에서 목표를 말하는 순간 세션이 죽었다.
        listOf(
            "예매 취소해줘",
            "예매를 취소해줘",
            "기차표 취소해줘",
            "KTX 예매 취소해줘",
            "주문 취소해줘",
            "알람 종료해줘",
        ).forEach { assertFalse("'$it'는 목표 문장이어야 한다", SpeechCommands.isStopCommand(it)) }
    }

    @Test
    fun `보통의 목표 문장은 중단 명령이 아니다`() {
        listOf(
            "카톡으로 엄마한테 사진 보내줘",
            "가장 최근에 찍은 사진 보내줘",
            "서울역에서 부산역 가는 기차 예매해줘",
            "",
        ).forEach { assertFalse("'$it'는 중단 명령이 아니어야 한다", SpeechCommands.isStopCommand(it)) }
    }

    // --- 확인 응답 분류 -----------------------------------------------------

    @Test
    fun `구두점이나 뒷말이 붙은 확인 응답도 분류한다`() {
        listOf("네", "네.", "응", "예", "맞아요", "네 맞아요", "응 보내줘", "그래 진행해줘", "아니요")
            .forEach {
                assertEquals("'$it'는 확인 응답이어야 한다", AnswerType.CONFIRMATION, SpeechCommands.classifyAnswer(it))
            }
    }

    @Test
    fun `정보 제공형 답변은 goal에 누적되도록 INFO로 분류한다`() {
        listOf("엄마한테요", "어제 찍은 거요", "김엄마요", "두 번째 거요", "서울역이요")
            .forEach {
                assertEquals("'$it'는 정보 제공형이어야 한다", AnswerType.INFO, SpeechCommands.classifyAnswer(it))
            }
    }

    @Test
    fun `확인 응답 낱말로 시작하는 긴 문장은 정보 제공형이다`() {
        // "예매..."의 "예"가 확인 응답으로 새어나가면 goal이 구체화되지 못한다.
        listOf("예매 취소해줘", "네이버에 검색해줘", "노란색 사진 보내줘")
            .forEach {
                assertEquals("'$it'는 정보 제공형이어야 한다", AnswerType.INFO, SpeechCommands.classifyAnswer(it))
            }
    }
}
