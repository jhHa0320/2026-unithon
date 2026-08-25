package com.example.pathpilot.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * 화면 위에 떠 있는 작은 상태 표시 오버레이.
 *
 * 두 가지 모드가 있다:
 * - [showOrUpdate]: 문구를 보여준다 (다음 동작 안내, 되묻는 질문, 사용자 답변 등).
 * - [showAnalyzing]: 문구 없이 돌아가는 스피너만 보여준다 — "지금 화면을 분석 중"이라는 뜻을
 *   아이콘으로만 전달한다. 화면 요소 개수 같은 세부 텍스트는 사용자에게 의미가 없어서 뺐다.
 *
 * 진행 중에는 "그만하기" 버튼이 함께 떠서 사용자가 언제든 자동화를 중단할 수 있다 —
 * 버튼을 누르면 [onStopClicked]가 호출된다. 완료/중단 안내처럼 더 이상 멈출 게 없는 상태에서는
 * `showStop = false`로 버튼을 숨기고 [hideAfterDelay]로 잠시 후 오버레이를 치운다.
 */
class StatusOverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var spinner: ProgressBar? = null
    private var stopButton: TextView? = null

    private val hideHandler = Handler(Looper.getMainLooper())

    /** "그만하기" 버튼을 눌렀을 때 실행할 동작. 서비스 쪽에서 세션 중단 로직을 걸어준다. */
    var onStopClicked: (() -> Unit)? = null

    /** SYSTEM_ALERT_WINDOW 권한이 있는지 확인 (ui/permission/PermissionActivity에서 요청). */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /** 오버레이가 없으면 만들고, 있으면 텍스트 모드로 갱신한다.
     * [showStop]이 false면 "그만하기" 버튼을 숨긴다 — 완료/중단 안내처럼 멈출 게 없는 상태용. */
    fun showOrUpdate(message: String, showStop: Boolean = true) {
        ensureViews()
        hideHandler.removeCallbacksAndMessages(null)
        spinner?.visibility = View.GONE
        stopButton?.visibility = if (showStop) View.VISIBLE else View.GONE
        textView?.apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    /** 텍스트 없이 스피너만 보여준다 — "실시간으로 분석 중"이라는 걸 아이콘으로만 알린다. */
    fun showAnalyzing() {
        ensureViews()
        hideHandler.removeCallbacksAndMessages(null)
        textView?.visibility = View.GONE
        stopButton?.visibility = View.VISIBLE
        spinner?.visibility = View.VISIBLE
    }

    /** [delayMs] 뒤에 오버레이를 치운다. 그 사이 [showOrUpdate]/[showAnalyzing]가 다시 불리면 취소된다. */
    fun hideAfterDelay(delayMs: Long) {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({ hide() }, delayMs)
    }

    private fun ensureViews() {
        if (!hasOverlayPermission()) return
        if (rootView != null) return

        val text = TextView(appContext).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(0, 0, 0, 0)
        }
        val progress = ProgressBar(appContext).apply {
            isIndeterminate = true
            indeterminateDrawable?.setTint(Color.WHITE)
            visibility = View.GONE
        }
        val stop = TextView(appContext).apply {
            // 바깥의 `val text`(메시지 TextView)가 프로퍼티를 가리므로 this를 명시한다.
            this.text = "그만하기"
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(36, 18, 36, 18)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 28f
                setColor(Color.parseColor("#E6B3261E"))
            }
            setOnClickListener { onStopClicked?.invoke() }
        }
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(36, 24, 36, 24)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#E6222222"))
            }
            addView(
                progress,
                LinearLayout.LayoutParams(72, 72).apply { marginEnd = 24 },
            )
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                stop,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = 32 },
            )
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        // FLAG_NOT_TOUCHABLE을 걸면 "그만하기" 버튼이 눌리지 않는다. FLAG_NOT_FOCUSABLE만 남겨
        // 오버레이 영역 안의 터치만 받고 키 입력/포커스는 뒤 앱에 그대로 넘긴다.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 96
        }

        windowManager.addView(container, params)
        rootView = container
        textView = text
        spinner = progress
        stopButton = stop
    }

    fun hide() {
        hideHandler.removeCallbacksAndMessages(null)
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        textView = null
        spinner = null
        stopButton = null
    }
}
