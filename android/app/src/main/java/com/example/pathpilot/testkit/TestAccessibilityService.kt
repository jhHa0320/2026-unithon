package com.example.pathpilot.testkit

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.pathpilot.model.ActionType
import com.example.pathpilot.model.DecideRequest
import com.example.pathpilot.model.DecideResponse
import com.example.pathpilot.model.DecideStatus
import com.example.pathpilot.model.ElementDTO
import com.example.pathpilot.network.RetrofitClient
import com.example.pathpilot.overlay.StatusOverlayManager
import com.example.pathpilot.voice.VoiceInteractionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 멤버 A 개인 테스트용 AccessibilityService — "카카오톡에서 가장 최근에 찍은 사진 보내줘" 같은
 * depth가 얕은 시나리오로 전체 파이프라인(화면 읽기 → /decide 호출 → 클릭/입력 → 반복)을
 * 직접 눌러보기 위한 최소 구현이다.
 *
 * **이건 정식 구현이 아니다.** 멤버 C가 `service/` 아래에 정식 AccessibilityService를 만들면
 * 이 파일과 `res/xml/test_accessibility_service_config.xml`, Manifest의 관련 `<service>`
 * 블록을 지운다 (docs/ARCHITECTURE.md §2).
 *
 * 알려진 한계 (테스트 용도라 감수):
 * - [nodeMap]에 담아둔 AccessibilityNodeInfo는 서버 응답이 오는 사이 화면이 바뀌면 무효화될 수
 *   있다. performAction이 조용히 실패하면 이게 원인일 가능성이 높다.
 * - 세션은 카카오톡 화면에 들어올 때마다 새로 시작되고, 목표 문장은 [DEFAULT_GOAL]로 고정한다.
 */
class TestAccessibilityService : AccessibilityService() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var overlay: StatusOverlayManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingCollect: Runnable? = null

    private var sessionId: String = UUID.randomUUID().toString()
    private var goal: String = DEFAULT_GOAL
    private var isSessionActive = false
    private var isRequestInFlight = false

    /** 이번 스텝에서 화면을 훑을 때 부여한 id -> 실제 노드. §알려진 한계 참고. */
    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        voice = VoiceInteractionManager(this)
        overlay = StatusOverlayManager(this)
        Log.i(TAG, "TestAccessibilityService connected (target=$TARGET_PACKAGE)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()
        if (eventPackage != TARGET_PACKAGE) {
            isSessionActive = false
            return
        }

        if (!isSessionActive) {
            isSessionActive = true
            sessionId = UUID.randomUUID().toString()
            // MainActivity에서 사용자가 입력한 목표가 있으면 그걸 쓰고, 없으면(예: adb로 바로
            // 카톡을 켠 경우) 하드코딩된 기본 목표로 폴백한다.
            goal = GoalHolder.consume() ?: DEFAULT_GOAL
            overlay.showOrUpdate("테스트 시작: $goal")
        }

        Log.d(TAG, "a11y 이벤트: type=${AccessibilityEvent.eventTypeToString(event?.eventType ?: 0)} source=${event?.className}")
        scheduleCollectAndDecide()
    }

    override fun onInterrupt() {
        Log.w(TAG, "TestAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        serviceScope.cancel()
        overlay.hide()
        voice.shutdown()
    }

    /** 화면 변경 이벤트가 연속으로 들어와도 마지막 한 번만 처리한다 (디바운스). */
    private fun scheduleCollectAndDecide() {
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable { collectAndDecide(userSpeech = null) }
        pendingCollect = runnable
        debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
    }

    /** 현재 화면을 ElementDTO 목록으로 만들어 /decide를 호출한다. */
    private fun collectAndDecide(userSpeech: String?) {
        if (isRequestInFlight) {
            Log.d(TAG, "이전 요청 진행 중 — 이번 스캔은 건너뜀")
            return
        }
        val root = rootInActiveWindow ?: return

        nodeMap.clear()
        val elements = mutableListOf<ElementDTO>()
        var nextId = 1

        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            if (node.isClickable || !text.isNullOrBlank() || !description.isNullOrBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                // 아직 레이아웃이 안 잡힌 노드는 bounds가 [0,0,0,0] 등 폭/높이 0으로 나온다.
                // 백엔드가 bounds 하나라도 잘못되면 요청 전체를 422로 거부하므로 여기서 미리 거른다.
                val hasValidBounds = bounds.left < bounds.right && bounds.top < bounds.bottom
                if (hasValidBounds) {
                    val id = nextId++
                    nodeMap[id] = node
                    elements.add(
                        ElementDTO(
                            id = id,
                            text = text,
                            content_description = description,
                            class_name = node.className?.toString() ?: "unknown",
                            clickable = node.isClickable,
                            bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                        ),
                    )
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it) }
            }
        }
        visit(root)

        if (elements.isEmpty()) return

        isRequestInFlight = true
        overlay.showOrUpdate("화면 분석 중… (${elements.size}개 요소)")

        val request = DecideRequest(
            session_id = sessionId,
            goal = goal,
            app_package = TARGET_PACKAGE,
            elements = elements,
            user_speech = userSpeech,
        )

        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.decide(request)
                }
                handleResponse(response)
            } catch (e: Exception) {
                Log.e(TAG, "decide 호출 실패", e)
                overlay.showOrUpdate("서버 호출 실패: ${e.message}")
            } finally {
                isRequestInFlight = false
            }
        }
    }

    private fun handleResponse(response: DecideResponse) {
        if (response.voice_message.isNotBlank()) {
            voice.speak(response.voice_message)
        }

        when (response.status) {
            DecideStatus.CONTINUE -> {
                overlay.showOrUpdate(response.voice_message.ifBlank { "다음 동작 실행 중" })
                performTargetAction(response)
                // 클릭/입력 후 화면이 바뀌면 onAccessibilityEvent가 다시 스케줄링한다.
            }

            DecideStatus.ASK_USER -> {
                overlay.showOrUpdate("답변 대기: ${response.voice_message}")
                voice.listenOnce(
                    onResult = { answer -> collectAndDecide(userSpeech = answer) },
                    onError = { err -> overlay.showOrUpdate("답변 인식 실패: $err") },
                )
            }

            DecideStatus.DONE -> {
                overlay.showOrUpdate("완료: ${response.voice_message}")
                isSessionActive = false
            }

            DecideStatus.UNSUPPORTED -> {
                overlay.showOrUpdate("중단됨: ${response.reason ?: response.voice_message}")
                isSessionActive = false
            }
        }
    }

    private fun performTargetAction(response: DecideResponse) {
        val node = response.target_node_id?.let { nodeMap[it] }
        if (node == null || response.action_type == null) {
            Log.w(TAG, "target node를 찾지 못함 (target_node_id=${response.target_node_id})")
            return
        }

        // 서버 응답이 오는 사이 화면이 바뀌면 이 노드는 이미 죽은 참조일 수 있다.
        // refresh()는 시스템에 재동기화를 시도하고, 원본 뷰가 사라졌으면 false를 반환한다 —
        // "클릭이 조용히 실패해서 제자리걸음" 가설을 확인하기 위한 핵심 로그.
        val refreshed = node.refresh()
        Log.i(
            TAG,
            "액션 실행: id=${response.target_node_id} type=${response.action_type} " +
                "class=${node.className} text=${node.text} refreshed=$refreshed",
        )
        if (!refreshed) {
            Log.w(TAG, "노드 refresh 실패 — 화면이 이미 바뀌어 이 노드는 무효화됐을 가능성 높음")
        }

        val actionResult = when (response.action_type) {
            ActionType.CLICK -> node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ActionType.SET_TEXT -> {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        response.input_value ?: "",
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            else -> false
        }
        Log.i(TAG, "performAction 결과: $actionResult (id=${response.target_node_id})")
        if (!actionResult) {
            Log.w(TAG, "performAction 실패 — 이 스텝은 화면에 아무 영향을 못 줬을 것")
        }
    }

    companion object {
        private const val TAG = "TestA11yService"
        private const val TARGET_PACKAGE = "com.kakao.talk"
        private const val DEFAULT_GOAL = "카카오톡에서 가장 최근에 찍은 사진 보내줘"
        private const val DEBOUNCE_MS = 500L
    }
}
