package com.example.pathpilot.ui.permission

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.pathpilot.R

/**
 * 앱 실행에 필요한 세 가지 권한(Accessibility, Microphone, Overlay)을 순서대로 안내/요청한다.
 * 접근성 서비스 활성화 자체는 시스템 설정에서 사용자가 직접 켜야 한다(Android 정책상 코드로 자동
 * 활성화 불가) — 여기서는 설정 화면으로 안내하고 상태를 다시 확인하는 역할만 한다.
 *
 * AccessibilityService 구현(멤버 C, service/ 패키지)이 아직 없어도 이 화면은 독립적으로 동작한다.
 * 접근성 활성화 여부를 서비스별로 정확히 검사하려면 [isAccessibilityServiceEnabled]에
 * 실제 서비스의 FQCN(예: "com.example.pathpilot.service.PathPilotAccessibilityService")을
 * 채워 넣어야 한다 — 그 전까지는 "접근성 설정 화면 진입 여부"만으로 안내한다.
 */
class PermissionActivity : AppCompatActivity() {

    /**
     * 지금은 멤버 A의 테스트용 서비스(testkit/TestAccessibilityService)를 가리킨다.
     * 멤버 C가 정식 AccessibilityService를 추가하면 이 값을 그 서비스의 FQCN으로 바꾼다.
     */
    private val accessibilityServiceComponent: String? =
        "com.example.pathpilot/com.example.pathpilot.testkit.TestAccessibilityService"

    private lateinit var accessibilityStatusText: TextView
    private lateinit var microphoneStatusText: TextView
    private lateinit var overlayStatusText: TextView

    private val requestMicrophonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "마이크 권한이 없으면 음성 대화를 쓸 수 없습니다.", Toast.LENGTH_SHORT).show()
            }
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission)

        accessibilityStatusText = findViewById(R.id.text_accessibility_status)
        microphoneStatusText = findViewById(R.id.text_microphone_status)
        overlayStatusText = findViewById(R.id.text_overlay_status)

        findViewById<Button>(R.id.button_grant_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.button_grant_microphone).setOnClickListener {
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        findViewById<Button>(R.id.button_grant_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        accessibilityStatusText.text = if (isAccessibilityServiceEnabled()) {
            "접근성 서비스: 켜짐"
        } else {
            "접근성 서비스: 꺼짐 — 설정에서 PathPilot을 켜주세요"
        }

        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        microphoneStatusText.text = if (micGranted) "마이크: 허용됨" else "마이크: 허용 필요"

        overlayStatusText.text = if (Settings.canDrawOverlays(this)) {
            "다른 앱 위에 표시: 허용됨"
        } else {
            "다른 앱 위에 표시: 허용 필요"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val target = accessibilityServiceComponent ?: return false
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabledServices.contains(target)
    }
}
