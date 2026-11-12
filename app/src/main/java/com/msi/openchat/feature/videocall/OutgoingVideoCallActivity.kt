package com.msi.openchat.feature.videocall

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    companion object {
        const val EXTRA_PEER_ID = "outgoing_video_peer_id"
        const val EXTRA_PEER_NAME = "outgoing_video_peer_name"

        fun start(context: Context, peerId: String, peerName: String) {
            // Guard: Mutual exclusion check
            if (CallSessionCoordinator.isActive()) {
                Toast.makeText(context, "Cannot start video call: a voice call is active", Toast.LENGTH_SHORT).show()
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityOutgoingVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "OpenChat Contact"

        setupEdgeToEdge()
        startPulseAnimation()

        binding.textContactName.text = peerName
        binding.textContactInitial.text = peerName.take(1).uppercase()

        binding.btnCancel.setOnClickListener {
            VideoCallManager.endCall("Cancelled by caller")
            finish()
        }

        // Start outgoing video call
        VideoCallManager.startOutgoingCall(this, peerId, peerName)

        // Observe state transitions
        observeCallState()
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
