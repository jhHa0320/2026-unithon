package com.example.pathpilot.accessibility

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.pathpilot.testkit.TestAccessibilityService

/**
 * 접근성 서비스의 상태를 판정한다. 화면마다 제각각으로 검사하지 않도록 여기 한 벌만 둔다.
 *
 * **"켜져 있다"와 "살아 있다"는 다른 이야기다.**
 * - 켜짐: 사용자가 시스템 설정에서 우리 서비스를 활성화했다 ([isEnabledInSettings])
 * - 살아 있음: 그 서비스가 실제로 바인딩돼 접근성 이벤트를 받고 있다
 *   ([TestAccessibilityService.isServiceConnected])
 *
 * 둘은 갈릴 수 있다. 서비스가 죽거나 설정 XML의 capability가 바뀐 채 재설치되면 바인딩은
 * 끊기는데 설정 목록에는 그대로 남는다. 그러면 화면에는 "켜짐"인데 실제로는 아무것도 동작하지
 * 않는, 원인을 짐작할 수 없는 상태가 된다 — 그래서 [current]가 셋으로 나눠 알려준다.
 */
object AccessibilityStatus {

    enum class State {
        /** 켠 적이 없다(정상적인 첫 실행). 설정 화면으로 안내하면 된다. */
        NEVER_ENABLED,

        /**
         * 켜져 있었는데 지금은 목록에서 사라졌다.
         *
         * **서비스가 죽으면 시스템이 접근성 목록에서 항목을 제거한다**(2026-08-26 실기기 확인).
         * 사용자는 분명히 켠 기억이 있는데 화면에는 "꺼짐"이 뜨니 "왜 켠 게 안 먹지?"가 된다.
         * [NEVER_ENABLED]와 같은 문구를 쓰면 안 되는 이유가 이것이다 — 사용자가 겪은 일은
         * "안 켠 것"이 아니라 "켰는데 풀린 것"이라, 그렇게 말해줘야 납득하고 다시 켠다.
         */
        TURNED_OFF_UNEXPECTEDLY,

        /** 설정에는 켜져 있는데 서비스가 응답하지 않는다. 껐다 켜야 복구된다. */
        ENABLED_BUT_DEAD,

        /** 정상. */
        RUNNING,
    }

    fun current(context: Context): State {
        val connected = TestAccessibilityService.isServiceConnected
        val enabled = isEnabledInSettings(context)
        val wasEnabled = wasEnabledBefore(context)
        val state = decideState(connected, enabled, wasEnabled)
        // 이 판정이 틀리면 "권한을 켰는데 꺼졌다고 나온다"가 되고, 화면만 봐서는 어느 쪽이
        // 틀렸는지 알 수 없다. 판정 근거를 그대로 남긴다(패키지명뿐이라 민감정보가 아니다).
        Log.d(
            TAG,
            "접근성 상태=$state (connected=$connected, enabledInSettings=$enabled, wasEnabled=$wasEnabled)",
        )
        return state
    }

    /** 상태 결정 규칙. 안드로이드 API에 기대지 않아 단위 테스트로 검증한다. */
    fun decideState(connected: Boolean, enabledInSettings: Boolean, wasEnabledBefore: Boolean): State = when {
        connected -> State.RUNNING
        enabledInSettings -> State.ENABLED_BUT_DEAD
        wasEnabledBefore -> State.TURNED_OFF_UNEXPECTEDLY
        else -> State.NEVER_ENABLED
    }

    /**
     * "이 기기에서 우리 접근성 서비스가 한 번이라도 실제로 연결된 적이 있다"를 남긴다.
     * 서비스가 [TestAccessibilityService.onServiceConnected]에서 호출한다.
     */
    fun rememberEnabled(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_WAS_ENABLED, true)
            .apply()
    }

    private fun wasEnabledBefore(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_WAS_ENABLED, false)

    fun isEnabledInSettings(context: Context): Boolean = isEnabledInSettings(
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ),
        context.packageName,
    )

    /**
     * `ENABLED_ACCESSIBILITY_SERVICES` 값에 [packageName]의 접근성 서비스가 들어 있는지.
     *
     * **컴포넌트 문자열의 형식은 OS 구현에 달렸다.**
     * `com.example.app/com.example.app.MyService`(완전형)일 수도,
     * `com.example.app/.MyService`(축약형, `ComponentName.flattenToShortString`)일 수도 있다.
     * 이 프로젝트의 테스트 기기(삼성)는 완전형만 저장하는 것으로 확인됐지만 거기 기대지 않는다 —
     * 예전 구현처럼 완전형 FQCN을 통째로 `contains`로 찾으면, 축약형을 쓰는 기기에서 서비스가
     * 정상 동작하는데도 조용히 "꺼짐"으로 표시된다. 그래서 `/` 앞의 **패키지 부분만** 꺼내
     * 정확히 비교한다 — 형식과 무관하고, `com.example.app2`처럼 접두사만 같은 남의 패키지도
     * 걸리지 않는다.
     *
     * 안드로이드 API에 의존하지 않는 순수 함수라 JVM 단위 테스트로 검증한다.
     */
    fun isEnabledInSettings(setting: String?, packageName: String): Boolean {
        if (setting.isNullOrBlank()) return false
        return setting.split(SERVICE_SEPARATOR).any { entry ->
            entry.trim().substringBefore(COMPONENT_SEPARATOR, missingDelimiterValue = "") == packageName
        }
    }

    private const val TAG = "AccessibilityStatus"
    private const val PREFS_NAME = "accessibility_status"
    private const val KEY_WAS_ENABLED = "was_enabled"
    private const val SERVICE_SEPARATOR = ':'
    private const val COMPONENT_SEPARATOR = '/'
}
