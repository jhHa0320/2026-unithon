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
 * 오버레이에는 "중단하기"/"종료하기" 버튼이 **항상** 함께 떠 있다(사용자 요구사항) —
 * 중단하기([onStopClicked])는 지금 하던 일을 멈추고 새 요청을 받는 용도,
 * 종료하기([onExitClicked])는 앱(접근성 서비스) 자체를 끄는 용도다.
 */
class StatusOverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var spinner: ProgressBar? = null

    private val hideHandler = Handler(Looper.getMainLooper())

    /** "중단하기" 버튼을 눌렀을 때 실행할 동작. 서비스 쪽에서 세션 중단+새 요청 청취를 걸어준다. */
    var onStopClicked: (() -> Unit)? = null

    /** "종료하기" 버튼을 눌렀을 때 실행할 동작. 서비스 쪽에서 앱 종료 로직을 걸어준다. */
    var onExitClicked: (() -> Unit)? = null

    /** SYSTEM_ALERT_WINDOW 권한이 있는지 확인 (ui/permission/PermissionActivity에서 요청). */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /** 오버레이가 없으면 만들고, 있으면 텍스트 모드로 갱신한다. 버튼 두 개는 항상 떠 있다. */
    fun showOrUpdate(message: String) {
        ensureViews()
        hideHandler.removeCallbacksAndMessages(null)
        spinner?.visibility = View.GONE
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
        spinner?.visibility = View.VISIBLE
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
        fun makeButton(label: String, bgColor: String, onClick: () -> Unit) =
            TextView(appContext).apply {
                // 바깥의 `val text`(메시지 TextView)가 프로퍼티를 가리므로 this를 명시한다.
                this.text = label
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding(36, 18, 36, 18)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 28f
                    setColor(Color.parseColor(bgColor))
                }
                setOnClickListener { onClick() }
            }

        val stop = makeButton("중단하기", "#E6E07A1F") { onStopClicked?.invoke() }
        val exit = makeButton("종료하기", "#E6B3261E") { onExitClicked?.invoke() }

        val statusRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                progress,
                LinearLayout.LayoutParams(72, 72).apply { marginEnd = 24 },
            )
            addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val buttonRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                exit,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = 24 },
            )
        }
        // 문구가 길어져도 버튼이 화면 밖으로 밀리지 않게 문구 줄과 버튼 줄을 세로로 나눈다.
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 32f
                setColor(Color.parseColor("#E6222222"))
            }
            addView(statusRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(
                buttonRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 20 },
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
    }

    fun hide() {
        hideHandler.removeCallbacksAndMessages(null)
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        textView = null
        spinner = null
    }
}
