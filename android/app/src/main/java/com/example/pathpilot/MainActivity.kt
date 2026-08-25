package com.example.pathpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.pathpilot.settings.WakeWordSettings
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
        restartWakeListening()
    }

    override fun onPause() {
        super.onPause()
        voice.stopWakeListening()
    }

    override fun onDestroy() {
        super.onDestroy()
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
                statusText.text = getString(R.string.main_status_heard_not_wake, heard, wakePhrase)
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
}
