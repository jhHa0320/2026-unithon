package com.example.pathpilot.testkit

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * [TARGET_PACKAGES]에 등록된 앱(카카오톡/카카오택시/코레일톡)만 대상으로 한다 — 목록 밖 앱은
 * 이벤트 자체가 안 들어온다(`res/xml/test_accessibility_service_config.xml`의 packageNames가 OS
 * 레벨 필터). 어떤 앱을 켤지는 [com.example.pathpilot.wakeup.WakeAndLaunchActivity]가 goal
 * 문장을 보고 미리 정해서 지원 앱 중 하나를 실행해준다.
 *
 * 알려진 한계 (테스트 용도라 감수):
 * - [nodeMap]에 담아둔 AccessibilityNodeInfo는 서버 응답이 오는 사이 화면이 바뀌면 무효화될 수
 *   있다. performAction이 조용히 실패하면 이게 원인일 가능성이 높다.
 * - 세션은 웨이크 흐름이 [sessionRequested]를 세운 뒤 대상 앱 화면이 뜰 때만 시작된다(완료/중단
 *   후 앱 화면에 남아 있어도 다시 묻지 않는다). 목표 문장은 [pendingGoal]이 미리 세팅돼
 *   있으면 그걸 쓰고(예: 웨이크업 트리거가 goal을 이미 알고 있는 경우), 없으면 "무엇을
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

    /** 지금 디바운스 중인 이벤트 burst가 언제 시작됐는지. [scheduleCollectAndDecide] 참고. */
    private var firstEventInBurstAt: Long? = null

    /** "분석 중" 스피너를 3초 이상 걸릴 때만 보여주기 위한 지연 타이머. 빠르게 끝나는 대부분의
     * 요청(2~3초대)에서는 깜빡임 없이 조용히 지나가고, 느려질 때만 사용자에게 알린다. */
    private val analyzingIndicatorHandler = Handler(Looper.getMainLooper())
    private var pendingAnalyzingIndicator: Runnable? = null

    private var sessionId: String = UUID.randomUUID().toString()
    private var goal: String = DEFAULT_GOAL
    private var isSessionActive = false
    private var isRequestInFlight = false
    private var consecutiveAskUserCount = 0

    /** 세션 세대 번호. 중단/새 세션마다 올라가고, 서버 응답이 도착했을 때 이 값이 요청 시점과
     * 다르면(= 그 사이 사용자가 중단했거나 세션이 바뀌었으면) 응답을 버린다 — 중단을 눌렀는데
     * 날아가 있던 응답이 뒤늦게 도착해 클릭을 실행해버리는 사고 방지. */
    private var sessionEpoch = 0

    /** 지금 세션이 진행 중인 앱. [TARGET_PACKAGES] 중 하나이며, 실제로 이벤트가 들어온 값으로
     * 채워진다 — 하드코딩된 단일 상수가 아니라 "지금 어느 앱 화면인지"를 그대로 반영한다. */
    private var currentPackage: String = TARGET_PACKAGES.first()

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
        overlay.onStopClicked = { stopSession() }
        Log.i(TAG, "TestAccessibilityService connected (targets=$TARGET_PACKAGES)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString()
        if (eventPackage == null || eventPackage !in TARGET_PACKAGES) {
            if (isAwaitingGoal) {
                isAwaitingGoal = false
                voice.stopListening()
            }
            isSessionActive = false
            return
        }

        Log.d(TAG, "a11y 이벤트: type=${AccessibilityEvent.eventTypeToString(event?.eventType ?: 0)} source=${event?.className} package=$eventPackage")

        // 지원 앱 목록 안에서 다른 앱으로 바뀐 경우(예: 카카오톡 -> 카카오택시)도 새 세션으로
        // 취급한다 — 이전 세션의 goal/이력이 엉뚱한 앱에 이어붙는 걸 막는다.
        val packageChanged = eventPackage != currentPackage
        currentPackage = eventPackage

        if (!isSessionActive) {
            // 세션이 없을 때는 웨이크 흐름([sessionRequested])이 명시적으로 요청한 경우에만 새로
            // 시작한다. 예전엔 대상 앱 화면이기만 하면 무조건 세션을 열어서, 완료/중단 후에도
            // 사용자가 앱을 계속 쓰는 동안 "무엇을 도와드릴까요?"를 반복해 묻고 마이크를 계속
            // 열어뒀다(2026-08-26 보고: "계속 사용자 입력을 대기하는 것 같다").
            if (!sessionRequested) return
            sessionRequested = false
            beginNewSession()
            return
        }

        if (packageChanged) {
            beginNewSession()
            return
        }

        if (isAwaitingGoal) return
        scheduleCollectAndDecide()
    }

    private fun beginNewSession() {
        isSessionActive = true
        sessionEpoch++
        sessionId = UUID.randomUUID().toString()
        consecutiveAskUserCount = 0
        startSessionAndCaptureGoal()
    }

    /**
     * 진행 중인 자동화를 즉시 중단한다 — 오버레이 "그만하기" 버튼과 음성 중단 명령
     * ([isStopCommand])의 공통 경로. 예약된 화면 스캔·마이크·TTS를 전부 멈추고, 날아가 있는
     * 서버 응답은 [sessionEpoch] 증가로 무효화한다.
     */
    private fun stopSession() {
        sessionEpoch++
        isSessionActive = false
        isAwaitingGoal = false
        sessionRequested = false
        pendingGoal = null
        consecutiveAskUserCount = 0
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        pendingCollect = null
        firstEventInBurstAt = null
        cancelAnalyzingIndicator()
        voice.stopListening()
        voice.stopSpeaking()
        Log.i(TAG, "사용자 요청으로 세션 중단 (epoch=$sessionEpoch)")
        voice.speak("네, 중단했어요.")
        overlay.showOrUpdate("중단했습니다.", showStop = false)
        overlay.hideAfterDelay(OVERLAY_HIDE_DELAY_MS)
    }

    /** 세션을 조용히 끝낸다(완료/실패 안내 후). 오버레이는 잠시 보여주고 스스로 사라진다. */
    private fun endSession(message: String) {
        isSessionActive = false
        overlay.showOrUpdate(message, showStop = false)
        overlay.hideAfterDelay(OVERLAY_HIDE_DELAY_MS)
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
                if (isStopCommand(answer)) {
                    stopSession()
                    return@askAndListen
                }
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
        firstEventInBurstAt = null
        cancelAnalyzingIndicator()
        serviceScope.cancel()
        overlay.hide()
        voice.shutdown()
    }

    /**
     * 화면 변경 이벤트가 연속으로 들어와도 마지막 한 번만 처리한다 (디바운스).
     *
     * 카카오택시의 지도 화면처럼 콘텐츠가 초당 5~6번씩 계속 바뀌는 화면에서는, 매 이벤트마다
     * 디바운스 타이머가 리셋되기만 해서 **500ms의 조용한 순간이 영영 안 오면 collectAndDecide가
     * 한 번도 실행되지 못하는** starvation이 실기기에서 재현됐다(2026-08-25, "목적지 입력 후
     * 진행 안 됨"). 그래서 이 burst가 시작된 후 [MAX_BURST_WAIT_MS]가 지나면, 계속 이벤트가
     * 들어오는 중이어도 강제로 한 번 실행한다.
     */
    private fun scheduleCollectAndDecide() {
        val now = SystemClock.uptimeMillis()
        val burstStart = firstEventInBurstAt ?: now.also { firstEventInBurstAt = it }
        val elapsedSinceBurstStart = now - burstStart
        val delay = if (elapsedSinceBurstStart >= MAX_BURST_WAIT_MS) 0L else DEBOUNCE_MS

        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        val runnable = Runnable {
            firstEventInBurstAt = null
            collectAndDecide(userSpeech = null)
        }
        pendingCollect = runnable
        debounceHandler.postDelayed(runnable, delay)
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
            // Compose 앱(코레일톡 등)은 clickable 컨테이너에 라벨이 없고 그 안의 클릭 불가
            // TextView에만 "바로 예매" 같은 텍스트가 있는 경우가 많다. 라벨 없는 clickable
            // 노드를 그대로 보내면 LLM이 어느 버튼인지 알 수 없어 엉뚱한 요소만 반복
            // 클릭한다(2026-08-26 코레일톡 실측) — 자손 텍스트를 모아 라벨을 합성한다.
            val description = node.contentDescription?.toString()
                ?: if (node.isClickable && text.isNullOrBlank()) synthesizeLabel(node) else null
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
            app_package = currentPackage,
            elements = elements,
            user_speech = userSpeech,
        )
        val requestEpoch = sessionEpoch

        serviceScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.decide(request)
                }
                if (requestEpoch != sessionEpoch) {
                    Log.i(TAG, "중단/세션 교체 후 도착한 응답 무시 (epoch $requestEpoch != $sessionEpoch)")
                } else {
                    handleResponse(response)
                }
            } catch (e: Exception) {
                Log.e(TAG, "decide 호출 실패", e)
                if (requestEpoch == sessionEpoch) {
                    overlay.showOrUpdate("서버 호출 실패: ${e.message}")
                }
            } finally {
                isRequestInFlight = false
                cancelAnalyzingIndicator()
            }
        }
    }

    /**
     * 자체 text/contentDescription이 없는 clickable 노드의 라벨을 자손 노드의 텍스트로 합성한다.
     * 자손 전체를 훑되 [SYNTHESIZED_LABEL_MAX_PARTS]개 텍스트까지만 모으고
     * [SYNTHESIZED_LABEL_MAX_LENGTH]자로 자른다 — 화면 전체를 감싸는 clickable 컨테이너가
     * 거대한 라벨을 만들지 않게 하기 위해서다. 모을 텍스트가 없으면 null.
     */
    private fun synthesizeLabel(node: AccessibilityNodeInfo): String? {
        val parts = mutableListOf<String>()
        fun walk(child: AccessibilityNodeInfo) {
            if (parts.size >= SYNTHESIZED_LABEL_MAX_PARTS) return
            child.text?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
            child.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it.trim()) }
            for (i in 0 until child.childCount) {
                if (parts.size >= SYNTHESIZED_LABEL_MAX_PARTS) return
                child.getChild(i)?.let { walk(it) }
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it) }
        }
        return parts.joinToString(" ").take(SYNTHESIZED_LABEL_MAX_LENGTH).ifBlank { null }
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
                    endSession("답변을 계속 이해하지 못해 중단합니다.")
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
                endSession("완료: ${response.voice_message}")
            }

            DecideStatus.UNSUPPORTED -> {
                consecutiveAskUserCount = 0
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                endSession("중단됨: ${response.reason ?: response.voice_message}")
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
            endSession("답변을 인식하지 못했습니다.")
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

    /** 답변이 중단 명령이면 즉시 세션을 끝내고, 정보 제공형이면 goal에 누적, 확인 응답이면
     * user_speech로 일회성 전달한다 (CLAUDE.md §5-1). 중단 명령은 서버를 거치지 않고 클라이언트가
     * 바로 처리한다 — 사용자가 멈추라는데 한 번 더 왕복하는 사이 클릭이 나가면 안 되기 때문. */
    private fun routeAnswer(answer: String) {
        if (isStopCommand(answer)) {
            stopSession()
            return
        }
        when (classifyAnswer(answer)) {
            AnswerType.CONFIRMATION -> collectAndDecide(userSpeech = answer)
            AnswerType.INFO -> {
                goal = "$goal. $answer"
                collectAndDecide(userSpeech = null)
            }
        }
    }

    /** "그만"/"취소"/"중단"류 발화인지. 공백을 지운 뒤 키워드 포함 여부로 본다 — STT가 "그만 해줘"처럼
     * 띄어쓰기를 섞어도 잡힌다. 정보 제공형 답변에 이 단어들이 들어갈 일은 이 도메인에선 드물다고 보고
     * 단순 포함 매칭을 쓴다(오탐이 관찰되면 정확 일치 목록으로 좁힐 것). */
    private fun isStopCommand(text: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), "")
        return STOP_KEYWORDS.any { normalized.contains(it) }
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

        /** 이 서비스가 반응하는 앱 목록. `res/xml/test_accessibility_service_config.xml`의
         * packageNames와 반드시 같이 맞춰야 한다 — 저쪽에 없는 패키지를 여기 추가해도 이벤트
         * 자체가 시스템에서 걸러져서 안 들어온다. */
        private val TARGET_PACKAGES = setOf("com.kakao.talk", "com.kakao.taxi", "com.korail.talk")
        private const val DEFAULT_GOAL = "카카오톡에서 가장 최근에 찍은 사진 보내줘"
        private const val DEBOUNCE_MS = 500L

        /** 이벤트가 쉬지 않고 계속 들어와도(예: 지도 화면 애니메이션) 이 시간이 지나면 강제로
         * 한 번 스캔한다 — 디바운스 starvation 방지. */
        private const val MAX_BURST_WAIT_MS = 1500L

        /** 이보다 오래 걸리는 요청에만 "분석 중" 스피너를 보여준다. */
        private const val ANALYZING_INDICATOR_DELAY_MS = 3000L

        /** [synthesizeLabel]이 자손에서 모으는 텍스트 조각 수/길이 상한. */
        private const val SYNTHESIZED_LABEL_MAX_PARTS = 3
        private const val SYNTHESIZED_LABEL_MAX_LENGTH = 60

        /** STT 인식 실패 시 같은 질문을 다시 묻는 최대 횟수. */
        private const val MAX_ASK_RETRIES = 3

        /** 세션 하나에서 ASK_USER가 연속으로 나올 수 있는 최대 횟수 — 무한 되묻기 방지. */
        private const val MAX_CONSECUTIVE_ASK_USER = 5

        /** 세션 종료(완료/중단/실패) 안내를 이만큼 보여준 뒤 오버레이를 스스로 치운다. */
        private const val OVERLAY_HIDE_DELAY_MS = 5000L

        /** [isStopCommand]가 보는 중단 명령 키워드. 공백 제거 후 부분 일치로 매칭한다. */
        private val STOP_KEYWORDS = setOf(
            "취소", "그만", "중단", "멈춰", "멈춰줘", "스톱", "스탑", "하지마", "종료",
        )

        // 취소/그만류는 STOP_KEYWORDS가 먼저 잡으므로 여기엔 없다.
        private val CONFIRMATION_ANSWERS = setOf(
            "응", "네", "예", "넵", "웅", "맞아", "맞아요", "그래", "그래요",
            "좋아", "좋아요", "오케이", "콜", "진행", "진행해줘", "진행해주세요",
            "아니", "아니요", "아니오", "노", "안돼", "안 돼", "싫어",
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

        /**
         * [com.example.pathpilot.wakeup.WakeAndLaunchActivity]가 "이제 자동화 세션을 시작해도
         * 된다"고 알리는 신호. [pendingGoal]과 달리 goal이 없어도(=서비스가 TTS로 되물어야 하는
         * 경우에도) 세워진다. 이 신호 없이는 대상 앱 화면에 있어도 세션을 시작하지 않는다 —
         * 완료/중단 후 사용자가 앱을 계속 쓰는 동안 "무엇을 도와드릴까요?"를 반복해 묻던 문제의 수정.
         */
        @Volatile
        var sessionRequested: Boolean = false
    }
}
