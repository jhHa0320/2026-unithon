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
 * - 세션은 카카오톡 화면에 들어올 때마다 새로 시작된다. 목표 문장은 [pendingGoal]이 미리 세팅돼
 *   있으면 그걸 쓰고(예: 웨이크업 트리거가 goal을 이미 알고 있는 경우), 없으면 매번 "무엇을
 *   도와드릴까요?"를 TTS로 묻고 STT로 받은 답을 목표로 삼는다 — [startSessionAndCaptureGoal] 참고.
 *   [DEFAULT_GOAL]은 그 STT마저 실패했을 때만 쓰는 최후의 fallback이다.
 * - 완료 판단(status=DONE)을 서버/LLM에 전적으로 맡긴다 — 화면만 보고 "이미 전송했다"를 스스로
 *   못 알아채서 같은 절차를 반복 실행할 수 있다(전송류 화면은 전송 전후가 거의 똑같이 생김).
 *   이 하드 스톱은 아직 없다 — 필요성이 다시 확인되면 재도입 검토.
 */
class TestAccessibilityService : AccessibilityService() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var overlay: StatusOverlayManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingCollect: Runnable? = null

    /** "분석 중" 스피너를 3초 이상 걸릴 때만 보여주기 위한 지연 타이머. 빠르게 끝나는 대부분의
     * 요청(2~3초대)에서는 깜빡임 없이 조용히 지나가고, 느려질 때만 사용자에게 알린다. */
    private val analyzingIndicatorHandler = Handler(Looper.getMainLooper())
    private var pendingAnalyzingIndicator: Runnable? = null

    private var sessionId: String = UUID.randomUUID().toString()
    private var goal: String = DEFAULT_GOAL
    private var isSessionActive = false
    private var isRequestInFlight = false
    private var consecutiveAskUserCount = 0

    /** "무엇을 도와드릴까요?" 답변을 기다리는 동안, 그 사이 들어오는 화면 변경 이벤트가 아직
     * 정해지지 않은 goal로 collectAndDecide를 먼저 실행해버리지 않도록 막는 가드. */
    private var isAwaitingGoal = false

    /** ASK_USER 답변의 종류. CLAUDE.md §5-1 참고: 정보 제공형은 goal에 누적, 확인 응답은 user_speech로 일회성 전달. */
    private enum class AnswerType { INFO, CONFIRMATION }

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
            if (isAwaitingGoal) {
                isAwaitingGoal = false
                voice.stopListening()
            }
            isSessionActive = false
            return
        }

        Log.d(TAG, "a11y 이벤트: type=${AccessibilityEvent.eventTypeToString(event?.eventType ?: 0)} source=${event?.className}")

        if (!isSessionActive) {
            isSessionActive = true
            sessionId = UUID.randomUUID().toString()
            consecutiveAskUserCount = 0
            startSessionAndCaptureGoal()
            return
        }

        if (isAwaitingGoal) return
        scheduleCollectAndDecide()
    }

    /**
     * 새 세션을 시작할 때 목표를 정한다. [pendingGoal]이 미리 세팅돼 있으면(예: adb 테스트로 goal을
     * 지정해서 트리거한 경우) 그대로 쓰고, 없으면 — 즉 카카오톡이 방금 막 떠서 아직 아무 목표도
     * 모르는 보통의 경우 — 바로 "무엇을 도와드릴까요?"를 TTS로 묻고 마이크를 켜서 답변을 목표로 삼는다.
     * 답변을 기다리는 동안 [isAwaitingGoal]을 세워서, 그 사이 들어오는 화면 변경 이벤트가
     * 아직 정해지지 않은 goal로 먼저 요청을 쏘지 않게 막는다.
     */
    private fun startSessionAndCaptureGoal() {
        val preset = pendingGoal
        pendingGoal = null
        if (preset != null) {
            goal = preset
            overlay.showOrUpdate("테스트 시작: $goal")
            scheduleCollectAndDecide()
            return
        }

        isAwaitingGoal = true
        overlay.showOrUpdate("무엇을 도와드릴까요?")
        voice.askAndListen(
            question = "무엇을 도와드릴까요?",
            onAnswer = { answer ->
                isAwaitingGoal = false
                goal = answer
                overlay.showOrUpdate("목표: $goal")
                scheduleCollectAndDecide()
            },
            onError = { err ->
                isAwaitingGoal = false
                Log.w(TAG, "목표 음성 인식 실패($err), 기본 목표로 대체")
                goal = DEFAULT_GOAL
                overlay.showOrUpdate("음성 인식 실패, 기본 목표로 진행합니다.")
                scheduleCollectAndDecide()
            },
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "TestAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        cancelAnalyzingIndicator()
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
        scheduleAnalyzingIndicator()

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
                cancelAnalyzingIndicator()
            }
        }
    }

    /** 3초 안에 응답이 오면 스피너를 아예 안 보여준다 — 대부분의 요청(2~3초대)에서는 화면이
     * 조용하고, 느려질 때만("3초 이상") 지금 분석 중이라는 걸 스피너로 알린다. */
    private fun scheduleAnalyzingIndicator() {
        cancelAnalyzingIndicator()
        val runnable = Runnable { overlay.showAnalyzing() }
        pendingAnalyzingIndicator = runnable
        analyzingIndicatorHandler.postDelayed(runnable, ANALYZING_INDICATOR_DELAY_MS)
    }

    private fun cancelAnalyzingIndicator() {
        pendingAnalyzingIndicator?.let { analyzingIndicatorHandler.removeCallbacks(it) }
        pendingAnalyzingIndicator = null
    }

    private fun handleResponse(response: DecideResponse) {
        when (response.status) {
            DecideStatus.CONTINUE -> {
                consecutiveAskUserCount = 0
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                overlay.showOrUpdate(response.voice_message.ifBlank { "다음 동작 실행 중" })
                performTargetAction(response)
                // 클릭/입력 후 화면이 바뀌면 onAccessibilityEvent가 다시 스케줄링한다.
            }

            DecideStatus.ASK_USER -> {
                consecutiveAskUserCount++
                if (consecutiveAskUserCount > MAX_CONSECUTIVE_ASK_USER) {
                    overlay.showOrUpdate("답변을 계속 이해하지 못해 중단합니다.")
                    isSessionActive = false
                    return
                }
                overlay.showOrUpdate("답변 대기: ${response.voice_message}")
                askUserWithRetry(response.voice_message, attempt = 0)
            }

            DecideStatus.DONE -> {
                consecutiveAskUserCount = 0
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                overlay.showOrUpdate("완료: ${response.voice_message}")
                isSessionActive = false
            }

            DecideStatus.UNSUPPORTED -> {
                consecutiveAskUserCount = 0
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                overlay.showOrUpdate("중단됨: ${response.reason ?: response.voice_message}")
                isSessionActive = false
            }
        }
    }

    /**
     * 질문을 TTS로 읽어준 뒤(끝난 다음에만) 마이크를 켠다 — [VoiceInteractionManager.askAndListen]을 써서
     * TTS 재생 중에 STT가 그 소리를 주워듣는 경합을 막는다. 인식 실패 시 같은 질문을 최대
     * [MAX_ASK_RETRIES]번까지 다시 묻는다.
     */
    private fun askUserWithRetry(question: String, attempt: Int) {
        if (attempt >= MAX_ASK_RETRIES) {
            overlay.showOrUpdate("답변을 인식하지 못했습니다.")
            isSessionActive = false
            return
        }
        voice.askAndListen(
            question = question,
            onAnswer = { answer ->
                // 사용자가 방금 뭐라고 답했는지는 항상 화면에 보여야 한다 — 잘 알아들었는지
                // 스스로 확인할 수 있게.
                overlay.showOrUpdate("입력: $answer")
                routeAnswer(answer)
            },
            onError = { err ->
                overlay.showOrUpdate("답변 인식 실패($err), 다시 물어봅니다.")
                askUserWithRetry(question, attempt + 1)
            },
        )
    }

    /** 답변이 정보 제공형이면 goal에 누적, 확인 응답이면 user_speech로 일회성 전달한다 (CLAUDE.md §5-1). */
    private fun routeAnswer(answer: String) {
        when (classifyAnswer(answer)) {
            AnswerType.CONFIRMATION -> collectAndDecide(userSpeech = answer)
            AnswerType.INFO -> {
                goal = "$goal. $answer"
                collectAndDecide(userSpeech = null)
            }
        }
    }

    /**
     * 짧은 예/아니오류 답변만 확인 응답(CONFIRMATION)으로 분류하고, 나머지는 전부 정보 제공형(INFO)으로
     * 본다. 클라이언트 측 휴리스틱이라 완벽하지 않음 — 오작동이 관찰되면 백엔드가 질문 종류를
     * 알려주는 방식(DecideResponse에 필드 추가)으로 전환을 검토할 것.
     */
    private fun classifyAnswer(text: String): AnswerType {
        val normalized = text.trim()
        return if (normalized in CONFIRMATION_ANSWERS) AnswerType.CONFIRMATION else AnswerType.INFO
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
                "class=${node.className} text=${node.text} desc=${node.contentDescription} refreshed=$refreshed",
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

        /** 이보다 오래 걸리는 요청에만 "분석 중" 스피너를 보여준다. */
        private const val ANALYZING_INDICATOR_DELAY_MS = 3000L

        /** STT 인식 실패 시 같은 질문을 다시 묻는 최대 횟수. */
        private const val MAX_ASK_RETRIES = 3

        /** 세션 하나에서 ASK_USER가 연속으로 나올 수 있는 최대 횟수 — 무한 되묻기 방지. */
        private const val MAX_CONSECUTIVE_ASK_USER = 5

        private val CONFIRMATION_ANSWERS = setOf(
            "응", "네", "예", "넵", "웅", "맞아", "맞아요", "그래", "그래요",
            "좋아", "좋아요", "오케이", "콜", "진행", "진행해줘", "진행해주세요",
            "아니", "아니요", "아니오", "노", "안돼", "안 돼", "싫어",
            "취소", "취소해줘", "취소해주세요", "그만", "그만해줘",
        )

        /**
         * [com.example.pathpilot.wakeup.WakeAndLaunchActivity]가 화면을 깨우고 카카오톡을 실행하기
         * 직전에 세팅해두는 이번 세션의 목표 문장(선택 사항). 다음 [onAccessibilityEvent]가 새 세션을
         * 열 때 한 번 소비하고 null로 되돌린다 — 같은 프로세스 안에서만 오가므로 Intent extra 대신
         * 정적 필드로 간단히 넘긴다. null이면 [startSessionAndCaptureGoal]이 대신 TTS로 되물어서
         * 목표를 구한다 — 보통의 경우(웨이크업 트리거가 goal을 미리 모르는 경우) 여기에 해당한다.
         */
        @Volatile
        var pendingGoal: String? = null
    }
}
