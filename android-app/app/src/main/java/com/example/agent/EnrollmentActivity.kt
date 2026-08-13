package com.example.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.agent.security.GatewayEnrollmentManager
import com.example.agent.security.SecureCredentialStore
import kotlinx.coroutines.launch

/** One-time operator enrollment. The enrollment code is never persisted. */
class EnrollmentActivity : AppCompatActivity() {
    private lateinit var credentialStore: SecureCredentialStore
    private lateinit var enrollButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var gatewayUrl: EditText
    private lateinit var deviceId: EditText
    private lateinit var enrollmentCode: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_enrollment)

        credentialStore = SecureCredentialStore(applicationContext)
        enrollButton = findViewById(R.id.enroll_button)
        progress = findViewById(R.id.enrollment_progress)
        status = findViewById(R.id.enrollment_status)
        gatewayUrl = findViewById(R.id.gateway_url)
        deviceId = findViewById(R.id.device_id)
        enrollmentCode = findViewById(R.id.enrollment_code)

        try {
            if (credentialStore.load() != null) {
                openControlPlane()
                return
            }
        } catch (_: Exception) {
            // An invalidated/tampered Keystore record cannot be recovered. Remove
            // it and require a fresh one-time code instead of using stale data.
            try {
                credentialStore.clearEnrollment()
            } catch (_: Exception) {
                status.setText(R.string.enrollment_storage_error)
                enrollButton.isEnabled = false
                return
            }
            status.setText(R.string.enrollment_reenroll_required)
        }

        enrollButton.setOnClickListener { enroll() }
    }

    private fun enroll() {
        val gateway = gatewayUrl.text.toString()
        val device = deviceId.text.toString()
        val code = enrollmentCode.text.toString()
        enrollmentCode.text.clear()
        setBusy(true)
        status.text = ""

        lifecycleScope.launch {
            try {
                GatewayEnrollmentManager(credentialStore).enroll(gateway, device, code)
                openControlPlane()
            } catch (_: IllegalArgumentException) {
                status.setText(R.string.enrollment_invalid_input)
            } catch (_: IllegalStateException) {
                status.setText(R.string.enrollment_storage_error)
            } catch (_: Exception) {
                status.setText(R.string.enrollment_failed)
            } finally {
                if (!isFinishing) setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        enrollButton.isEnabled = !busy
        gatewayUrl.isEnabled = !busy
        deviceId.isEnabled = !busy
        enrollmentCode.isEnabled = !busy
        progress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun openControlPlane() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
