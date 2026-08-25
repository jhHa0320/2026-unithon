package com.example.pathpilot.wakeup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.pathpilot.BuildConfig

/**
 * "화면 꺼짐 → 깨우기 → 카카오톡 실행" 흐름을 실기기에서 눌러보기 위한 테스트용 트리거.
 *
 * 예: adb shell am broadcast -a com.example.pathpilot.action.WAKE_AND_LAUNCH_KAKAO --es goal "엄마한테 사진 보내줘"
 *
 * **정식 트리거가 아니다.** 실제로는 음성 웨이크워드 감지 등 신뢰할 수 있는 트리거로 교체해야
 * 한다(아직 미구현 — 설계 결정 필요). `exported=true`인 채로 두면 다른 어떤 앱이든 이 브로드캐스트를
 * 보내 화면을 켜고 카카오톡 자동화를 시작시킬 수 있으므로, [BuildConfig.DEBUG]가 아니면 즉시
 * 무시하도록 막아뒀다 — 릴리스 빌드에 이 exported 리시버가 그대로 들어가도 실질적으로 아무 일도
 * 하지 않는다. adb 테스트는 signature 권한을 걸면 shell 사용자가 막혀버려서 못 하게 되므로
 * 이 방식을 대신 택했다.
 */
class WakeTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "release 빌드에서는 무시됨 (테스트 전용 트리거)")
            return
        }
        val goal = intent.getStringExtra(EXTRA_GOAL)
        val activityIntent = Intent(context, WakeAndLaunchActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(WakeAndLaunchActivity.EXTRA_GOAL, goal)
        }
        context.startActivity(activityIntent)
    }

    companion object {
        private const val TAG = "WakeTriggerReceiver"
        const val ACTION_WAKE_AND_LAUNCH = "com.example.pathpilot.action.WAKE_AND_LAUNCH_KAKAO"
        private const val EXTRA_GOAL = "goal"
    }
}
