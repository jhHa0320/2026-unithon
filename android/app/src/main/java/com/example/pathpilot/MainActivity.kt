package com.example.pathpilot

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathpilot.testkit.GoalHolder

/**
 * 테스트 진입점 — 자연어 목표를 입력받아 [GoalHolder]에 저장하고 카카오톡을 실행한다.
 * 카카오톡이 포그라운드로 올라오면 `testkit.TestAccessibilityService`가 [GoalHolder]에서
 * 이 목표를 읽어 (하드코딩된 DEFAULT_GOAL 대신) 실제 파이프라인을 시작한다.
 *
 * **정식 구현이 아니다.** 접근성/마이크/오버레이 권한은 [ui.permission.PermissionActivity]에서
 * 미리 켜져 있어야 동작한다 — 이 화면은 그 상태를 별도로 확인하지 않는다.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val KAKAOTALK_PACKAGE = "com.kakao.talk"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val editGoal = findViewById<EditText>(R.id.edit_goal)
        val statusText = findViewById<TextView>(R.id.text_status)

        findViewById<Button>(R.id.button_start).setOnClickListener {
            val goal = editGoal.text?.toString()?.trim()
            if (goal.isNullOrEmpty()) {
                Toast.makeText(this, "먼저 무엇을 해야 할지 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            GoalHolder.set(goal)
            statusText.text = "목표 저장됨: $goal"
            launchKakaoTalk(statusText)
        }
    }

    /** 카카오톡을 켠다. 이미 떠 있으면 포그라운드로 올라오고, 꺼져 있으면 콜드 스타트된다. */
    private fun launchKakaoTalk(statusText: TextView) {
        val launchIntent = try {
            packageManager.getLaunchIntentForPackage(KAKAOTALK_PACKAGE)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (launchIntent == null) {
            statusText.text = "카카오톡이 설치돼 있지 않습니다."
            Toast.makeText(this, "카카오톡을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launchIntent)
    }
}
