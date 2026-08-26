package com.example.pathpilot.testkit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.pathpilot.accessibility.AccessibilityStatus
import com.example.pathpilot.model.ActionType
import com.example.pathpilot.model.DecideRequest
import com.example.pathpilot.model.DecideResponse
import com.example.pathpilot.model.DecideStatus
import com.example.pathpilot.model.ElementDTO
import com.example.pathpilot.network.RetrofitClient
import com.example.pathpilot.overlay.CharacterExpression
import com.example.pathpilot.overlay.StatusOverlayManager
import com.example.pathpilot.voice.SpeechCommands
import com.example.pathpilot.voice.SpeechCommands.AnswerType
import com.example.pathpilot.voice.VoiceInteractionManager
import com.example.pathpilot.wakeup.WakeAndLaunchActivity
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

    /** "마지막 진행 후 아무 일도 안 일어남"을 감지해 스스로 복구하는 워치독. [armWatchdog] 참고. */
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var pendingWatchdog: Runnable? = null
    private var watchdogRetries = 0

    private var sessionId: String = UUID.randomUUID().toString()
    private var goal: String = DEFAULT_GOAL
    /** 세션이 살아 있는지. 값이 바뀔 때마다 [isAutomationRunning]에 그대로 반영한다 —
     * MainActivity의 웨이크 루프가 이 값을 보고 마이크를 건드리지 않는다(경합 방지). */
    private var isSessionActive = false
        set(value) {
            field = value
            isAutomationRunning = value
        }

    private var isRequestInFlight = false
    private var consecutiveAskUserCount = 0

    /** 요청이 날아가 있는 동안 들어온 화면 변경을 기억해 두는 플래그. 예전엔 그냥 버렸는데,
     * 버려진 그 스캔이 마지막 이벤트였으면 이후 아무 일도 일어나지 않아 세션이 그대로 멈췄다. */
    private var pendingRescan = false

    /**
     * 이 시각([SystemClock.uptimeMillis] 기준)까지는 화면이 아직 정착 중으로 보고 **스캔하지 않는다.**
     *
     * 클릭 직후에는 화면 전환 애니메이션이 돌아가는데, 그 사이 화면은 이전 화면과 새 화면이 섞인
     * 중간 상태다. 그걸 읽어서 LLM에 보내면 "화면을 제대로 보지도 않고 다음 동작을 실행하는"
     * 결과가 된다. 예전에는 이 개념 자체가 없어서, 디바운스가 [MAX_BURST_WAIT_MS] 상한에 걸리면
     * 애니메이션 한복판에서도 강제로 스캔이 나갔다.
     */
    private var settleUntil = 0L

    /**
     * 직전에 서버로 보낸 화면의 지문. 같은 화면을 두 번 판단해 **같은 곳을 또 누르는 것**을 막는다.
     *
     * 화면 전환이 끝나고 정적이 되면 접근성 이벤트가 더 오지 않는다. 그러면 워치독이 재스캔하는데,
     * 화면이 그대로이므로 LLM도 같은 판단을 내리고 같은 버튼이 한 번 더 눌린다 — 전송처럼
     * 되돌릴 수 없는 동작에서는 그대로 사고다. 사용자 답변이 새로 들어왔거나 goal이 바뀐
     * 경우에는 화면이 같아도 다시 물어야 하므로, 그때는 이 값을 null로 지워 강제로 재판단시킨다.
     */
    private var lastScreenFingerprint: Int? = null

    /**
     * 체크박스 화면 안정화 게이트 상태([collectAndDecide] 참고). 직전 확인 스캔의 지문과
     * 재시도 횟수 — 같은 지문이 두 번 연속 나오면 화면이 정착한 것으로 보고 판단을 허용한다.
     */
    private var stabilityProbeFingerprint: Int? = null
    private var stabilityRetries = 0

    /** 판단 전에 화면을 아래까지 훑는 프리스크롤이 진행 중인지. 이 동안 들어오는 접근성
     * 이벤트는 우리가 만든 스크롤의 메아리이므로 새 스캔을 예약하지 않는다. */
    private var isPrescrolling = false
    private var pendingPrescrollStep: Runnable? = null

    /** 방금 실행한 액션의 대상 시그니처. 다음 스캔에서 화면이 그대로면 "그 액션은 무효였다"로
     * 기록하는 데 쓴다. */
    private var pendingActionKey: String? = null

    /** 무효로 판명된 클릭 대상. performAction이 true를 돌려주고도 화면을 못 바꾼 노드다 —
     * 같은 대상을 또 고르면 performAction 대신 곧장 좌표 탭(진짜 터치)으로 간다. */
    private var ineffectiveActionKey: String? = null

    /** 직전에 말한 진행 멘트와 반복 횟수. 같은 화면을 재탐색(프리스크롤 등)하는 동안 서버가
     * 같은 voice_message를 또 보내면, 그대로 반복하는 대신 "잠시만 기다려 주세요" 계열의
     * 대기 문구로 바꿔 말한다 — 같은 멘트가 두 번 나오면 사용자는 앱이 이상하다고 느낀다. */
    private var lastProgressMessage: String? = null
    private var progressRepeatCount = 0

    /**
     * 다음 스캔에서 프리스크롤을 건너뛸지. 직전 응답이 CONTINUE("~할게요" 식 행동 선언)였으면
     * LLM이 이미 무엇을 할지 알고 있으므로 화면 전체 훑기가 불필요하다 — 코레일처럼 목록이
     * 긴 화면에서 매 스텝 스크롤 왕복을 하면 진행이 한없이 늘어진다. 되묻기 답변 후,
     * 진행 정체(워치독), 실패 복구, 새 세션처럼 "다시 파악이 필요한 시점"에만 훑는다.
     */
    private var skipNextPrescroll = false

    /** 마지막으로 프리스크롤을 수행한 화면의 지문. 같은 화면에서 스캔이 반복될 때
     * (워치독 재시도 등) 스크롤 왕복을 다시 하지 않기 위한 기억이다 — 이게 없으면
     * "복귀 스크롤 -> 이벤트 -> 재스캔 -> 다시 프리스크롤"의 무한 왕복이 생긴다. */
    private var lastPrescrolledFingerprint: Int? = null

    /** 화면 민감도. 어떤 화면에서 무엇을 멈출지 결정한다. */
    private enum class ScreenSensitivity {
        /** 일반 화면 — 오버레이·자동화 모두 정상. */
        NONE,

        /** 약관 동의 등: 오버레이만 걷는다(체크박스를 가리지 않게). **자동화는 계속** —
         * 체크박스는 AI가 눌러야 할 대상이다. */
        OVERLAY_ONLY,

        /** 결제 비밀번호·생체인증: 오버레이를 걷고 자동 조작도 멈춘다.
         * 비밀번호는 AI가 대신 누를 대상이 아니다. */
        FULL_PAUSE,
    }

    /**
     * 민감 화면 때문에 오버레이를 걷어둔 상태인지(모드 포함). 다음 화면으로 넘어가
     * 민감 요소가 사라지면 오버레이를 되살린다.
     */
    private var suppressedMode = ScreenSensitivity.NONE

    /** 세션 세대 번호. 중단/새 세션마다 올라가고, 서버 응답이 도착했을 때 이 값이 요청 시점과
     * 다르면(= 그 사이 사용자가 중단했거나 세션이 바뀌었으면) 응답을 버린다 — 중단을 눌렀는데
     * 날아가 있던 응답이 뒤늦게 도착해 클릭을 실행해버리는 사고 방지. */
    private var sessionEpoch = 0

    /** 지금 세션이 진행 중인 앱. [TARGET_PACKAGES] 중 하나이며, 실제로 이벤트가 들어온 값으로
     * 채워진다 — 하드코딩된 단일 상수가 아니라 "지금 어느 앱 화면인지"를 그대로 반영한다. */
    private var currentPackage: String = PRIMARY_PACKAGES.first()

    /** "무엇을 도와드릴까요?" 답변을 기다리는 동안, 그 사이 들어오는 화면 변경 이벤트가 아직
     * 정해지지 않은 goal로 collectAndDecide를 먼저 실행해버리지 않도록 막는 가드. */
    private var isAwaitingGoal = false

    // ASK_USER 답변의 종류(AnswerType)는 SpeechCommands에 있다. CLAUDE.md §5-1 참고:
    // 정보 제공형은 goal에 누적, 확인 응답은 user_speech로 일회성 전달.

    /** 이번 스텝에서 화면을 훑을 때 부여한 id -> 실제 노드. §알려진 한계 참고. */
    private val nodeMap = mutableMapOf<Int, AccessibilityNodeInfo>()

    /**
     * **여기서 예외가 새어나가면 시스템이 이 서비스를 죽이고 `Crashed services`로 표시한다.**
     * 그 뒤가 고약하다 — 설정 화면에는 여전히 "켜짐"으로 보이는데 실제로는 접근성 이벤트가
     * 하나도 안 들어오고 오버레이 창도 함께 사라진다. 사용자 눈에는 "버튼이 없어지고 아무것도
     * 안 되는" 상태이고, 원인을 짐작할 단서가 화면에 하나도 없다(2026-08-26 실기기 확인).
     * 그래서 초기화 실패를 예외로 터뜨리지 않고 로그로만 남긴다.
     */
    override fun onServiceConnected() {
        super.onServiceConnected()
        val initialized = runCatching {
            voice = VoiceInteractionManager(this)
            overlay = StatusOverlayManager(this)
            overlay.onStopClicked = { stopAndListenForNewRequest() }
            overlay.onExitClicked = { exitApp() }
        }
        if (initialized.isFailure) {
            Log.e(TAG, "서비스 초기화 실패 — 이벤트를 받지 않는다", initialized.exceptionOrNull())
            return
        }
        isServiceConnected = true
        // "이 기기에서 한 번은 정상으로 켜졌다"를 남긴다. 나중에 서비스가 죽어 시스템이 접근성
        // 목록에서 우리를 지워버렸을 때, 안내 화면이 "안 켜셨습니다"가 아니라 "켜져 있었는데
        // 풀렸습니다"라고 정확히 말할 수 있게 하는 근거다.
        AccessibilityStatus.rememberEnabled(this)
        Log.i(TAG, "TestAccessibilityService connected (targets=$TARGET_PACKAGES)")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isServiceConnected = false
        Log.i(TAG, "TestAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    /**
     * 이벤트 처리 중 터진 예외가 밖으로 나가면 서비스 전체가 죽는다([onServiceConnected] 주석 참고).
     * 화면 구조는 앱마다 제각각이고 노드는 언제든 무효화되므로, 한 이벤트의 실패가 세션 전체를
     * 끝내지 않도록 여기서 막는다.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isServiceConnected) return
        runCatching { handleAccessibilityEvent(event) }
            .onFailure { Log.e(TAG, "이벤트 처리 중 예외 — 이번 이벤트만 건너뛴다", it) }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent?) {
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

        // 주 앱(카카오톡/카카오택시/코레일톡)끼리 바뀐 경우만 새 세션으로 취급한다 — 이전 세션의
        // goal/이력이 엉뚱한 앱에 이어붙는 걸 막는다. 사진 선택기 같은 보조 화면([AUXILIARY_PACKAGES])
        // 으로 넘어간 것은 같은 목표를 수행하는 도중이므로 세션을 그대로 이어간다.
        val primaryAppChanged =
            eventPackage in PRIMARY_PACKAGES && currentPackage != eventPackage &&
                currentPackage in PRIMARY_PACKAGES
        currentPackage = eventPackage

        // 웨이크 신호는 세션 진행 여부와 무관하게 항상 여기서 소비한다. 예전엔 !isSessionActive일
        // 때만 소비해서, 세션이 살아 있는 동안 웨이크가 또 걸리면 플래그가 true인 채로 남았다가
        // 세션이 끝난 뒤 아무 이벤트에서나 의도치 않은 새 세션이 열렸다.
        if (sessionRequested) {
            sessionRequested = false
            beginNewSession()
            return
        }

        // 세션이 없을 때는 웨이크 흐름([sessionRequested])이 명시적으로 요청한 경우에만 새로
        // 시작한다. 예전엔 대상 앱 화면이기만 하면 무조건 세션을 열어서, 완료/중단 후에도
        // 사용자가 앱을 계속 쓰는 동안 "무엇을 도와드릴까요?"를 반복해 묻고 마이크를 계속
        // 열어뒀다(2026-08-26 보고: "계속 사용자 입력을 대기하는 것 같다").
        if (!isSessionActive) return

        if (primaryAppChanged) {
            beginNewSession()
            return
        }

        if (isAwaitingGoal) return
        scheduleCollectAndDecide()
    }

    private fun beginNewSession() {
        // 이전 세션의 잔여 타이머·음성 콜백을 먼저 확실히 끊는다. epoch를 올린 뒤 정리해야
        // 정리 과정에서 살아남은 콜백들도 새 epoch와 비교돼 무시된다.
        sessionEpoch++
        resetTransientState()
        isSessionActive = true
        isAwaitingGoal = false
        sessionId = UUID.randomUUID().toString()
        consecutiveAskUserCount = 0
        watchdogRetries = 0
        startSessionAndCaptureGoal()
    }

    /**
     * 예약된 화면 스캔·워치독·스피너·마이크·TTS를 전부 멈춘다. **[pendingGoal]과
     * [isSessionActive]는 건드리지 않는다** — 세션을 끝낼 때([stopSessionCore])와 새로 시작할
     * 때([beginNewSession]) 모두 쓰는 공통 정리라서, 그 둘의 의미가 정반대인 필드는 호출부가 정한다.
     */
    private fun resetTransientState() {
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        pendingCollect = null
        firstEventInBurstAt = null
        pendingRescan = false
        isPrescrolling = false
        pendingPrescrollStep?.let { debounceHandler.removeCallbacks(it) }
        pendingPrescrollStep = null
        lastPrescrolledFingerprint = null
        skipNextPrescroll = false
        lastProgressMessage = null
        progressRepeatCount = 0
        pendingActionKey = null
        ineffectiveActionKey = null
        suppressedMode = ScreenSensitivity.NONE
        overlay.unsuppress()
        settleUntil = 0L
        // 세션이 바뀌면 직전 화면과의 비교는 의미가 없다. 남겨두면 새 세션의 첫 스캔이
        // "직전과 같은 화면"으로 오인돼 통째로 건너뛰어진다.
        lastScreenFingerprint = null
        stabilityProbeFingerprint = null
        stabilityRetries = 0
        disarmWatchdog()
        cancelAnalyzingIndicator()
        // nodeMap은 AccessibilityNodeInfo(= 시스템과의 IPC 핸들)를 화면 하나당 수백 개까지
        // 들고 있다. 세션이 끝났는데 계속 붙들고 있을 이유가 없다.
        nodeMap.clear()
        // stopListening()+stopSpeaking()이 아니라 cancelAll()이어야 한다 — 이미 엔진에 등록돼
        // 취소할 수 없는 TTS/STT 콜백까지 무효화해야 중단 후 마이크가 되살아나지 않는다.
        voice.cancelAll()
    }

    /**
     * 진행 중인 자동화를 즉시 멈춘다 — 예약된 화면 스캔·마이크·TTS를 전부 멈추고, 날아가 있는
     * 서버 응답은 [sessionEpoch] 증가로 무효화한다. 이후 무엇을 할지는 호출부가 정한다
     * ([stopAndListenForNewRequest]는 새 요청 청취, [exitApp]은 서비스 종료).
     */
    private fun stopSessionCore() {
        sessionEpoch++
        isSessionActive = false
        isAwaitingGoal = false
        sessionRequested = false
        pendingGoal = null
        consecutiveAskUserCount = 0
        resetTransientState()
    }

    /**
     * "중단하기" 버튼과 음성 중단 명령([isStopCommand])의 공통 경로: 하던 일을 멈추고
     * 곧바로 새 요청을 듣는다(사용자 요구사항 — 중단 후 새로운 요구사항 청취).
     */
    private fun stopAndListenForNewRequest() {
        stopSessionCore()
        Log.i(TAG, "사용자 요청으로 세션 중단, 새 요청 대기 (epoch=$sessionEpoch)")
        listenForNewRequest(attempt = 0)
    }

    private fun listenForNewRequest(attempt: Int) {
        if (attempt >= MAX_ASK_RETRIES) {
            endSession("요청을 인식하지 못했습니다. 필요하시면 중단하기 버튼을 다시 눌러주세요.")
            return
        }
        overlay.showOrUpdate("무엇을 도와드릴까요?", CharacterExpression.CURIOUS)
        val epoch = sessionEpoch
        voice.askAndListen(
            question = if (attempt == 0) "네, 멈췄어요. 무엇을 도와드릴까요?" else "무엇을 도와드릴까요?",
            onAnswer = { answer ->
                if (isStaleEpoch(epoch)) return@askAndListen
                if (isStopCommand(answer)) {
                    voice.speak("알겠습니다.")
                    endSession("대기 중입니다. 필요하시면 중단하기 버튼을 눌러주세요.")
                    return@askAndListen
                }
                startRequestedGoal(answer)
            },
            onError = {
                if (isStaleEpoch(epoch)) return@askAndListen
                // 재질문 전 한 박자 쉰다 — 즉시 재오픈하면 인식 실패가 연쇄된다.
                debounceHandler.postDelayed({
                    if (!isStaleEpoch(epoch)) listenForNewRequest(attempt + 1)
                }, RETRY_ASK_DELAY_MS)
            },
        )
    }

    /**
     * 이 콜백이 등록된 [epoch] 시점의 세션이 아직 유효한지. 음성 콜백(TTS 완료·STT 결과)은
     * 사용자가 중단/종료를 누른 뒤에도 뒤늦게 도착할 수 있어서, 실행 전에 반드시 확인해야 한다.
     * 확인 없이 진행하면 "중단했는데 클릭이 나가는" 사고가 난다 — 사진 전송처럼 되돌릴 수 없는
     * 동작이 걸려 있으므로 이 검사는 선택이 아니다.
     */
    private fun isStaleEpoch(epoch: Int): Boolean {
        if (epoch == sessionEpoch) return false
        Log.i(TAG, "중단/세션 교체 후 도착한 음성 콜백 무시 (epoch $epoch != $sessionEpoch)")
        return true
    }

    /**
     * 중단 후 새로 받은 요청을 시작한다. 대상 앱이 지금 떠 있는 앱과 같으면 그 자리에서 바로
     * 세션을 열고, 다른 앱이면 그 앱을 실행한 뒤 [sessionRequested]/[pendingGoal] 경로로 넘긴다
     * (웨이크 흐름과 동일한 진입점).
     */
    private fun startRequestedGoal(answer: String) {
        overlay.showOrUpdate("사용자: $answer", CharacterExpression.FOCUSED)
        // 새 요청에 앱 키워드가 없으면 **지금 쓰던 앱**을 그대로 쓴다. 예전엔 기본값이
        // 카카오톡이라, 코레일 진행 중 중단하고 "처음부터 다시 해줘"라고만 말해도
        // 카카오톡으로 화면이 튀어버렸다.
        val target = WakeAndLaunchActivity.resolveTargetPackage(answer, defaultPackage = currentPackage)
        pendingGoal = answer
        if (target == currentPackage) {
            beginNewSession()
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(target)
        if (launchIntent == null) {
            Log.w(TAG, "$target 를 찾지 못함 (미설치?)")
            endSession("해당 앱을 찾지 못했습니다.")
            pendingGoal = null
            return
        }
        sessionRequested = true
        // CLEAR_TASK: 대상 앱이 백그라운드에 중간 화면(채팅방 등)으로 남아 있어도
        // 메인 화면부터 새로 시작한다 — 자동화가 항상 같은 출발점에서 시작하게.
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(launchIntent)
    }

    /** "종료하기" 버튼: 자동화를 멈추고 조용히 대기 상태로 돌아간다.
     *
     * **주의: `disableSelf()`를 쓰면 안 된다.** 예전엔 여기서 접근성 서비스 자체를 껐는데,
     * `disableSelf()`는 앱 하나만 끄는 게 아니라 OS 접근성 프레임워크에서 이 서비스를 시스템
     * 레벨로 비활성화한다(`dumpsys accessibility`의 "Enabled services" 목록에서 빠짐). 앱을
     * 재실행해도 코드로 다시 켤 방법이 없고 설정에서 사람이 수동으로 다시 켜야만 해서, "종료하기"를
     * 누른 뒤 앱을 재실행해 새로 말을 걸어도 접근성 이벤트 자체가 안 들어와 아무 반응이 없는
     * 버그로 이어졌다(2026-08-26 보고). 그냥 세션을 정리하고 다음 웨이크 트리거
     * ([WakeAndLaunchActivity])를 기다리는 것으로 충분하다 — 서비스는 계속 켜둔다.
     */
    private fun exitApp() {
        stopSessionCore()
        Log.i(TAG, "사용자 요청으로 세션 종료 (서비스는 유지, 다음 웨이크 트리거 대기)")
        // 오버레이는 **즉시** 걷는다. 예전엔 "종료할게요" TTS가 끝난 뒤에 hide()를 불러서
        // 버튼을 눌러도 1초 넘게 화면이 남아 있었다 — 사용자에겐 "안 눌린 것"처럼 보인다.
        // endSession()을 쓰지 않는 이유: 그쪽은 문구만 바꾸고 오버레이(버튼 포함)를 계속
        // 띄워두는 경로다. "종료하기"는 화면 자체를 없애는 것까지가 기대다. 서비스/접근성
        // 이벤트 수신은 계속되므로 다음 웨이크 트리거가 오면 오버레이가 다시 만들어진다.
        overlay.hide()
        voice.speak("종료할게요.")
    }

    /** 세션을 조용히 끝낸다(완료/실패 안내 후). 오버레이는 버튼과 함께 계속 떠 있는다 —
     * 사용자가 언제든 "중단하기"(새 요청)나 "종료하기"를 누를 수 있게. */
    private fun endSession(
        message: String,
        expression: CharacterExpression = CharacterExpression.CURIOUS,
    ) {
        isSessionActive = false
        disarmWatchdog()
        // 민감 화면(약관 등)에서 억제된 채 세션이 끝나는 경우: 억제를 풀지 않으면
        // 아래 showOrUpdate가 삼켜져 종료 안내가 영영 안 보인다.
        suppressedMode = ScreenSensitivity.NONE
        overlay.unsuppress()
        overlay.showOrUpdate(message, expression)
    }

    /**
     * "동작을 실행했으니 화면이 곧 바뀔 것"이라는 기대에 시한을 건다. [WATCHDOG_TIMEOUT_MS] 안에
     * 접근성 이벤트가 오지 않으면 스스로 한 번 더 스캔한다.
     *
     * 이 하나가 여러 정지 시나리오의 공통 안전망이다: 클릭이 조용히 실패했을 때, 화면 밖 노드를
     * 눌러 아무 일도 안 일어났을 때, 응답은 왔는데 계약 밖 status라 처리하지 못했을 때 —
     * 전부 "이벤트가 안 오니 다음 스캔도 예약되지 않아 영구 정지"로 끝나는 경로였다.
     * 오버레이는 직전 문구인 채로 멈춰 있어서 관객 눈에는 앱이 죽은 것으로 보인다.
     *
     * 되묻는 중(ASK_USER)에는 걸지 않는다 — 사용자가 답을 고민하는 시간은 정지가 아니다.
     */
    private fun armWatchdog(delayMs: Long = WATCHDOG_TIMEOUT_MS) {
        disarmWatchdog()
        if (!isSessionActive) return
        val epoch = sessionEpoch
        val runnable = Runnable { onWatchdogFired(epoch) }
        pendingWatchdog = runnable
        watchdogHandler.postDelayed(runnable, delayMs)
    }

    private fun disarmWatchdog() {
        pendingWatchdog?.let { watchdogHandler.removeCallbacks(it) }
        pendingWatchdog = null
    }

    private fun onWatchdogFired(epoch: Int) {
        pendingWatchdog = null
        if (!isSessionActive || isStaleEpoch(epoch)) return
        watchdogRetries++
        if (watchdogRetries > MAX_WATCHDOG_RETRIES) {
            Log.w(TAG, "워치독 재시도 한도 초과 — 세션 종료")
            voice.speak("화면이 더 진행되지 않아요. 중단하기를 눌러 다시 말씀해 주세요.")
            endSession("진행이 멈췄습니다. 중단하기를 눌러 다시 말씀해 주세요.")
            return
        }
        Log.i(TAG, "워치독: 진행 없음 — 재스캔 (retry=$watchdogRetries)")
        skipNextPrescroll = false // 진행이 막혔다 — 화면을 다시 제대로 파악한다
        // 지문 스킵을 우회한다. 재시도의 목적이 "LLM에게 다시 물어 다른 방법을 시도"인데,
        // 화면이 그대로라는 이유로 서버 호출을 생략하면 재시도가 아무 일도 못 하고
        // 한도만 소진된다(실측: 스킵 3연속 -> "진행이 멈췄습니다").
        lastScreenFingerprint = null
        collectAndDecide(userSpeech = null)
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
            overlay.showOrUpdate("사용자: $goal", CharacterExpression.FOCUSED)
            scheduleCollectAndDecide()
            return
        }

        isAwaitingGoal = true
        overlay.showOrUpdate("무엇을 도와드릴까요?", CharacterExpression.CURIOUS)
        val epoch = sessionEpoch
        voice.askAndListen(
            question = "무엇을 도와드릴까요?",
            onAnswer = { answer ->
                if (isStaleEpoch(epoch)) return@askAndListen
                isAwaitingGoal = false
                if (isStopCommand(answer)) {
                    stopSessionCore()
                    voice.speak("알겠습니다.")
                    endSession("대기 중입니다. 필요하시면 중단하기 버튼을 눌러주세요.")
                    return@askAndListen
                }
                goal = answer
                overlay.showOrUpdate("목표: $goal", CharacterExpression.FOCUSED)
                scheduleCollectAndDecide()
            },
            onError = { err ->
                if (isStaleEpoch(epoch)) return@askAndListen
                isAwaitingGoal = false
                Log.w(TAG, "목표 음성 인식 실패($err), 기본 목표로 대체")
                goal = DEFAULT_GOAL
                overlay.showOrUpdate("잘 못 들었어요. 기본 요청으로 진행할게요.", CharacterExpression.LISTENING)
                scheduleCollectAndDecide()
            },
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "TestAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceConnected = false
        isAutomationRunning = false
        pendingCollect?.let { debounceHandler.removeCallbacks(it) }
        firstEventInBurstAt = null
        disarmWatchdog()
        cancelAnalyzingIndicator()
        serviceScope.cancel()
        nodeMap.clear()
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
        // 프리스크롤이 만드는 스크롤 이벤트는 화면 변화가 아니라 우리 손가락의 메아리다.
        // 이걸 새 스캔으로 받으면 "스크롤 -> 이벤트 -> 스캔 -> 또 스크롤"로 영영 못 벗어난다.
        if (isPrescrolling) return

        // 화면이 실제로 바뀌었다는 뜻이므로 "진행이 멈췄다" 타이머를 해제하고 재시도 횟수도 되돌린다.
        disarmWatchdog()
        watchdogRetries = 0

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

    /** 화면을 훑는 동안 모아두는 후보. id는 최종 선별이 끝난 뒤에야 매긴다. */
    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val text: String?,
        val description: String?,
        val className: String,
        val clickable: Boolean,
        val scrollable: Boolean,
        val viewId: String?,
        val isPassword: Boolean,
        val checked: Boolean?,
        val bounds: Rect,
    )

    /** 현재 화면을 ElementDTO 목록으로 만들어 /decide를 호출한다. */
    private fun collectAndDecide(userSpeech: String?) {
        // 중단/종료 이후 뒤늦게 들어온 경로(뒤늦은 음성 콜백, 이미 예약된 타이머)로는 절대
        // 요청을 보내지 않는다. 보내면 그 응답으로 클릭이 실행돼 "멈추라고 했는데 전송됨"이 된다.
        if (!isSessionActive) {
            Log.i(TAG, "세션이 활성 상태가 아님 — 스캔/요청 취소")
            return
        }
        // 액션 직후 화면이 아직 정착 중이면 그 시간이 지날 때까지 미룬다. 여기서 "무시"가 아니라
        // "재예약"이어야 한다 — 그냥 버리면 이 스캔이 마지막 기회였을 때 세션이 그대로 멈춘다.
        val settleRemaining = settleUntil - SystemClock.uptimeMillis()
        if (settleRemaining > 0) {
            Log.d(TAG, "화면 정착 대기 — ${settleRemaining}ms 후로 스캔을 미룸")
            pendingCollect?.let { debounceHandler.removeCallbacks(it) }
            val runnable = Runnable { collectAndDecide(userSpeech) }
            pendingCollect = runnable
            debounceHandler.postDelayed(runnable, settleRemaining)
            return
        }

        if (isRequestInFlight) {
            // 예전엔 그냥 버렸는데, 버려진 그 스캔이 마지막 이벤트였으면 이후 아무 일도
            // 일어나지 않아 세션이 그대로 멈췄다. 기억해 뒀다가 응답이 끝나면 다시 돌린다.
            Log.d(TAG, "이전 요청 진행 중 — 이번 스캔은 응답 후로 미룸")
            pendingRescan = true
            return
        }
        // 화면을 읽지 못했다. 그냥 return하면 다음 이벤트가 안 올 경우 세션이 조용히 멈추므로
        // 워치독에 맡겨 다시 시도하게 한다.
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow가 null — 재시도 예약")
            armWatchdog(RETRY_DELAY_MS)
            return
        }

        val candidates = collectCandidates(root)

        // 결제 동의처럼 체크박스가 있는 화면은 확인 팝업이 여닫히는 전환 애니메이션 중간에
        // 읽으면 체크 상태가 실제와 어긋난다 — LLM이 그 낡은 상태를 근거로 체크를 다시 눌러
        // 토글 해제되는 사고(코레일 실측). 연속 두 번 같은 지문이 나올 때까지 판단을 미룬다.
        // 체크박스가 없는 화면은 이 게이트를 타지 않으므로 전체 반응 속도에는 영향이 없다.
        if (userSpeech == null && candidates.any { it.checked != null }) {
            val stabilityKey = stabilityFingerprintOf(candidates)
            if (stabilityKey != stabilityProbeFingerprint && stabilityRetries < MAX_STABILITY_RETRIES) {
                stabilityProbeFingerprint = stabilityKey
                stabilityRetries++
                Log.d(TAG, "체크 화면 안정화 확인 $stabilityRetries/$MAX_STABILITY_RETRIES — ${STABILITY_GAP_MS}ms 후 재확인")
                pendingCollect?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { collectAndDecide(userSpeech) }
                pendingCollect = runnable
                debounceHandler.postDelayed(runnable, STABILITY_GAP_MS)
                return
            }
        }
        stabilityProbeFingerprint = null
        stabilityRetries = 0

        // 화면 민감도에 따라 오버레이/자동화를 조절한다.
        // - FULL_PAUSE(비밀번호·생체): 오버레이를 걷고 자동 조작도 멈춘다 — 사용자가 직접 입력.
        // - OVERLAY_ONLY(약관 동의): 오버레이만 걷는다(체크박스를 가리지 않게).
        when (classifyScreenSensitivity(candidates)) {
            ScreenSensitivity.FULL_PAUSE -> {
                if (suppressedMode != ScreenSensitivity.FULL_PAUSE) {
                    suppressedMode = ScreenSensitivity.FULL_PAUSE
                    disarmWatchdog() // 입력을 기다리는 시간은 '정지'가 아니다 — 재스캔 재촉 금지
                    overlay.suppress()
                    voice.speak("이 화면은 직접 확인해 주세요. 끝나면 이어서 도와드릴게요.")
                    Log.i(TAG, "비밀번호/생체 화면 감지 — 오버레이 숨김, 자동 조작 일시 중지")
                }
                return
            }

            ScreenSensitivity.OVERLAY_ONLY -> {
                if (suppressedMode != ScreenSensitivity.OVERLAY_ONLY) {
                    suppressedMode = ScreenSensitivity.OVERLAY_ONLY
                    overlay.suppress()
                    Log.i(TAG, "약관 화면 감지 — 오버레이만 숨김, 자동화는 계속")
                }
                // 계속 진행 — 아래 프리스크롤/판단 파이프라인을 그대로 탄다. 체크박스는
                // AI가 눌러야 할 대상이므로 자동 조작을 멈추면 안 된다.
            }

            ScreenSensitivity.NONE -> {
                if (suppressedMode != ScreenSensitivity.NONE) {
                    val wasFullPause = suppressedMode == ScreenSensitivity.FULL_PAUSE
                    suppressedMode = ScreenSensitivity.NONE
                    overlay.unsuppress()
                    // 직전 화면과 같다는 이유로 재개 판단이 건너뛰어지지 않게 지문을 지운다.
                    lastScreenFingerprint = null
                    if (wasFullPause) {
                        overlay.showOrUpdate("이어서 도와드릴게요.", CharacterExpression.FOCUSED)
                    }
                    Log.i(TAG, "민감 화면 벗어남 — 오버레이 복원")
                }
            }
        }

        // 판단하기 전에 화면을 아래까지 훑는다: 스크롤 가능한 목록이 있고 이 화면에서 아직
        // 안 훑었다면, 몇 페이지 앞으로 스크롤하며 요소를 누적 수집한 뒤 원위치로 돌아와서
        // 전체 목록을 근거로 판단한다 — 첫 화면만 보고 엉뚱한 항목을 고르는 것을 막는다.
        val prescrollKey = prescrollFingerprintOf(candidates)
        val scrollContainer = candidates.filter { it.scrollable }
            .maxByOrNull { it.bounds.width().toLong() * it.bounds.height() }
        if (scrollContainer != null && !skipNextPrescroll &&
            prescrollKey != lastPrescrolledFingerprint && !isPrescrolling
        ) {
            lastPrescrolledFingerprint = prescrollKey
            startPrescroll(scrollContainer.node, candidates, userSpeech)
            return
        }

        proceedToDecision(candidates, userSpeech)
    }

    /** 화면 전체를 훑어 후보를 모은다. 프리스크롤 중간 단계에서도 재사용한다. */
    private fun collectCandidates(root: AccessibilityNodeInfo): List<Candidate> {
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        // 우리 오버레이가 지금 덮고 있는 화면 영역. 오버레이도 하나의 '창'이라 이 영역의
        // 대상 앱 노드는 isVisibleToUser=false로 보고된다 — 코레일 안내창의 확인 버튼처럼
        // 하단 중앙에 뜨는 요소가 스캔에서 통째로 빠지는 원인. 이 영역과 겹치는 노드는
        // "우리가 가린 것"이므로 보이는 것으로 취급한다(ACTION_CLICK은 가림과 무관하게 동작).
        val overlayArea = overlay.visibleBounds()
        val candidates = mutableListOf<Candidate>()

        fun visit(node: AccessibilityNodeInfo) {
            val text = node.text?.toString()
            // Compose 앱(코레일톡 등)은 clickable 컨테이너에 라벨이 없고 그 안의 클릭 불가
            // TextView에만 "바로 예매" 같은 텍스트가 있는 경우가 많다 — 자손 텍스트로 라벨을 합성한다.
            val description = node.contentDescription?.toString()
                ?: if (node.isClickable && text.isNullOrBlank()) synthesizeLabel(node) else null
            if (node.isClickable || node.isScrollable || !text.isNullOrBlank() || !description.isNullOrBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val visibleOrCoveredByUs = node.isVisibleToUser ||
                    (overlayArea != null && Rect.intersects(overlayArea, bounds))
                if (isUsableBounds(bounds, screen) && visibleOrCoveredByUs) {
                    candidates.add(
                        Candidate(
                            node = node,
                            text = text,
                            description = description,
                            className = node.className?.toString() ?: "unknown",
                            clickable = node.isClickable,
                            scrollable = node.isScrollable,
                            viewId = node.viewIdResourceName,
                            isPassword = node.isPassword,
                            // 체크박스류만 상태를 싣는다 — LLM이 이미 체크된 것을 다시 눌러
                            // 토글 해제하는 사고(코레일 결제 동의 실측)를 막는 근거.
                            checked = if (node.isCheckable) node.isChecked else null,
                            bounds = bounds,
                        ),
                    )
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { visit(it) }
            }
        }
        visit(root)
        return candidates
    }

    /** 프리스크롤 반복 여부를 판정하는 화면 지문. 라벨 구성만 본다(스크롤로 좌표가 변해도 동일). */
    private fun prescrollFingerprintOf(candidates: List<Candidate>): Int =
        candidates.joinToString("|") { "${it.text}${it.description}${it.viewId}" }.hashCode()

    /**
     * 화면 안정화 판정용 지문. bounds까지 포함해 팝업 여닫힘 애니메이션 중의 미세한 좌표
     * 변화도 "아직 움직이는 중"으로 잡는다 — 같은 값이 두 번 연속 나와야 정착으로 본다.
     */
    private fun stabilityFingerprintOf(candidates: List<Candidate>): Int =
        candidates.joinToString("|") {
            "${it.text}${it.description}${it.viewId}${it.checked}${it.bounds}"
        }.hashCode()

    /** 프리스크롤 누적 병합용 키. 같은 항목이 스크롤로 좌표만 바뀐 경우를 하나로 본다. */
    private fun mergeKeyOf(candidate: Candidate): String =
        "${candidate.viewId}|${candidate.text}|${candidate.description}|${candidate.className}"

    /**
     * 화면을 아래로 [MAX_PRESCROLL_PAGES]페이지까지 스크롤하며 요소를 누적 수집한 뒤,
     * 같은 횟수만큼 되돌려 원위치로 복귀하고 나서 판단([proceedToDecision])으로 넘어간다.
     *
     * 각 단계 사이에 짧은 대기를 둔다 — 스크롤 직후의 접근성 트리는 아직 이전 프레임일 수 있다.
     * 진행 중 세션이 중단되면([isStaleEpoch]) 그 자리에서 멈춘다.
     */
    private fun startPrescroll(
        container: AccessibilityNodeInfo,
        base: List<Candidate>,
        userSpeech: String?,
    ) {
        isPrescrolling = true
        val epoch = sessionEpoch
        val merged = LinkedHashMap<String, Candidate>()
        base.forEach { merged.putIfAbsent(mergeKeyOf(it), it) }
        Log.i(TAG, "프리스크롤 시작 — 화면 파악을 위해 최대 ${MAX_PRESCROLL_PAGES}페이지 훑기")

        fun finish(forwardCount: Int) {
            // 원위치 복귀: 내려간 만큼 되올라간다. 복귀가 일부 실패해도 판단은 진행한다 —
            // LLM은 SCROLL 액션으로 스스로 이동할 수 있다.
            fun restore(step: Int) {
                if (!isSessionActive || isStaleEpoch(epoch)) {
                    isPrescrolling = false
                    return
                }
                if (step >= forwardCount) {
                    val runnable = Runnable {
                        isPrescrolling = false
                        pendingPrescrollStep = null
                        if (isSessionActive && !isStaleEpoch(epoch)) {
                            Log.i(TAG, "프리스크롤 완료 — 누적 ${merged.size}개 요소로 판단 진행")
                            proceedToDecision(merged.values.toList(), userSpeech)
                        }
                    }
                    pendingPrescrollStep = runnable
                    debounceHandler.postDelayed(runnable, PRESCROLL_SETTLE_MS)
                    return
                }
                container.refresh()
                container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                val runnable = Runnable { restore(step + 1) }
                pendingPrescrollStep = runnable
                debounceHandler.postDelayed(runnable, PRESCROLL_STEP_DELAY_MS)
            }
            restore(0)
        }

        fun forward(step: Int) {
            if (!isSessionActive || isStaleEpoch(epoch)) {
                isPrescrolling = false
                return
            }
            if (step >= MAX_PRESCROLL_PAGES) {
                finish(step)
                return
            }
            container.refresh()
            val scrolled = container.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (!scrolled) {
                finish(step)
                return
            }
            val runnable = Runnable {
                if (!isSessionActive || isStaleEpoch(epoch)) {
                    isPrescrolling = false
                    return@Runnable
                }
                rootInActiveWindow?.let { root ->
                    collectCandidates(root).forEach { merged.putIfAbsent(mergeKeyOf(it), it) }
                }
                forward(step + 1)
            }
            pendingPrescrollStep = runnable
            debounceHandler.postDelayed(runnable, PRESCROLL_STEP_DELAY_MS)
        }
        forward(0)
    }

    /** 수집(및 프리스크롤 누적)이 끝난 후보 목록으로 실제 판단(서버 호출)을 진행한다. */
    private fun proceedToDecision(candidates: List<Candidate>, userSpeech: String?) {
        val selected = selectElements(candidates)
        if (selected.isEmpty()) {
            // 전환 도중이라 아직 아무것도 안 그려졌을 수 있다. 마찬가지로 재시도에 맡긴다.
            Log.w(TAG, "보낼 요소가 없음 — 재시도 예약")
            armWatchdog(RETRY_DELAY_MS)
            return
        }

        nodeMap.clear()
        val elements = selected.mapIndexed { index, candidate ->
            val id = index + 1
            nodeMap[id] = candidate.node
            ElementDTO(
                id = id,
                text = candidate.text,
                content_description = candidate.description,
                class_name = candidate.className,
                clickable = candidate.clickable,
                scrollable = candidate.scrollable,
                view_id = candidate.viewId,
                checked = candidate.checked,
                bounds = listOf(
                    candidate.bounds.left,
                    candidate.bounds.top,
                    candidate.bounds.right,
                    candidate.bounds.bottom,
                ),
            )
        }
        Log.d(TAG, "화면 스캔: 후보 ${candidates.size}개 -> 전송 ${elements.size}개")

        // 직전과 완전히 같은 화면이면 서버에 다시 묻지 않는다. 물어봐야 같은 답이 오고,
        // 그 답대로 실행하면 방금 누른 곳을 한 번 더 누르게 된다. 사용자가 새로 답을 줬을
        // 때(userSpeech)는 화면이 같아도 판단이 달라져야 하므로 그대로 진행한다.
        val fingerprint = fingerprintOf(elements)
        if (userSpeech == null && fingerprint == lastScreenFingerprint) {
            Log.i(TAG, "화면이 직전과 동일 — 서버 호출 생략 (fingerprint=$fingerprint)")
            // 직전에 실행한 액션이 화면을 못 바꿨다는 뜻이다. 그 대상을 무효로 기록해 두면,
            // 다음 판단이 같은 대상을 또 고를 때 performAction 대신 좌표 탭으로 간다 —
            // performAction이 true를 돌려주고도 실제로는 안 눌리는 노드가 있다(코레일 실측).
            pendingActionKey?.let {
                ineffectiveActionKey = it
                Log.w(TAG, "직전 액션 무효 판정 — 다음 동일 대상은 좌표 탭으로 강제 (key=$it)")
            }
            pendingActionKey = null
            // 진짜로 아무 진행이 없는 상황일 수 있으니 워치독에 맡긴다. 계속 같으면
            // 재시도 한도에 걸려 사용자에게 알리고 끝난다.
            armWatchdog()
            return
        }
        pendingActionKey = null
        lastScreenFingerprint = fingerprint

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
                    // 예외 문자열(영문)을 그대로 띄우면 사용자는 무슨 일인지 알 수 없다.
                    // 워치독이 곧 재시도하므로 "다시 해보는 중"이라고만 알린다.
                    notifyAndRetry("연결이 잠시 끊겼어요. 다시 시도할게요.")
                }
            } finally {
                isRequestInFlight = false
                cancelAnalyzingIndicator()
                if (pendingRescan) {
                    pendingRescan = false
                    if (isSessionActive) scheduleCollectAndDecide()
                }
            }
        }
    }

    /**
     * 화면 민감도 분류. FULL_PAUSE가 OVERLAY_ONLY보다 우선한다 — 약관 문구와 비밀번호
     * 입력이 한 화면에 같이 있으면 사용자 직접 입력이 필요한 쪽으로 본다.
     */
    private fun classifyScreenSensitivity(candidates: List<Candidate>): ScreenSensitivity {
        var overlayOnly = false
        var singleDigitCount = 0
        for (candidate in candidates) {
            if (candidate.isPassword) return ScreenSensitivity.FULL_PAUSE
            val text = candidate.text.orEmpty().trim()
            if (text.length == 1 && text[0].isDigit()) singleDigitCount++
            val label = "$text ${candidate.description.orEmpty()}"
                .lowercase().replace(" ", "")
            if (FULL_PAUSE_KEYWORDS.any { label.contains(it) }) return ScreenSensitivity.FULL_PAUSE
            if (OVERLAY_ONLY_KEYWORDS.any { label.contains(it) }) overlayOnly = true
        }
        // 숫자 키패드 휴리스틱: 한 자리 숫자 버튼이 0~9 대부분 보이면 결제 비밀번호 다이얼이다.
        // 라벨 키워드는 앱·로딩 타이밍에 따라 잡혔다 안 잡혔다 해서(카카오택시에서 실측,
        // "됐다 안 됐다") 화면 구조 자체로 판정한다 — 어떤 결제 키패드든 숫자 버튼은 있다.
        if (singleDigitCount >= NUMPAD_DIGIT_THRESHOLD) return ScreenSensitivity.FULL_PAUSE
        return if (overlayOnly) ScreenSensitivity.OVERLAY_ONLY else ScreenSensitivity.NONE
    }

    /**
     * 화면 한 장의 지문. 요소의 라벨·조작 가능 여부·위치가 하나라도 다르면 값이 달라진다.
     *
     * id는 스캔할 때마다 1부터 다시 매기는 임시 번호라 넣어도 의미가 없다 — 대신 **순서**가
     * 반영되도록 이어붙인다. bounds를 포함하는 이유는 스크롤처럼 "항목은 그대로인데 위치만
     * 밀린" 변화를 놓치지 않기 위해서다. 애니메이션 중의 미세한 좌표 변화 때문에 매번 값이
     * 달라지는 문제는 [settleUntil] 대기가 먼저 막아준다.
     */
    private fun fingerprintOf(elements: List<ElementDTO>): Int = elements.joinToString("|") {
        "${it.text}${it.content_description}${it.view_id}${it.clickable}${it.scrollable}${it.checked}${it.bounds}"
    }.hashCode()

    /**
     * 이 노드를 LLM에 보낼 만한 위치에 있는지.
     *
     * 폭/높이가 0인 노드는 아직 레이아웃이 안 잡힌 것이고(백엔드가 bounds 정합성 위반으로 요청
     * 전체를 422로 거부한다), **화면 밖으로 밀려난 노드는 눌러도 아무 일이 일어나지 않는다.**
     * 스크롤로 밀려난 RecyclerView 항목은 bounds가 top=-800처럼 화면 밖인데도 폭·높이는
     * 멀쩡해서 예전 검사(폭>0, 높이>0)를 그대로 통과했다 — LLM이 그걸 고르면 performAction은
     * true를 돌려주는데 화면은 그대로여서, 이벤트가 안 오고 다음 스캔도 예약되지 않아
     * 세션이 영구 정지했다.
     */
    private fun isUsableBounds(bounds: Rect, screen: Rect): Boolean {
        if (bounds.left >= bounds.right || bounds.top >= bounds.bottom) return false
        return Rect.intersects(bounds, screen)
    }

    /**
     * 후보가 너무 많으면 [MAX_ELEMENTS]개로 줄인다. 카카오톡 대화방 목록 같은 화면은 노드가
     * 수백 개라 그대로 보내면 Gemini 입력 토큰이 커져 실측 2.4초가 5~8초로 늘어난다.
     *
     * 줄이는 순서:
     * 1. 부모/자식이 같은 라벨·같은 위치로 중복 보고하는 것을 합친다(clickable 쪽을 남긴다).
     *    같은 이름의 서로 다른 연락처는 bounds가 다르므로 살아남는다 — 되묻기 판단의 근거라
     *    이것까지 합치면 안 된다.
     * 2. 그래도 넘치면 조작 가능한 노드(clickable/scrollable)를 우선 남긴다. 화면 순서는
     *    유지한다 — LLM이 위치로 화면을 이해하기 때문.
     */
    private fun selectElements(candidates: List<Candidate>): List<Candidate> {
        val deduped = mutableListOf<Candidate>()
        val seen = mutableMapOf<String, Int>() // 라벨+위치 -> deduped 안의 인덱스
        for (candidate in candidates) {
            val label = "${candidate.text}|${candidate.description}"
            if (label == "null|null") {
                deduped.add(candidate)
                continue
            }
            val key = "$label|${candidate.bounds.flattenToString()}"
            val existingIndex = seen[key]
            if (existingIndex == null) {
                seen[key] = deduped.size
                deduped.add(candidate)
            } else if (candidate.clickable && !deduped[existingIndex].clickable) {
                // 같은 라벨·같은 위치라면 실제로 누를 수 있는 쪽이 LLM에 쓸모 있다.
                deduped[existingIndex] = candidate
            }
        }

        if (deduped.size <= MAX_ELEMENTS) return deduped

        val actionable = deduped.withIndex().filter { it.value.clickable || it.value.scrollable }
        val rest = deduped.withIndex().filterNot { it.value.clickable || it.value.scrollable }
        val kept = (actionable + rest.take((MAX_ELEMENTS - actionable.size).coerceAtLeast(0)))
            .take(MAX_ELEMENTS)
            .sortedBy { it.index }
        Log.w(TAG, "요소 ${deduped.size}개 -> ${kept.size}개로 축약 (조작 가능 ${actionable.size}개 우선)")
        return kept.map { it.value }
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
            // text와 contentDescription이 같은 값인 노드가 흔하다(둘 다 "전송"인 버튼 등).
            // 예전엔 둘 다 넣어서 "전송 전송"이 되고, 조각 상한 3개를 절반이나 잡아먹어
            // 정작 뒤에 오는 진짜 라벨이 잘렸다. 이미 담긴 조각은 다시 담지 않는다.
            fun addPart(value: String?) {
                val trimmed = value?.trim().orEmpty()
                if (trimmed.isNotBlank() && trimmed !in parts) parts.add(trimmed)
            }
            addPart(child.text?.toString())
            addPart(child.contentDescription?.toString())
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

    /**
     * 진행 멘트를 재생·표시한다. **같은 멘트가 연속으로 반복되면** 그대로 되풀이하지 않고
     * 대기 문구로 바꾼다 — 재탐색 중 서버가 같은 판단을 다시 보내는 경우, 같은 말을
     * 두 번 들은 사용자는 "이상한데?"라고 느낀다. 대기 문구도 두 종류를 번갈아 써서
     * 그 자체가 반복되는 느낌을 줄인다.
     */
    private fun speakProgress(message: String, onSpoken: (() -> Unit)? = null) {
        val effective: String
        if (message.isNotBlank() && message == lastProgressMessage) {
            progressRepeatCount++
            effective = WAITING_PHRASES[(progressRepeatCount - 1) % WAITING_PHRASES.size]
            // 대기 문구는 첫 한 번만 소리로 낸다. 반복마다 큐(QUEUE_ADD)에 쌓으면 오디오가
            // 화면보다 몇 스텝 뒤처져, 이미 지나간 단계의 멘트가 뒤늦게 나오는 불일치가
            // 생긴다(2026-08-26 결제 화면 실측). 이후 반복은 오버레이 문구만 갱신하고
            // 후속 동작은 바로 실행한다 — 진행 속도도 그만큼 빨라진다.
            if (progressRepeatCount >= 2) {
                overlay.showOrUpdate(effective, CharacterExpression.FOCUSED)
                onSpoken?.invoke()
                return
            }
        } else {
            progressRepeatCount = 0
            lastProgressMessage = message.ifBlank { null }
            effective = message
        }
        overlay.showOrUpdate(effective.ifBlank { "다음 동작 실행 중" }, CharacterExpression.HAPPY)
        if (effective.isNotBlank()) {
            // 멘트가 **다 끝난 뒤에** 후속 동작(클릭 등)을 실행한다 — 말하는 도중 화면이
            // 넘어가면 멘트가 연달아 밀려 쏟아지는 느낌을 준다(2026-08-26 보고).
            voice.speak(effective) { onSpoken?.invoke() }
        } else {
            onSpoken?.invoke()
        }
    }

    private fun handleResponse(response: DecideResponse) {
        when (response.status) {
            DecideStatus.CONTINUE -> {
                consecutiveAskUserCount = 0
                // "~할게요" 식 행동 선언 — 다음 화면은 훑지 말고 바로 이어서 진행한다.
                skipNextPrescroll = true
                // 클릭은 멘트가 끝난 뒤에 — 말과 화면 전환이 겹치면 다음 멘트가 밀려서
                // 안내가 와다다 쏟아진다. 사용자가 한 문장씩 따라올 수 있게 페이스를 맞춘다.
                val epochAtResponse = sessionEpoch
                speakProgress(response.voice_message) {
                    if (!isStaleEpoch(epochAtResponse) && isSessionActive) {
                        performTargetAction(response)
                    }
                }
                // 클릭/입력 후 화면이 바뀌면 onAccessibilityEvent가 다시 스케줄링한다.
            }

            DecideStatus.ASK_USER -> {
                consecutiveAskUserCount++
                // 정보가 부족하다는 뜻 — 답변을 받은 뒤의 재판단은 화면을 다시 훑어야 한다.
                skipNextPrescroll = false
                if (consecutiveAskUserCount > MAX_CONSECUTIVE_ASK_USER) {
                    endSession("답변을 계속 이해하지 못해 중단합니다.")
                    return
                }
                // 사용자가 답을 고민하는 시간은 "정지"가 아니므로 워치독을 걸지 않는다.
                disarmWatchdog()
                overlay.showOrUpdate("답변 대기: ${response.voice_message}", CharacterExpression.CURIOUS)
                askUserWithRetry(response.voice_message, attempt = 0)
            }

            DecideStatus.DONE -> {
                consecutiveAskUserCount = 0
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                endSession("완료: ${response.voice_message}", CharacterExpression.HAPPY)
            }

            DecideStatus.UNSUPPORTED -> {
                consecutiveAskUserCount = 0
                // 서버가 '일시적 실패'라고 표시한 UNSUPPORTED(AI 호출 실패/응답 지연)는 세션을
                // 끝내지 않는다. 무료 티어 소진(429)이나 순간적인 5xx 하나로 세션이 끝나면
                // 시연 도중 복구할 방법이 없다 — 같은 화면으로 잠시 후 다시 시도한다.
                if (response.retryable) {
                    Log.w(TAG, "일시적 서버 오류 — 재시도 예약 (reason=${response.reason})")
                    notifyAndRetry("잠시 문제가 있었어요. 다시 시도할게요.")
                    return
                }
                if (response.voice_message.isNotBlank()) {
                    voice.speak(response.voice_message)
                }
                // reason은 서버 로그/디버깅용 영문 요약이다(계약 §5) — 화면에 내보내면
                // "중단됨: target_node_id given without action_type" 같은 문구가 사용자에게
                // 그대로 보인다. 읽어줄 문구는 항상 voice_message다.
                Log.w(TAG, "UNSUPPORTED로 세션 종료 (reason=${response.reason})")
                endSession(
                    response.voice_message.ifBlank { "죄송해요, 이 화면에서는 도와드리기 어려워요." },
                )
            }

            // status는 String이라 when이 exhaustive하지 않다. 계약 밖 값이나 (Gson이 알 수 없는
            // 값을 null로 만들어) null이 들어오면 예전엔 아무 분기도 타지 않고 조용히 멈췄다.
            else -> {
                Log.e(TAG, "알 수 없는 status=${response.status} — 재스캔으로 복구 시도")
                armWatchdog(RETRY_DELAY_MS)
            }
        }
    }

    /**
     * 질문을 TTS로 읽어준 뒤(끝난 다음에만) 마이크를 켠다 — [VoiceInteractionManager.askAndListen]을 써서
     * TTS 재생 중에 STT가 그 소리를 주워듣는 경합을 막는다. 인식 실패 시 같은 질문을 최대
     * [MAX_ASK_RETRIES]번까지 다시 묻는다.
     */
    private fun askUserWithRetry(question: String, attempt: Int, repeatQuestion: Boolean = true) {
        if (attempt >= MAX_ASK_RETRIES) {
            endSession("답변을 인식하지 못했습니다.")
            return
        }
        val epoch = sessionEpoch
        voice.askAndListen(
            question = if (repeatQuestion) question else "말씀해 주세요.",
            onAnswer = { answer ->
                if (isStaleEpoch(epoch)) return@askAndListen
                // 사용자가 방금 뭐라고 답했는지는 항상 화면에 보여야 한다 — 잘 알아들었는지
                // 스스로 확인할 수 있게.
                overlay.showOrUpdate("사용자: $answer", CharacterExpression.FOCUSED)
                routeAnswer(answer)
            },
            onError = { err ->
                if (isStaleEpoch(epoch)) return@askAndListen
                // 원시 에러 문자열("음성 인식 오류 (code=7)" 등)을 화면에 그대로 내보내면 안 된다 —
                // 사용자에겐 앱이 고장난 것처럼 보인다. 특히 code=7(NO_MATCH)/6(SPEECH_TIMEOUT)은
                // "조용해서 못 알아들었다"는 정상 상황이다. 상세는 로그로만 남기고,
                // 화면·표정은 "다시 여쭤본다"는 자연스러운 흐름으로 보여준다.
                Log.w(TAG, "답변 인식 실패($err) — 재시도 ${attempt + 1}/$MAX_ASK_RETRIES")
                overlay.showOrUpdate("듣고 있어요. 말씀해 주세요.", CharacterExpression.LISTENING)
                // 곧바로 재오픈하면 인식이 연쇄로 실패하니 한 박자만 쉰다. 단 **질문 전체를
                // 다시 읽지 않는다** — 날짜·시간처럼 생각할 시간이 필요한 질문은 사용자가
                // 답을 고르는 동안 마이크가 먼저 닫히는데, 그때마다 같은 질문을 통째로
                // 반복하면 "한 번 더 물어보는" 어색한 경험이 된다(2026-08-26 보고).
                // 짧은 "말씀해 주세요"만 붙이고 바로 다시 듣는다.
                debounceHandler.postDelayed({
                    if (!isStaleEpoch(epoch) && isSessionActive) {
                        askUserWithRetry(question, attempt + 1, repeatQuestion = false)
                    }
                }, RETRY_ASK_DELAY_MS)
            },
        )
    }

    /** 답변이 중단 명령이면 즉시 세션을 끝내고, 정보 제공형이면 goal에 누적, 확인 응답이면
     * user_speech로 일회성 전달한다 (CLAUDE.md §5-1). 중단 명령은 서버를 거치지 않고 클라이언트가
     * 바로 처리한다 — 사용자가 멈추라는데 한 번 더 왕복하는 사이 클릭이 나가면 안 되기 때문. */
    private fun routeAnswer(answer: String) {
        if (isStopCommand(answer)) {
            stopAndListenForNewRequest()
            return
        }
        when (classifyAnswer(answer)) {
            AnswerType.CONFIRMATION -> collectAndDecide(userSpeech = answer)
            AnswerType.INFO -> {
                // 되묻기가 여러 번 이어지면 goal이 계속 길어진다. 오래된 앞부분을 잘라내면 원래
                // 목표("사진 보내줘")를 잃으므로, 상한을 넘으면 뒤쪽 답변부터 버린다.
                val extended = "$goal. $answer"
                goal = if (extended.length <= MAX_GOAL_LENGTH) {
                    extended
                } else {
                    Log.w(TAG, "goal 길이 상한 초과 — 이번 답변은 goal에 누적하지 않음")
                    goal
                }
                // 목표가 구체화됐으니 화면이 그대로여도 판단이 달라져야 한다. 지문을 지워
                // "직전과 같은 화면" 스킵에 걸리지 않게 한다.
                lastScreenFingerprint = null
                collectAndDecide(userSpeech = null)
            }
        }
    }

    /** 판정 로직과 그 근거는 [SpeechCommands.isStopCommand]에 있다(단위 테스트로 검증됨). */
    private fun isStopCommand(text: String): Boolean = SpeechCommands.isStopCommand(text)

    /** 판정 로직과 그 근거는 [SpeechCommands.classifyAnswer]에 있다(단위 테스트로 검증됨). */
    private fun classifyAnswer(text: String): AnswerType = SpeechCommands.classifyAnswer(text)

    private fun performTargetAction(response: DecideResponse) {
        val node = response.target_node_id?.let { nodeMap[it] }
        if (node == null || response.action_type == null) {
            Log.w(TAG, "target node를 찾지 못함 (target_node_id=${response.target_node_id})")
            notifyAndRetry("화면을 다시 살펴볼게요.")
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
            // 화면이 이미 바뀌어 죽은 참조다. 이 노드로 클릭/좌표탭을 하면 **낡은 좌표의
            // 엉뚱한 곳**을 누른다 — 코레일 실측(2026-08-26): 프리스크롤 누적분의 지나간
            // 화면 요소를 LLM이 골라 refresh 실패 -> 옛 좌표 탭 -> 무반응 3연속 -> 세션 종료.
            // 액션을 포기하고 화면을 다시 보게 한다.
            Log.w(TAG, "노드 refresh 실패 — 죽은 참조로는 액션하지 않고 재스캔")
            skipNextPrescroll = true // 재스캔은 현재 화면만 보면 된다(누적분이 원인이었으므로)
            notifyAndRetry("화면이 바뀌었네요. 다시 확인할게요.")
            return
        }

        // 이 대상의 시그니처. 노드 id는 스캔마다 바뀌므로 내용 기반으로 만든다.
        val actionKey = "${node.viewIdResourceName}|${node.text}|${node.contentDescription}|${response.action_type}"

        // 체크박스류는 클릭 후 상태가 실제로 뒤집힌 것을 확인하고 나서 다음 판단으로 넘어간다.
        val wasChecked = if (node.isCheckable) node.isChecked else null

        val actionResult = when (response.action_type) {
            ActionType.CLICK ->
                if (actionKey == ineffectiveActionKey) {
                    // 지난번에 performAction이 true였는데 화면이 안 바뀐 그 대상이다.
                    // 접근성 클릭을 무시하는 노드로 보고 곧장 진짜 터치(좌표 탭)를 넣는다.
                    Log.i(TAG, "무효 이력 대상 — performAction 생략, 좌표 탭 강제 (key=$actionKey)")
                    ineffectiveActionKey = null
                    tapCenter(node)
                } else {
                    clickWithFallback(node)
                }
            ActionType.SET_TEXT -> {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        response.input_value ?: "",
                    )
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            ActionType.SCROLL -> scrollForward(node)
            else -> false
        }
        Log.i(TAG, "performAction 결과: $actionResult (id=${response.target_node_id})")
        if (actionResult) {
            pendingActionKey = actionKey
            if (wasChecked != null && response.action_type == ActionType.CLICK) {
                // 체크박스 클릭: 고정 시간 대기 대신 isChecked가 실제로 뒤집힐 때까지 폴링한다.
                // 시간이 아니라 상태를 기준으로 다음 스캔을 여는 것이 핵심 — 확인 팝업이 뜨는
                // 경우(상태가 안 뒤집힘)는 타임아웃 후 일반 흐름으로 넘어가 팝업을 처리한다.
                settleUntil = SystemClock.uptimeMillis() + CHECK_CONFIRM_POLL_MS * (CHECK_CONFIRM_MAX_POLLS + 2)
                debounceHandler.postDelayed(
                    { awaitCheckToggle(node, wasChecked, attempt = 1) },
                    CHECK_CONFIRM_POLL_MS,
                )
            } else {
                // 방금 화면을 건드렸다. 전환이 끝나기 전에 읽으면 이전 화면과 새 화면이 섞인
                // 중간 상태를 보게 되므로, 이 시간 동안은 스캔을 막는다.
                val settleDelay = settleDelayFor(response.action_type)
                settleUntil = SystemClock.uptimeMillis() + settleDelay
                Log.d(TAG, "화면 정착 대기 ${settleDelay}ms (action=${response.action_type})")
            }
            // 화면이 곧 바뀔 것으로 기대한다. 안 바뀌면 워치독이 스스로 다시 스캔한다.
            armWatchdog()
        } else {
            Log.w(TAG, "performAction 실패 — 이 스텝은 화면에 아무 영향을 못 줬을 것")
            notifyAndRetry("잘 안 눌렸어요. 다시 해볼게요.")
        }
    }

    /**
     * 체크박스 클릭이 실제 상태 변화([AccessibilityNodeInfo.isChecked] 반전)로 반영됐는지
     * 폴링으로 확인한다. 반영이 확인되면 짧은 정착 후 곧장 재판단을 건다.
     *
     * 확인 팝업이 뜨는 체크박스(코레일 결제 동의)는 팝업을 닫아야 상태가 뒤집히므로 여기서는
     * 타임아웃이 난다 — 그 경우 일반 재스캔으로 넘어가 팝업의 확인 버튼을 처리하게 한다.
     */
    private fun awaitCheckToggle(node: AccessibilityNodeInfo, wasChecked: Boolean, attempt: Int) {
        if (!isSessionActive) return

        val flipped = node.refresh() && node.isChecked != wasChecked
        if (flipped) {
            Log.i(TAG, "체크 상태 반영 확인 ($wasChecked -> ${node.isChecked}) — 폴링 ${attempt}회")
            settleUntil = SystemClock.uptimeMillis() + CHECK_CONFIRM_SETTLE_MS
            debounceHandler.postDelayed({ collectAndDecide(null) }, CHECK_CONFIRM_SETTLE_MS)
            return
        }
        if (attempt >= CHECK_CONFIRM_MAX_POLLS) {
            Log.i(TAG, "체크 반영 미확인(확인 팝업 가능성) — 일반 재스캔으로 진행")
            settleUntil = SystemClock.uptimeMillis() + CHECK_CONFIRM_SETTLE_MS
            debounceHandler.postDelayed({ collectAndDecide(null) }, CHECK_CONFIRM_SETTLE_MS)
            return
        }
        settleUntil = SystemClock.uptimeMillis() + CHECK_CONFIRM_POLL_MS * 2
        debounceHandler.postDelayed({ awaitCheckToggle(node, wasChecked, attempt + 1) }, CHECK_CONFIRM_POLL_MS)
    }

    /**
     * [AccessibilityNodeInfo.ACTION_CLICK]이 실패했을 때 포기하지 않고 두 단계로 더 시도한다.
     *
     * 1. **클릭 가능한 조상으로 올라간다.** Compose 화면(코레일톡 등)은 실제 클릭 핸들러가
     *    컨테이너에 붙어 있고 라벨은 그 안쪽 텍스트 노드에만 있어서, LLM이 "바로 예매"라는
     *    글자가 있는 clickable=false 노드를 고르는 일이 잦다. 그 노드는 눌리지 않는다.
     * 2. **그래도 안 되면 좌표를 직접 탭한다.** 접근성 액션을 아예 처리하지 않는 커스텀 뷰가
     *    있어서, 화면상 위치를 두드리는 것이 마지막 수단이다
     *    (`test_accessibility_service_config.xml`의 `canPerformGestures="true"`가 있어야 동작).
     *
     * 예전에는 한 번 실패하면 로그만 남기고 끝났고, 화면이 안 바뀌니 접근성 이벤트도 안 와서
     * 다음 스캔이 예약되지 않아 그 자리에서 영구 정지했다.
     */
    private fun clickWithFallback(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var ancestor: AccessibilityNodeInfo? = node.parent
        var depth = 1
        while (ancestor != null && depth <= MAX_CLICK_ANCESTOR_DEPTH) {
            if (ancestor.isClickable && ancestor.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "조상 노드로 클릭 성공 (depth=$depth class=${ancestor.className})")
                return true
            }
            ancestor = ancestor.parent
            depth++
        }

        return tapCenter(node)
    }

    /**
     * 이 동작을 하고 나서 화면이 정착하기까지 기다릴 시간.
     *
     * 동작마다 화면이 흔들리는 양상이 다르다:
     * - CLICK: 화면 전환 애니메이션(보통 300~500ms, 무거운 화면은 더)
     * - SET_TEXT: 입력 반영에 더해 키보드가 올라오면서 레이아웃 전체가 한 번 더 흔들린다
     * - SCROLL: 손을 뗀 뒤에도 관성 스크롤이 이어져 가장 오래 흐른다
     */
    private fun settleDelayFor(actionType: String?): Long = when (actionType) {
        ActionType.SET_TEXT -> SETTLE_DELAY_SET_TEXT_MS
        ActionType.SCROLL -> SETTLE_DELAY_SCROLL_MS
        else -> SETTLE_DELAY_CLICK_MS
    }

    /** 노드 bounds 중앙을 제스처로 탭한다. 접근성 액션을 처리하지 않는 커스텀 뷰용 최후 수단. */
    private fun tapCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        // 화면 밖 좌표를 탭하면 엉뚱한 곳이 눌린다(프리스크롤 누적 요소는 좌표가 낡았을 수 있다).
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (!screen.contains(bounds.centerX(), bounds.centerY())) {
            Log.w(TAG, "좌표 탭 생략 — 대상 중심이 화면 밖 (${bounds.centerX()}, ${bounds.centerY()})")
            return false
        }
        // 탭 좌표가 우리 오버레이 위면 **오버레이가 터치를 먹는다** — 화면 하단 버튼을 탭했는데
        // 뒤 앱 대신 말풍선 카드가 눌리던 원인(코레일 실측: (540, 2100) 탭이 전부 무반응).
        // 탭하는 동안만 오버레이 터치를 통과시키고, 끝나면 되돌린다.
        val overlayArea = overlay.visibleBounds()
        val needsBypass = overlayArea != null && overlayArea.contains(bounds.centerX(), bounds.centerY())
        if (needsBypass) {
            overlay.setTouchable(false)
            debounceHandler.postDelayed({ overlay.setTouchable(true) }, TAP_TOUCH_BYPASS_MS)
        }
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS))
            .build()
        val dispatched = dispatchGesture(gesture, null, null)
        Log.i(TAG, "좌표 탭 폴백: dispatched=$dispatched at (${bounds.exactCenterX()}, ${bounds.exactCenterY()})")
        return dispatched
    }

    /**
     * 목록을 한 화면 앞으로 스크롤한다. LLM이 스크롤 대상으로 고른 노드가 정작 스크롤 컨테이너가
     * 아닌 경우(목록 안의 항목을 고르는 등)가 있어 스크롤 가능한 조상까지 올라가며 시도한다.
     */
    private fun scrollForward(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth <= MAX_CLICK_ANCESTOR_DEPTH) {
            if (current.isScrollable &&
                current.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                Log.i(TAG, "스크롤 성공 (depth=$depth class=${current.className})")
                return true
            }
            current = current.parent
            depth++
        }
        return false
    }

    /**
     * 일시적인 문제가 났을 때 조용히 멈추지 않고 **화면과 음성 양쪽으로** 알린 뒤 스스로 다시 시도한다.
     *
     * 오버레이가 직전 문구("다음 동작 실행 중")인 채로 굳어 있으면 관객 눈에는 앱이 죽은 것으로
     * 보인다. 그리고 이 앱의 주 사용자는 고령자라 화면 문구만으로는 부족하다 — 무슨 일이
     * 벌어지는지 귀로도 들려야 기다릴지 중단할지 판단할 수 있다.
     *
     * 클릭 실패·네트워크 오류·서버의 일시적 오류(retryable) 셋 다 사용자 입장에서는 같은 상황
     * ("잠깐 문제가 생겼고 다시 해보는 중")이라 한 경로로 합쳤다. 재시도 자체는 워치독이 맡고,
     * 횟수는 [MAX_WATCHDOG_RETRIES]로 제한된다.
     */
    private fun notifyAndRetry(message: String) {
        if (!isSessionActive) return
        skipNextPrescroll = false // 방금 시도가 실패했다 — 다음 스캔은 화면을 다시 훑는다
        Log.w(TAG, "일시적 문제 — 복구 시도: $message")
        overlay.showOrUpdate(message, CharacterExpression.LISTENING)
        voice.speak(message)
        armWatchdog(RETRY_DELAY_MS)
    }

    companion object {
        private const val TAG = "TestA11yService"

        /** 세션의 "주인"이 되는 앱들. 이들 사이를 오가면 새 세션으로 취급한다. */
        private val PRIMARY_PACKAGES = setOf("com.kakao.talk", "com.kakao.taxi", "com.korail.talk")

        /**
         * 주 앱이 목표 수행 도중 띄우는 보조 화면들. 여기로 넘어가도 세션은 그대로 이어진다.
         *
         * 이 목록이 없으면 카카오톡이 시스템 사진 선택기를 띄우는 순간 접근성 이벤트가 0건이 되고
         * (packageNames가 OS 레벨 필터라 목록 밖 앱은 이벤트 자체가 안 온다) 자동화가 그 자리에서
         * 멈춘다. 사진 보내기 시나리오에서 정확히 밟게 되는 경로다.
         *
         * 기기마다 어떤 선택기가 뜨는지 달라서 흔한 것들을 함께 넣어 둔다(설치돼 있지 않으면
         * 그냥 이벤트가 안 올 뿐 부작용은 없다).
         */
        private val AUXILIARY_PACKAGES = setOf(
            "com.google.android.providers.media.module", // Android 시스템 포토피커
            "com.android.providers.media.module",
            "com.android.documentsui", // 파일 선택기
            "com.sec.android.gallery3d", // 삼성 갤러리
            "com.samsung.android.providers.media", // 삼성 미디어 제공자
        )

        /** 이 서비스가 반응하는 앱 목록. `res/xml/test_accessibility_service_config.xml`의
         * packageNames와 반드시 같이 맞춰야 한다 — 저쪽에 없는 패키지를 여기 추가해도 이벤트
         * 자체가 시스템에서 걸러져서 안 들어온다. */
        private val TARGET_PACKAGES = PRIMARY_PACKAGES + AUXILIARY_PACKAGES
        private const val DEFAULT_GOAL = "카카오톡에서 가장 최근에 찍은 사진 보내줘"
        private const val DEBOUNCE_MS = 500L

        /** 이벤트가 쉬지 않고 계속 들어와도(예: 지도 화면 애니메이션) 이 시간이 지나면 강제로
         * 한 번 스캔한다 — 디바운스 starvation 방지. */
        private const val MAX_BURST_WAIT_MS = 2500L

        /**
         * 동작 직후 화면이 정착하기를 기다리는 시간. [settleDelayFor] 참고.
         * 이 시간이 끝나기 전에는 [MAX_BURST_WAIT_MS] 상한에 걸려도 스캔하지 않는다 —
         * 전환 애니메이션 한복판의 화면을 읽는 것이 오판의 주된 원인이었다.
         */
        private const val SETTLE_DELAY_CLICK_MS = 800L
        private const val SETTLE_DELAY_SET_TEXT_MS = 1_000L
        private const val SETTLE_DELAY_SCROLL_MS = 1_200L

        /** 체크박스 클릭 후 isChecked 반전을 확인하는 폴링 간격/횟수([awaitCheckToggle]). */
        private const val CHECK_CONFIRM_POLL_MS = 250L
        private const val CHECK_CONFIRM_MAX_POLLS = 10
        /** 반전 확인(또는 타임아웃) 후 재판단까지의 짧은 정착 시간. */
        private const val CHECK_CONFIRM_SETTLE_MS = 400L

        /** 체크박스 화면 안정화 게이트: 재확인 간격과 무한 대기 방지 상한. */
        private const val STABILITY_GAP_MS = 350L
        private const val MAX_STABILITY_RETRIES = 6

        /** 이보다 오래 걸리는 요청에만 "분석 중" 스피너를 보여준다. */
        private const val ANALYZING_INDICATOR_DELAY_MS = 3000L

        /** [synthesizeLabel]이 자손에서 모으는 텍스트 조각 수/길이 상한. */
        private const val SYNTHESIZED_LABEL_MAX_PARTS = 3
        private const val SYNTHESIZED_LABEL_MAX_LENGTH = 60

        /** 인식 실패 후 다시 묻기까지 쉬는 시간. 마이크 재오픈 연쇄 실패 방지 + 사용자 숨 고르기. */
        private const val RETRY_ASK_DELAY_MS = 900L

        /** STT 인식 실패 시 같은 질문을 다시 묻는 최대 횟수. */
        private const val MAX_ASK_RETRIES = 3

        /** 세션 하나에서 ASK_USER가 연속으로 나올 수 있는 최대 횟수 — 무한 되묻기 방지. */
        // 기차 예매는 필수 슬롯이 4개(출발역·도착역·날짜·시간)라 정상 흐름만으로도 되묻기가
        // 4번 이어진다. 5면 추가 확인 한두 번에 세션이 끊기므로 여유를 둔다.
        private const val MAX_CONSECUTIVE_ASK_USER = 7

        /** 동작 실행 후 이 시간 안에 화면 변경 이벤트가 없으면 스스로 재스캔한다. [armWatchdog] 참고. */
        private const val WATCHDOG_TIMEOUT_MS = 7_000L

        /** 일시적 실패(서버 오류·클릭 실패) 후 다시 시도하기까지의 간격. */
        private const val RETRY_DELAY_MS = 2_000L

        /** 워치독이 진행 없음을 감지해 재시도할 수 있는 최대 횟수. 넘으면 사용자에게 알리고 끝낸다. */
        private const val MAX_WATCHDOG_RETRIES = 3

        /** 한 번에 LLM으로 보내는 요소 개수 상한. 넘으면 조작 가능한 노드를 우선 남긴다. */
        private const val MAX_ELEMENTS = 120

        /** 클릭/스크롤 폴백이 조상 방향으로 거슬러 올라가는 최대 깊이. */
        private const val MAX_CLICK_ANCESTOR_DEPTH = 5

        /** 좌표 탭 폴백에서 손가락을 대고 있는 시간. */
        private const val TAP_DURATION_MS = 60L

        /** 오버레이 위 좌표를 탭할 때 오버레이 터치를 통과시켜 두는 시간. */
        private const val TAP_TOUCH_BYPASS_MS = 400L

        /** 되묻기 답변을 이어붙인 goal의 길이 상한. */
        private const val MAX_GOAL_LENGTH = 300

        /** 프리스크롤: 판단 전에 화면을 아래로 훑는 최대 페이지 수와 단계별 대기. */
        private const val MAX_PRESCROLL_PAGES = 2
        private const val PRESCROLL_STEP_DELAY_MS = 500L
        private const val PRESCROLL_SETTLE_MS = 350L

        /** 같은 진행 멘트가 반복될 때 대신 말할 대기 문구. 번갈아 사용한다. */
        private val WAITING_PHRASES = listOf(
            "잠시만 기다려 주세요.",
            "조금만 더 확인하고 있어요.",
        )

        /** [classifyScreenSensitivity]가 보는 완전 정지 키워드. 소문자·공백 제거 후 부분 일치.
         * "인증"처럼 광범위한 단어는 일부러 뺐다 — 본인인증 '버튼'이 있는 일반 화면까지
         * 잡아서 오버레이가 엉뚱하게 사라진다. */
        private val FULL_PAUSE_KEYWORDS = setOf(
            "비밀번호", "지문", "생체", "페이스아이디", "faceid",
        )

        /** 이 개수 이상의 한 자리 숫자 버튼이 보이면 결제 키패드로 판정한다.
         * 0~9 열 개 중 스캔이 한둘 놓쳐도 잡히도록 9로 둔다. 일반 화면에 한 자리 숫자
         * 노드가 9개나 흩어져 있을 일은 사실상 없다. */
        private const val NUMPAD_DIGIT_THRESHOLD = 9

        /** 오버레이만 걷는 화면(자동화는 계속). "동의"만으로는 일반 화면(수신 동의 버튼 등)까지
         * 잡혀서 "약관"만 본다 — 체크박스는 AI가 눌러야 하므로 자동 조작을 멈추면 안 된다. */
        private val OVERLAY_ONLY_KEYWORDS = setOf(
            "약관",
        )

        // 중단 명령 키워드/확인 응답 목록은 SpeechCommands로 옮겼다 — 단위 테스트가 붙어 있는
        // 쪽에 한 벌만 두어야 둘이 따로 놀지 않는다.

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

        /**
         * 지금 자동화 세션이 진행 중인지. **MainActivity가 웨이크 루프를 켜기 전에 확인한다.**
         *
         * TTS 1개 + SpeechRecognizer 1개를 MainActivity와 이 서비스가 각각 따로 만들어 쓰는데,
         * 마이크는 하나뿐이다. 시연 도중 사용자가 홈을 눌렀다가 앱 화면으로 돌아오면
         * `onResume` -> `restartWakeListening`이 서비스가 쓰던 마이크를 가로채
         * `ERROR_RECOGNIZER_BUSY`(code=8)가 나고 진행 중이던 되묻기가 깨진다.
         *
         * [isSessionActive]의 setter가 갱신하므로 따로 관리할 필요는 없다.
         */
        @Volatile
        var isAutomationRunning: Boolean = false

        /**
         * 접근성 서비스가 **실제로 살아서 이벤트를 받고 있는지.**
         *
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`에 우리 서비스가 들어 있는 것과는
         * 다른 이야기다. 서비스가 죽거나(초기화 예외) 앱을 재설치해 바인딩이 끊기면, 설정 목록에는
         * 그대로 남아 "켜짐"으로 보이지만 이벤트는 하나도 들어오지 않는다. 이 값이 그 둘을
         * 구분해 주고, [com.example.pathpilot.MainActivity]가 이걸 보고 사용자에게 알린다.
         */
        @Volatile
        var isServiceConnected: Boolean = false
    }
}
