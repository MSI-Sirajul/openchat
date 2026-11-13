package com.msi.openchat.feature.videocall

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.example.databinding.ActivityOutgoingVideoCallBinding
import com.msi.openchat.service.call.CallSessionCoordinator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen edge-to-edge Activity for outgoing video calls.
 * Displays "Calling..." with animated pulsing ring and transitions to ActiveVideoCallActivity on connect.
 */
class OutgoingVideoCallActivity : AppCompatActivity() {
    private val tag = "OpenChat_OutgoingVideo"

    private lateinit var binding: ActivityOutgoingVideoCallBinding
    private var peerId: String = ""
    private var peerName: String = "OpenChat Contact"
    private var pulseAnimator: ObjectAnimator? = null
    private var hasNavigatedToActive = false
    private var hasStartedCall = false

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (camGranted && micGranted) {
            beginOutgoingVideoCall()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required for video calls", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_PEER_ID = "outgoing_video_peer_id"
        const val EXTRA_PEER_NAME = "outgoing_video_peer_name"

        fun start(context: Context, peerId: String, peerName: String) {
            // Guard: Mutual exclusion check against audio calls & active video calls
            if (CallSessionCoordinator.isActive()) {
                Toast.makeText(context, "Cannot start video call: a voice call is active", Toast.LENGTH_SHORT).show()
                return
            }
            if (VideoCallManager.isCallActive()) {
                Toast.makeText(context, "A video call is already in progress", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(context, OutgoingVideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CallSessionCoordinator.isActive()) {
            Toast.makeText(this, "Cannot start video call while voice call is active", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityOutgoingVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "OpenChat Contact"

        if (peerId.isBlank()) {
            finish()
            return
        }

        setupEdgeToEdge()
        startPulseAnimation()

        binding.textContactName.text = peerName
        binding.textContactInitial.text = peerName.take(1).uppercase()

        binding.btnCancel.setOnClickListener {
            VideoCallManager.endCall("Cancelled by caller")
            finish()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                VideoCallManager.endCall("Cancelled by caller")
                finish()
            }
        })

        // Observe state transitions
        observeCallState()

        // Ensure CAMERA and RECORD_AUDIO permissions before initializing WebRTC tracks & signaling
        ensurePermissionsAndStart()
    }

    private fun ensurePermissionsAndStart() {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasCam && hasMic) {
            beginOutgoingVideoCall()
        } else {
            val missing = mutableListOf<String>()
            if (!hasCam) missing.add(Manifest.permission.CAMERA)
            if (!hasMic) missing.add(Manifest.permission.RECORD_AUDIO)
            videoPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun beginOutgoingVideoCall() {
        if (hasStartedCall) return
        hasStartedCall = true
        VideoCallManager.startOutgoingCall(this, peerId, peerName)
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootOutgoingVideo) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopCapsule.updatePadding(top = systemBars.top)
            binding.layoutCancel.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringPulse,
            PropertyValuesHolder.ofFloat(android.view.View.SCALE_X, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(android.view.View.SCALE_Y, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(android.view.View.ALPHA, 0.7f, 0.15f, 0.7f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun observeCallState() {
        lifecycleScope.launch {
            VideoCallManager.uiState.collectLatest { state ->
                when (state.status) {
                    is VideoCallStatus.Connected -> {
                        if (!hasNavigatedToActive) {
                            hasNavigatedToActive = true
                            val activeIntent = ActiveVideoCallActivity.createIntent(
                                context = this@OutgoingVideoCallActivity,
                                callId = state.callId,
                                peerId = state.peerId,
                                peerName = state.peerName,
                                isIncoming = false
                            )
                            startActivity(activeIntent)
                            finish()
                        }
                    }
                    is VideoCallStatus.Ended -> {
                        Toast.makeText(this@OutgoingVideoCallActivity, state.status.reason, Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pulseAnimator?.cancel()
    }
}
