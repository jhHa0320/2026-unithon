package com.example.pathpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathpilot.accessibility.AccessibilityStatus
import com.example.pathpilot.settings.WakeWordSettings
import com.example.pathpilot.testkit.TestAccessibilityService
import com.example.pathpilot.ui.permission.PermissionActivity
import com.example.pathpilot.voice.VoiceInteractionManager
import com.example.pathpilot.wakeup.WakeAndLaunchActivity

/**
 * 앱 메인 화면 (screen/InApp Main Screen + 마이크사용 감지 시안).
 *
 * 두 모드를 오간다:
 * - **대기 모드**: 손 흔드는 캐릭터와 "안녕, [이름] 라고 말해보세요!" — 웨이크 문구를 기다린다.
 *   이름 칩을 탭하면 부를 이름을 바꿀 수 있고, 우상단 프로필 원은 권한 화면으로 간다.
 * - **듣는 중 모드**: 경청하는 캐릭터와 "듣고 있어요…" — 웨이크 문구가 들린 뒤 실제 요청을
 *   받아쓰는 동안. 인식된 문장이 회색 자막으로 보인다.
 *
 * 받은 요청은 [WakeAndLaunchActivity]에 넘겨 카카오톡 자동화로 이어준다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var voice: VoiceInteractionManager

    private lateinit var statusText: TextView
    private lateinit var chipWakeName: TextView
    private lateinit var characterImage: ImageView
    private lateinit var groupIdle: View
    private lateinit var groupListening: View
    private lateinit var transcriptText: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardCaption: TextView

    /** 접근성 서비스 생존 판정을 잠깐 미뤄서 하기 위한 핸들러. [warnIfAccessibilityServiceIsDead] 참고. */
    private val serviceCheckHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        voice = VoiceInteractionManager(this)
        statusText = findViewById(R.id.text_wake_status)
        chipWakeName = findViewById(R.id.chip_wake_name)
        characterImage = findViewById(R.id.image_character)
        groupIdle = findViewById(R.id.group_idle)
        groupListening = findViewById(R.id.group_listening)
        transcriptText = findViewById(R.id.text_transcript)
        cardTitle = findViewById(R.id.text_card_title)
        cardCaption = findViewById(R.id.text_card_caption)

        chipWakeName.text = WakeWordSettings.getName(this)
        // 이름 칩 탭 -> 부를 이름 바꾸기 (기존 EditText+저장 버튼을 다이얼로그로 대체)
        chipWakeName.setOnClickListener { showEditWakeNameDialog() }
        // 캐릭터 탭 -> 듣기 재시작 (기존 "다시 듣기 시작" 버튼 대체)
        characterImage.setOnClickListener { restartWakeListening() }
        findViewById<ImageView>(R.id.button_open_permissions).setOnClickListener {
            startActivity(Intent(this, PermissionActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 접근성 서비스가 죽어 있으면 웨이크 문구를 들어봐야 아무 일도 일어나지 않는다.
        // 먼저 확인하고, 죽었으면 그 사실을 알리는 것으로 끝낸다.
        if (warnIfAccessibilityServiceIsDead()) return
        restartWakeListening()
    }

    override fun onPause() {
        super.onPause()
        serviceCheckHandler.removeCallbacksAndMessages(null)
        voice.stopWakeListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceCheckHandler.removeCallbacksAndMessages(null)
        voice.shutdown()
    }

    // --- 화면 모드 전환 -------------------------------------------------------

    /** 대기 모드: 손 흔드는 캐릭터 + "안녕, [이름] 라고 말해보세요!" */
    private fun showIdleMode() {
        characterImage.setImageResource(R.drawable.char_greeting)
        groupIdle.visibility = View.VISIBLE
        groupListening.visibility = View.GONE
        cardTitle.setText(R.string.main_card_idle_title)
        cardCaption.setText(R.string.main_card_idle_caption)
    }

    /** 듣는 중 모드: 경청 캐릭터 + "듣고 있어요…" + 인식된 문장 자막 */
    private fun showListeningMode() {
        characterImage.setImageResource(R.drawable.char_listening)
        groupIdle.visibility = View.GONE
        groupListening.visibility = View.VISIBLE
        cardTitle.setText(R.string.main_card_listening_title)
        cardCaption.setText(R.string.main_card_listening_caption)
    }

    private fun showEditWakeNameDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(WakeWordSettings.getName(this@MainActivity))
            setSelection(text.length)
            hint = getString(R.string.main_wake_name_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.main_edit_wake_name_title)
            .setView(input)
            .setPositiveButton(R.string.main_save_wake_name) { _, _ ->
                WakeWordSettings.setName(this, input.text.toString())
                chipWakeName.text = WakeWordSettings.getName(this)
                Toast.makeText(
                    this,
                    getString(R.string.main_wake_name_saved, WakeWordSettings.getWakePhrase(this)),
                    Toast.LENGTH_SHORT,
                ).show()
                restartWakeListening()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- 접근성 서비스 생존 확인 ----------------------------------------------

    /**
     * "설정에는 켜져 있는데 실제로는 죽어 있는" 접근성 서비스를 감지해 사용자에게 알린다.
     * 알렸으면 true를 돌려준다(= 지금 웨이크 리스닝을 시작할 상황이 아니다).
     *
     * 이 상태가 왜 생기냐면 — 접근성 서비스가 초기화 중 죽거나, **설정 XML의 capability가 바뀐
     * 채로 재설치되면**(예: `canPerformGestures` 추가) 기존 승인으로는 바인딩되지 않는다.
     * 서비스가 죽으면 시스템이 접근성 목록에서 항목을 지워버리기도 한다(2026-08-26 실기기 확인).
     * 복구 방법은 접근성 설정에서 껐다 켜는 것뿐이라 그 사실을 그대로 알려준다.
     *
     * **판정을 [SERVICE_CHECK_DELAY_MS]만큼 미루는 이유:** 이 액티비티와 접근성 서비스는 같은
     * 프로세스에서 돈다. 앱을 처음 열어 프로세스가 막 뜨는 참이면 `onResume`이
     * `onServiceConnected`보다 먼저 실행될 수 있고, 그 순간에 판정하면 멀쩡한 서비스를
     * "응답하지 않는다"고 잘못 알린다. 잠깐 기다렸다 다시 확인한다.
     */
    private fun warnIfAccessibilityServiceIsDead(): Boolean = when (AccessibilityStatus.current(this)) {
        AccessibilityStatus.State.RUNNING -> false

        AccessibilityStatus.State.NEVER_ENABLED -> {
            statusText.text = getString(R.string.main_status_accessibility_off)
            true
        }

        AccessibilityStatus.State.TURNED_OFF_UNEXPECTEDLY -> {
            statusText.text = getString(R.string.main_status_accessibility_turned_off)
            true
        }

        AccessibilityStatus.State.ENABLED_BUT_DEAD -> {
            statusText.text = getString(R.string.main_status_accessibility_checking)
            serviceCheckHandler.removeCallbacksAndMessages(null)
            serviceCheckHandler.postDelayed({
                if (TestAccessibilityService.isServiceConnected) {
                    restartWakeListening()
                } else {
                    statusText.text = getString(R.string.main_status_accessibility_dead)
                }
            }, SERVICE_CHECK_DELAY_MS)
            true
        }
    }

    // --- 웨이크 리스닝 --------------------------------------------------------

    private fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun restartWakeListening() {
        if (!hasMicPermission()) {
            statusText.text = getString(R.string.main_status_need_permission)
            return
        }

        // 접근성 서비스가 자동화를 돌리는 중이면 마이크를 건드리지 않는다.
        // 마이크는 하나뿐인데 MainActivity와 서비스가 각자 SpeechRecognizer를 만들어 쓴다 —
        // 시연 도중 홈을 눌렀다가 앱으로 돌아오면 onResume이 여기로 들어와서 서비스가 쓰던
        // 마이크를 가로채고, ERROR_RECOGNIZER_BUSY(code=8)로 진행 중이던 되묻기가 깨진다.
        if (TestAccessibilityService.isAutomationRunning) {
            statusText.text = getString(R.string.main_status_automation_running)
            return
        }

        showIdleMode()
        statusText.text = ""
        val wakePhrase = WakeWordSettings.getWakePhrase(this)
        voice.stopWakeListening()
        voice.startWakeListening(
            wakePhrase = wakePhrase,
            onWake = {
                // 웨이크 문구 인식됨 -> 듣는 중 모드로 전환하고 실제 요청을 받아쓴다.
                showListeningMode()
                transcriptText.text = wakePhrase
                voice.askAndListen(
                    question = getString(R.string.main_prompt_after_wake),
                    onAnswer = { goal -> handleCapturedGoal(goal, attempt = 0) },
                    onError = {
                        statusText.text = getString(R.string.main_status_answer_failed)
                        restartWakeListening()
                    },
                    onListeningChanged = { isListening ->
                        statusText.text = if (isListening) {
                            ""
                        } else {
                            getString(R.string.main_status_processing)
                        }
                    },
                )
            },
            onError = {
                // 웨이크 문구 인식이 한 번 틀리는 건 흔한 일이라 상태 텍스트를 계속 바꾸지 않고 조용히 재시도한다.
            },
            onHeard = { heard ->
                // 웨이크 문구가 왜 안 걸리는지(STT가 다르게 알아들었는지) 화면에서 바로 보이게 한다.
                statusText.text = getString(R.string.main_status_heard_not_wake, heard)
            },
        )
    }

    /**
     * 웨이크 후 받아쓴 요청을 검증하고 나서 자동화로 넘긴다.
     *
     * STT가 "기차 예매해줘"를 "애매해서"처럼 엉뚱하게 받아쓰는 일이 실제로 있다(2026-08-26).
     * 예전엔 무슨 문장이든 기본 앱(카카오톡)으로 조용히 넘어가 엉뚱한 앱이 열렸다.
     * 이제 요청이 지원 앱 어느 것도 가리키지 않으면("애매하면") 이동하지 않고 되묻는다.
     */
    private fun handleCapturedGoal(goal: String, attempt: Int) {
        transcriptText.text = goal
        if (WakeAndLaunchActivity.matchTargetPackage(goal) != null) {
            launchTargetWithGoal(goal)
            return
        }
        if (attempt >= MAX_GOAL_RETRIES) {
            statusText.text = getString(R.string.main_status_answer_failed)
            restartWakeListening()
            return
        }
        voice.askAndListen(
            question = getString(R.string.main_prompt_goal_retry),
            onAnswer = { retried -> handleCapturedGoal(retried, attempt + 1) },
            onError = {
                statusText.text = getString(R.string.main_status_answer_failed)
                restartWakeListening()
            },
            onListeningChanged = { isListening ->
                statusText.text = if (isListening) {
                    ""
                } else {
                    getString(R.string.main_status_processing)
                }
            },
        )
    }

    private fun launchTargetWithGoal(goal: String) {
        transcriptText.text = goal
        startActivity(
            Intent(this, WakeAndLaunchActivity::class.java).apply {
                putExtra(WakeAndLaunchActivity.EXTRA_GOAL, goal)
            },
        )
    }

    private companion object {
        /** 접근성 서비스 생존을 판정하기 전에 기다리는 시간. 프로세스가 막 뜨는 참이면
         * onServiceConnected가 아직 안 왔을 수 있어서 곧바로 판정하면 오탐이 난다. */
        const val SERVICE_CHECK_DELAY_MS = 1_200L

        /** 요청이 애매할 때(지원 앱 미매칭) 다시 물어보는 최대 횟수. 넘으면 웨이크 대기로 복귀. */
        const val MAX_GOAL_RETRIES = 2
    }
}
