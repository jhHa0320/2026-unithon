package com.example.pathpilot.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.example.pathpilot.R

/**
 * 자동화 진행 상태를 보여주는 오버레이 (screen/폰 메인 화면 ver1 시안).
 *
 * 화면 하단에 흰 말풍선 카드가 붙고, 카드 위로 캐릭터가 솟아 있다. 캐릭터의 표정은
 * 상태에 따라 바뀐다([CharacterExpression] — 찾는 중/실행/되묻기 등). 카드 하단
 * 좌우 모서리에 초록(중단하기)·빨강(종료하기) 원형 버튼이 항상 떠 있다(사용자 요구사항).
 */
class StatusOverlayManager(context: Context) {

    /**
     * **applicationContext가 아니라 생성자로 받은 컨텍스트를 그대로 쓴다.**
     * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 창은 접근성 서비스만 띄울 수 있어서,
     * 서비스 컨텍스트의 WindowManager로 붙여야 확실하다. 이 매니저는 서비스가 소유하고 수명도
     * 같으므로 서비스 컨텍스트를 들고 있어도 누수가 아니다.
     */
    private val hostContext = context
    private val appContext = context.applicationContext
    private val windowManager =
        hostContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var characterView: ImageView? = null
    private var messageView: TextView? = null
    private var spinnerView: ProgressBar? = null

    private val hideHandler = Handler(Looper.getMainLooper())

    /** "중단하기" 버튼을 눌렀을 때 실행할 동작. 서비스 쪽에서 세션 중단+새 요청 청취를 걸어준다. */
    var onStopClicked: (() -> Unit)? = null

    /** "종료하기" 버튼을 눌렀을 때 실행할 동작. 서비스 쪽에서 앱 종료 로직을 걸어준다. */
    var onExitClicked: (() -> Unit)? = null

    /**
     * SYSTEM_ALERT_WINDOW 권한이 있는지 확인 (ui/permission/PermissionActivity에서 요청).
     *
     * 이 권한이 없어도 오버레이는 뜬다 — [attach]가 접근성 오버레이를 먼저 시도하기 때문이다.
     * 이 함수는 권한 안내 화면이 "허용됨"을 표시하는 용도로만 남아 있다.
     */
    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }

    /**
     * 오버레이가 없으면 만들고, 있으면 문구/표정을 갱신한다. 버튼 두 개는 항상 떠 있다.
     * [expression]이 null이면 표정은 바꾸지 않는다 — 문구만 갱신하는 호출부가 직전 표정을
     * 일일이 기억할 필요가 없게.
     */
    fun showOrUpdate(message: String, expression: CharacterExpression? = null) {
        ensureViews()
        hideHandler.removeCallbacksAndMessages(null)
        spinnerView?.visibility = View.GONE
        messageView?.apply {
            visibility = View.VISIBLE
            text = message
        }
        expression?.let { characterView?.setImageResource(it.drawableRes) }
    }

    /** 문구 대신 스피너를 보여준다 — "실시간으로 분석 중"이라는 걸 아이콘으로만 알린다. */
    fun showAnalyzing() {
        ensureViews()
        hideHandler.removeCallbacksAndMessages(null)
        messageView?.visibility = View.GONE
        spinnerView?.visibility = View.VISIBLE
        characterView?.setImageResource(CharacterExpression.FOCUSED.drawableRes)
    }

    private fun ensureViews() {
        if (rootView != null) return

        val view = LayoutInflater.from(hostContext).inflate(R.layout.overlay_status, null)
        characterView = view.findViewById(R.id.overlay_character)
        messageView = view.findViewById(R.id.overlay_message)
        spinnerView = view.findViewById(R.id.overlay_spinner)
        view.findViewById<ImageView>(R.id.overlay_btn_stop).setOnClickListener {
            onStopClicked?.invoke()
        }
        view.findViewById<ImageView>(R.id.overlay_btn_exit).setOnClickListener {
            onExitClicked?.invoke()
        }

        if (!attach(view)) {
            // 붙이지 못했으면 참조를 남기지 않는다 — 다음 showOrUpdate에서 다시 시도하게 된다.
            // (권한을 나중에 켜거나 일시적인 토큰 문제였던 경우 저절로 복구된다.)
            characterView = null
            messageView = null
            spinnerView = null
            return
        }
        rootView = view
    }

    /**
     * 오버레이를 화면에 붙인다. 성공 여부를 돌려준다.
     *
     * 창 타입을 순서대로 시도한다:
     * 1. **[WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]** — 접근성 서비스만 띄울 수 있는
     *    대신 **SYSTEM_ALERT_WINDOW 권한이 아예 필요 없다.** 시연 직전에 권한 하나 때문에 오버레이도
     *    버튼도 안 뜨는 상황을 없애는 게 목적이다. 서비스가 종료되면 시스템이 알아서 걷어간다.
     * 2. 실패하면 기존 경로([WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY], 권한 필요)로 폴백.
     *
     * `addView`는 반드시 예외를 잡아야 한다. 창 토큰이 유효하지 않으면 `BadTokenException`을 던지는데,
     * 예전엔 `hide()`만 `runCatching`으로 감싸고 여기는 맨몸이라 **접근성 서비스 프로세스가 통째로
     * 죽었다.** 오버레이는 진행 상황을 보여주는 보조 UI일 뿐이라, 못 띄우더라도 자동화 자체는
     * 계속 도는 게 맞다.
     */
    private fun attach(container: View): Boolean {
        val candidates = buildList {
            add(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                add(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
        }

        for (overlayType in candidates) {
            // FLAG_NOT_TOUCHABLE을 걸면 "중단하기" 버튼이 눌리지 않는다. FLAG_NOT_FOCUSABLE만 남겨
            // 오버레이 영역 안의 터치만 받고 키 입력/포커스는 뒤 앱에 그대로 넘긴다.
            // 시안대로 화면 폭 전체를 쓰고 하단에 붙인다.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
                // 하단바(뒤로가기·홈)를 가리면 사용자가 시스템 내비게이션을 못 쓴다.
                // 창 전체를 내비게이션 바 높이만큼 위로 올려서 그 영역을 비워 둔다.
                y = navigationBarHeight()
            }

            val result = runCatching { windowManager.addView(container, params) }
            if (result.isSuccess) {
                Log.i(TAG, "오버레이 표시 (type=$overlayType)")
                return true
            }
            Log.w(TAG, "오버레이 부착 실패 (type=$overlayType)", result.exceptionOrNull())
        }

        Log.e(
            TAG,
            "오버레이를 띄우지 못했습니다. 자동화는 계속 진행되지만 화면 안내와 " +
                "중단/종료 버튼이 보이지 않습니다 (다른 화면 위에 표시 권한 확인 필요).",
        )
        return false
    }

    /**
     * 시스템 내비게이션 바 높이. 3버튼/제스처 어느 쪽이든 시스템 리소스가 현재 모드의 값을 준다.
     * 리소스가 없으면(일부 제스처 전용 기기) 소량의 기본 여백을 쓴다.
     */
    private fun navigationBarHeight(): Int {
        val resId = hostContext.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resId > 0) return hostContext.resources.getDimensionPixelSize(resId)
        return (FALLBACK_NAV_CLEARANCE_DP * hostContext.resources.displayMetrics.density).toInt()
    }

    fun hide() {
        hideHandler.removeCallbacksAndMessages(null)
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        characterView = null
        messageView = null
        spinnerView = null
    }

    private companion object {
        const val TAG = "StatusOverlay"

        /** navigation_bar_height 리소스가 없을 때 쓰는 하단 여백(dp). */
        const val FALLBACK_NAV_CLEARANCE_DP = 24
    }
}
