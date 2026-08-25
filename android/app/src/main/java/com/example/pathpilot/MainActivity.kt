package com.example.pathpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
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
 * 앱을 열면 곧바로 요청을 받지 않고, 웨이크 문구("안녕 " + [WakeWordSettings]에 저장된 이름, 기본값
 * "손자")가 들릴 때까지 대기한다. 웨이크 문구를 들으면 그제서야 "네, 말씀하세요"로 실제 요청을
 * 받고, 받은 요청을 [WakeAndLaunchActivity]에 넘겨 카카오톡 자동화로 이어준다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var voice: VoiceInteractionManager
    private lateinit var statusText: TextView
    private lateinit var wakeNameInput: EditText

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
        wakeNameInput = findViewById(R.id.input_wake_name)
        wakeNameInput.setText(WakeWordSettings.getName(this))

        findViewById<Button>(R.id.button_save_wake_name).setOnClickListener {
            WakeWordSettings.setName(this, wakeNameInput.text.toString())
            wakeNameInput.setText(WakeWordSettings.getName(this))
            Toast.makeText(
                this,
                getString(R.string.main_wake_name_saved, WakeWordSettings.getWakePhrase(this)),
                Toast.LENGTH_SHORT,
            ).show()
            restartWakeListening()
        }

        findViewById<Button>(R.id.button_start_listening).setOnClickListener { restartWakeListening() }
        findViewById<Button>(R.id.button_open_permissions).setOnClickListener {
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

    /**
     * "설정에는 켜져 있는데 실제로는 죽어 있는" 접근성 서비스를 감지해 사용자에게 알린다.
     * 알렸으면 true를 돌려준다(= 지금 웨이크 리스닝을 시작할 상황이 아니다).
     *
     * 이 상태가 왜 생기냐면 — 접근성 서비스가 초기화 중 죽거나, **설정 XML의 capability가 바뀐
     * 채로 재설치되면**(예: `canPerformGestures` 추가) 기존 승인으로는 바인딩되지 않는다.
     * 그런데 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 목록에는 그대로 남아 있어서 설정
     * 화면은 여전히 "켜짐"으로 보인다. 서비스가 죽으면 오버레이 창도 함께 사라지므로 사용자
     * 눈에는 "중단/종료 버튼이 없어지고 아무 처리도 안 되는" 상태가 되고, 화면 어디에도 단서가
     * 없다(2026-08-26 실기기 `dumpsys accessibility`의 Crashed services로 확인).
     * 복구 방법은 접근성 설정에서 껐다 켜는 것뿐이라 그 사실을 그대로 알려준다.
     *
     * **판정을 [SERVICE_CHECK_DELAY_MS]만큼 미루는 이유:** 이 액티비티와 접근성 서비스는 같은
     * 프로세스에서 돈다. 앱을 처음 열어 프로세스가 막 뜨는 참이면 `onResume`이
     * `onServiceConnected`보다 먼저 실행될 수 있고, 그 순간에 판정하면 멀쩡한 서비스를
     * "응답하지 않는다"고 잘못 알린다. 잠깐 기다렸다 다시 확인한다.
     */
    private fun warnIfAccessibilityServiceIsDead(): Boolean = when (AccessibilityStatus.current(this)) {
        AccessibilityStatus.State.RUNNING -> false

        // 애초에 켠 적이 없는 정상적인 첫 실행. 권한 화면에서 켜면 된다.
        AccessibilityStatus.State.NEVER_ENABLED -> {
            statusText.text = getString(R.string.main_status_accessibility_off)
            true
        }

        // 켠 적은 있는데 목록에서 사라졌다 = 서비스가 죽으면서 시스템이 해제한 것.
        // "켜주세요"가 아니라 "풀렸습니다"라고 말해야 사용자가 상황을 납득한다.
        AccessibilityStatus.State.TURNED_OFF_UNEXPECTEDLY -> {
            statusText.text = getString(R.string.main_status_accessibility_turned_off)
            true
        }

        // 목록엔 있는데 아직 응답이 없다. 프로세스가 막 뜨는 중일 수 있으니 한 번 더 확인한다.
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

    override fun onDestroy() {
        super.onDestroy()
        serviceCheckHandler.removeCallbacksAndMessages(null)
        voice.shutdown()
    }

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

        val wakePhrase = WakeWordSettings.getWakePhrase(this)
        statusText.text = getString(R.string.main_status_waiting_wake, wakePhrase)
        voice.stopWakeListening()
        voice.startWakeListening(
            wakePhrase = wakePhrase,
            onWake = {
                statusText.text = getString(R.string.main_status_wake_detected)
                voice.askAndListen(
                    question = getString(R.string.main_prompt_after_wake),
                    onAnswer = { goal -> launchKakaoWithGoal(goal) },
                    onError = {
                        statusText.text = getString(R.string.main_status_answer_failed)
                        restartWakeListening()
                    },
                    onListeningChanged = { isListening ->
                        statusText.text = if (isListening) {
                            getString(R.string.main_status_listening)
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
                // 설명 문구 없이 사용자가 실제로 말한 내용만 보여준다.
                statusText.text = getString(R.string.main_status_heard_not_wake, heard)
            },
        )
    }

    private fun launchKakaoWithGoal(goal: String) {
        statusText.text = getString(R.string.main_status_goal_captured, goal)
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
    }
}
