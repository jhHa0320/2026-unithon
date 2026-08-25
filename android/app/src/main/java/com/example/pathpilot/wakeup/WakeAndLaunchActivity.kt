package com.example.pathpilot.wakeup

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import com.example.pathpilot.testkit.TestAccessibilityService

/**
 * 화면이 꺼져 있거나 잠겨 있는 상태에서 음성 트리거가 들어왔을 때 "화면을 깨우고 목표 앱으로
 * 이동하는 모션"을 담당하는, UI 없는 중계 Activity. onCreate에서 화면을 켜고 goal 문장을 보고
 * 정한 앱(카카오톡/카카오택시, [resolveTargetPackage] 참고)을 실행한 뒤 스스로 finish()한다 —
 * 그 이후는 [TestAccessibilityService.onAccessibilityEvent]가 그 앱 창을 감지해서 이어받는다.
 *
 * **잠금 화면 위에 띄우는 것까지만 된다.** 기기가 PIN/패턴/생체인증으로 잠겨 있으면 실제 잠금
 * 해제는 사용자가 직접 해야 한다 — Android는 앱이 보안 잠금을 코드로 우회하는 걸 허용하지 않는다
 * (CLAUDE.md §4-2 "보안 통제 우회 금지"와도 방향이 같다).
 */
class WakeAndLaunchActivity : Activity() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        acquireShortWakeLock()
        applyShowOverLockScreenFlags()

        val goal = intent.getStringExtra(EXTRA_GOAL)
        TestAccessibilityService.pendingGoal = goal
        launchTargetApp(resolveTargetPackage(goal))
        finish()
    }

    /**
     * goal 문장에 어떤 앱을 가리키는 키워드가 있는지 보고 실행할 패키지를 정한다.
     * **정식 구현이 아니다** — 지금은 "택시"라는 단어가 있으면 카카오택시, 없으면 기본값(카카오톡)인
     * 아주 단순한 규칙이다. 지원 앱이 늘어나면 이 if/when 사슬 대신 LLM이나 별도 분류기로
     * 넘기는 걸 검토할 것 — 키워드 매칭은 "택시 사진 보내줘" 같은 애매한 문장에서 오판할 수 있다.
     */
    private fun resolveTargetPackage(goal: String?): String {
        val text = goal.orEmpty()
        return when {
            text.contains("택시") -> KAKAOTAXI_PACKAGE
            else -> KAKAOTALK_PACKAGE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** 화면을 켜는 동작과 카카오톡 실행 사이에 CPU가 다시 잠들지 않도록 짧게만 잡아둔다. */
    private fun acquireShortWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:WakeAndLaunch",
        ).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun applyShowOverLockScreenFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
    }

    private fun launchTargetApp(targetPackage: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        if (launchIntent == null) {
            Log.w(TAG, "$targetPackage 를 찾지 못함 (미설치?)")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "WakeAndLaunch"
        private const val KAKAOTALK_PACKAGE = "com.kakao.talk"
        private const val KAKAOTAXI_PACKAGE = "com.kakao.taxi"
        private const val WAKE_LOCK_TIMEOUT_MS = 10_000L

        /** 새로 시작할 세션의 goal을 지정하고 싶을 때 담아 보내는 선택적 extra. */
        const val EXTRA_GOAL = "extra_goal"
    }
}
