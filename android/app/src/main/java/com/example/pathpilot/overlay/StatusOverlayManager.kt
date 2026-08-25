package com.example.pathpilot.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
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
 */
class StatusOverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: LinearLayout? = null
    private var textView: TextView? = null
    private var spinner: ProgressBar? = null

    /** SYSTEM_ALERT_WINDOW 권한이 있는지 확인 (ui/permission/PermissionActivity에서 요청). */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /** 오버레이가 없으면 만들고, 있으면 텍스트 모드로 갱신한다. */
    fun showOrUpdate(message: String) {
        ensureViews()
        spinner?.visibility = View.GONE
        textView?.apply {
            visibility = View.VISIBLE
            text = message
        }
    }

    /** 텍스트 없이 스피너만 보여준다 — "실시간으로 분석 중"이라는 걸 아이콘으로만 알린다. */
    fun showAnalyzing() {
        ensureViews()
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
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
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
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        textView = null
        spinner = null
    }
}
