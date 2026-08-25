package com.example.pathpilot.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 화면 위에 떠 있는 작은 상태 표시 오버레이.
 *
 * 결제 완료는 별도 Mock 화면을 만들지 않는다 — 실제 코레일+ 앱이 보여주는 화면으로 확인한다.
 * 이 오버레이는 그 사이 진행 상황("출발역 입력 중", "결제 진행 중" 등)만 짧게 안내하는 용도다.
 * (docs/ARCHITECTURE.md §7, dumps/TODO.md 멤버 A-4 갱신본)
 */
class StatusOverlayManager(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: TextView? = null

    /** SYSTEM_ALERT_WINDOW 권한이 있는지 확인 (ui/permission/PermissionActivity에서 요청). */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /** 오버레이가 없으면 만들고, 있으면 텍스트만 갱신한다. */
    fun showOrUpdate(message: String) {
        if (!hasOverlayPermission()) return

        val existing = overlayView
        if (existing != null) {
            existing.text = message
            return
        }

        val textView = TextView(appContext).apply {
            text = message
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(24, 16, 24, 16)
            textSize = 14f
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

        windowManager.addView(textView, params)
        overlayView = textView
    }

    fun hide() {
        overlayView?.let {
            runCatching { windowManager.removeView(it) }
        }
        overlayView = null
    }
}
