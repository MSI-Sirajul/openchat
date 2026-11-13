package com.msi.openchat.feature.calls

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivityOutgoingAudioCallBinding
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.CallSessionGuard
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen edge-to-edge Activity for outgoing voice calls.
 * Displays "Calling..." and transitions to ActiveAudioCallActivity when answered/connecting.
 */
class OutgoingAudioCallActivity : AppCompatActivity() {
    private val tag = "OpenChat_OutgoingAudio"

    private lateinit var binding: ActivityOutgoingAudioCallBinding
    private var contactId: String = ""
    private var contactName: String = "Voice Call"
    private var callId: String = ""
    private var pulseAnimator: ObjectAnimator? = null
    private var timeoutJob: Job? = null
    private var hasNavigatedToActive = false
    private var hasStartedCall = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startOutgoingCall()
        } else {
            Toast.makeText(this, "Microphone permission is required for voice calls", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_CONTACT_ID = "outgoing_audio_contact_id"
        const val EXTRA_CONTACT_NAME = "outgoing_audio_contact_name"

        fun start(context: Context, contactId: String, contactName: String) {
            // Guard: Mutual exclusion check against active video or audio calls
            if (CallSessionGuard.isVideoCallActive() || VideoCallManager.isCallActive()) {
                Toast.makeText(context, "Cannot start voice call: a video call is active", Toast.LENGTH_SHORT).show()
                return
            }
            if (CallSessionGuard.isAudioCallActive() || CallSessionCoordinator.isActive()) {
                Toast.makeText(context, "A voice call is already in progress", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(context, OutgoingAudioCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CallSessionGuard.isVideoCallActive() || VideoCallManager.isCallActive()) {
            Toast.makeText(this, "Cannot start voice call while video call is active", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (CallSessionGuard.isAudioCallActive() || CallSessionCoordinator.isActive()) {
            Toast.makeText(this, "A voice call is already in progress", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityOutgoingAudioCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Voice Call"

        if (contactId.isBlank()) {
            finish()
            return
        }

        setupEdgeToEdge()
        startPulseAnimation()

        binding.textContactName.text = contactName
        binding.textContactInitial.text = contactName.take(1).uppercase()

        binding.btnCancel.setOnClickListener {
            cancelCall()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelCall()
            }
        })

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasMic) {
            startOutgoingCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootOutgoingAudio) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopCapsule.updatePadding(top = systemBars.top)
            binding.layoutCancel.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0.15f, 0.7f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun startOutgoingCall() {
        if (hasStartedCall) return
        hasStartedCall = true
        lifecycleScope.launch {
            val res = ServiceLocator.callRepository.initiateAudioCall(contactId)
            if (res is Resource.Success && res.data != null) {
                callId = res.data.id
                CallSessionGuard.registerAudioCall(callId)
                startTimeoutTimer()
                observeCallSession()
            } else {
                CallSessionGuard.unregisterCall(callId.ifBlank { null })
                Toast.makeText(this@OutgoingAudioCallActivity, "Failed to start voice call", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = lifecycleScope.launch {
            delay(35_000L)
            if (!hasNavigatedToActive && callId.isNotBlank()) {
                ServiceLocator.callRepository.updateCallStatus(callId = callId, status = "missed", endReason = "No answer")
                ServiceLocator.callRepository.sendSignaling(callId, contactId, "bye", "{\"reason\":\"timeout\"}")
                CallSessionGuard.unregisterCall(callId)
            }
            if (!hasNavigatedToActive) {
                Toast.makeText(this@OutgoingAudioCallActivity, "No answer", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun observeCallSession() {
        if (callId.isBlank()) return
        lifecycleScope.launch {
            ServiceLocator.callRepository.observeCallSession(callId).collect { session ->
                when (session.status) {
                    "connecting", "connected" -> {
                        timeoutJob?.cancel()
                        if (!hasNavigatedToActive) {
                            hasNavigatedToActive = true
                            val activeIntent = ActiveAudioCallActivity.createIntent(
                                context = this@OutgoingAudioCallActivity,
                                callId = callId,
                                contactId = contactId,
                                contactName = contactName,
                                isIncoming = false
                            )
                            startActivity(activeIntent)
                            finish()
                        }
                    }
                    "declined" -> {
                        timeoutJob?.cancel()
                        CallSessionGuard.unregisterCall(callId)
                        Toast.makeText(this@OutgoingAudioCallActivity, "Call declined", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    "busy" -> {
                        timeoutJob?.cancel()
                        CallSessionGuard.unregisterCall(callId)
                        Toast.makeText(this@OutgoingAudioCallActivity, "Contact is on another call", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    "missed" -> {
                        timeoutJob?.cancel()
                        CallSessionGuard.unregisterCall(callId)
                        Toast.makeText(this@OutgoingAudioCallActivity, "No answer", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    "failed" -> {
                        timeoutJob?.cancel()
                        CallSessionGuard.unregisterCall(callId)
                        Toast.makeText(this@OutgoingAudioCallActivity, "Call failed", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    "ended" -> {
                        timeoutJob?.cancel()
                        CallSessionGuard.unregisterCall(callId)
                        finish()
                    }
                }
            }
        }
    }

    private fun cancelCall() {
        timeoutJob?.cancel()
        if (callId.isNotBlank()) {
            CallSessionGuard.unregisterCall(callId)
            lifecycleScope.launch {
                ServiceLocator.callRepository.updateCallStatus(callId = callId, status = "ended", endReason = "Cancelled by caller")
                ServiceLocator.callRepository.sendSignaling(callId, contactId, "bye", "{\"reason\":\"cancelled\"}")
            }
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
        timeoutJob?.cancel()
        if (!hasNavigatedToActive && callId.isNotBlank()) {
            CallSessionGuard.unregisterCall(callId)
        }
    }
}
