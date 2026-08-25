package com.example.pathpilot.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ENABLED_ACCESSIBILITY_SERVICES` 파싱과 상태 판정 회귀 테스트.
 *
 * 이 판정이 틀리면 "권한을 분명히 켰는데 앱은 꺼졌다고 한다"가 되고, 사용자는 이미 켜 둔
 * 권한을 다시 켜려고 설정 화면을 헤매게 된다.
 */
class AccessibilityStatusTest {

    private val pkg = "com.example.pathpilot"

    @Test
    fun `완전형 컴포넌트 문자열을 인식한다`() {
        val setting = "$pkg/com.example.pathpilot.testkit.TestAccessibilityService"
        assertTrue(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    @Test
    fun `축약형 컴포넌트 문자열도 인식한다`() {
        // ComponentName.flattenToShortString()은 클래스 접두사를 생략한다. 이 프로젝트의 테스트
        // 기기(삼성)는 완전형만 저장하는 것으로 확인됐지만, 저장 형식은 OS 구현에 달린 것이라
        // 기대지 않는다. 예전 구현처럼 완전형 FQCN을 통째로 contains로 찾으면 축약형을 쓰는
        // 기기에서 조용히 "꺼짐"이 된다.
        val setting = "$pkg/.testkit.TestAccessibilityService"
        assertTrue(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    @Test
    fun `여러 서비스가 켜져 있어도 우리 것을 찾는다`() {
        val setting = listOf(
            "com.fluiz.fluizgpt/com.fluiz.seoulai.FluidGPTAccessibilityService",
            "ai.fluiz.ditestbed_agent/ai.fluiz.ditestbed_agent.FluidGPTAccessibilityService",
            "$pkg/.testkit.TestAccessibilityService",
        ).joinToString(":")
        assertTrue(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    @Test
    fun `목록에 없으면 꺼진 것으로 본다`() {
        val setting = "com.fluiz.fluizgpt/com.fluiz.seoulai.FluidGPTAccessibilityService"
        assertFalse(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    @Test
    fun `비어 있거나 null이면 꺼진 것으로 본다`() {
        assertFalse(AccessibilityStatus.isEnabledInSettings(null, pkg))
        assertFalse(AccessibilityStatus.isEnabledInSettings("", pkg))
        assertFalse(AccessibilityStatus.isEnabledInSettings("   ", pkg))
    }

    @Test
    fun `접두사만 같은 다른 패키지를 우리 것으로 오인하지 않는다`() {
        // "com.example.pathpilot"으로 시작하는 남의 패키지를 startsWith/contains로 보면 걸린다.
        val setting = "com.example.pathpilot2/com.example.pathpilot2.SomeService"
        assertFalse(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    @Test
    fun `구분자 주변 공백을 견딘다`() {
        val setting = " $pkg/.testkit.TestAccessibilityService : com.other/.Svc "
        assertTrue(AccessibilityStatus.isEnabledInSettings(setting, pkg))
    }

    // --- 상태 결정 -----------------------------------------------------------
    //
    // 서비스가 죽으면 시스템이 접근성 목록에서 항목을 지운다. 그때 "안 켜셨습니다"라고 하면
    // 분명히 켰던 사용자는 "왜 켠 게 안 먹지?"가 된다. 그 둘을 구분하는 게 이 규칙의 목적이다.

    @Test
    fun `연결돼 있으면 실행 중이다`() {
        val state = AccessibilityStatus.decideState(
            connected = true, enabledInSettings = true, wasEnabledBefore = true,
        )
        assertEquals(AccessibilityStatus.State.RUNNING, state)
    }

    @Test
    fun `목록에는 있는데 응답이 없으면 죽은 것으로 본다`() {
        val state = AccessibilityStatus.decideState(
            connected = false, enabledInSettings = true, wasEnabledBefore = true,
        )
        assertEquals(AccessibilityStatus.State.ENABLED_BUT_DEAD, state)
    }

    @Test
    fun `켠 적이 있는데 목록에서 사라졌으면 예기치 않게 꺼진 것이다`() {
        val state = AccessibilityStatus.decideState(
            connected = false, enabledInSettings = false, wasEnabledBefore = true,
        )
        assertEquals(AccessibilityStatus.State.TURNED_OFF_UNEXPECTEDLY, state)
    }

    @Test
    fun `켠 적이 없으면 첫 실행으로 본다`() {
        val state = AccessibilityStatus.decideState(
            connected = false, enabledInSettings = false, wasEnabledBefore = false,
        )
        assertEquals(AccessibilityStatus.State.NEVER_ENABLED, state)
    }
}
