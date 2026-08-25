package com.example.pathpilot

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * 로딩(스플래시) 화면 (screen/Loading Screen 시안). 브랜드를 잠깐 보여준 뒤 [MainActivity]로
 * 넘어간다. 점 3개가 순서대로 밝아지는 간단한 애니메이션으로 "켜지는 중"을 표현한다.
 */
class LoadingActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)

        // 점 3개 순차 페이드 — 별도 라이브러리 없이 View 알파 애니메이션만 쓴다.
        val dots = listOf(
            findViewById<android.view.View>(R.id.dot1),
            findViewById<android.view.View>(R.id.dot2),
            findViewById<android.view.View>(R.id.dot3),
        )
        dots.forEachIndexed { index, dot ->
            dot.animate()
                .alpha(1f)
                .setStartDelay(index * 220L)
                .setDuration(320L)
                .start()
        }

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }

    private companion object {
        /** 브랜드가 인지될 만큼만 잠깐. 시연 흐름을 늦출 정도로 길면 안 된다. */
        const val SPLASH_DURATION_MS = 1_200L
    }
}
