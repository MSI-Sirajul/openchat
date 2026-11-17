package com.msi.openchat.feature.calls

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityActiveAudioCallBinding
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.CallSessionGuard
import com.msi.openchat.service.call.OpenChatCallService
import com.msi.openchat.service.call.WebRtcCallState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Thin full-screen edge-to-edge Activity for active voice calls.
 * Binds directly to OpenChatCallService (which owns WebRTC, signaling, AudioManager, and proximity lock),
 * so opening this screen later seamlessly displays the ongoing call without reconnecting.
 */
class ActiveAudioCallActivity : AppCompatActivity() {
    private val tag = "OpenChat_ActiveAudio"

    private lateinit var binding: ActivityActiveAudioCallBinding
    private var callId: String = ""
    private var contactId: String = ""
    private var contactName: String = "Voice Call"
    private var isIncoming: Boolean = false

    private lateinit var callViewModel: CallViewModel
    private var callService: OpenChatCallService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? OpenChatCallService.LocalBinder ?: return
            val svc = binder.getService()
            callService = svc
            isBound = true
            if (::callViewModel.isInitialized) {
                callViewModel.attachBoundService(svc)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            callService = null
            isBound = false
            if (::callViewModel.isInitialized) {
                callViewModel.attachBoundService(null)
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (::callViewModel.isInitialized) {
                callViewModel.onPermissionGranted()
            }
        } else {
            Toast.makeText(this, "Microphone permission is required for voice calls", Toast.LENGTH_SHORT).show()
            if (::callViewModel.isInitialized) {
                callViewModel.endCall()
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "active_audio_call_id"
        const val EXTRA_CONTACT_ID = "active_audio_contact_id"
        const val EXTRA_CONTACT_NAME = "active_audio_contact_name"
        const val EXTRA_IS_INCOMING = "active_audio_is_incoming"

        fun createIntent(
            context: Context,
            callId: String,
            contactId: String,
            contactName: String,
            isIncoming: Boolean
        ): Intent {
            return Intent(context, ActiveAudioCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
        }

        fun createResumeIntent(
            context: Context,
            callId: String = CallSessionCoordinator.currentCallId.value.orEmpty(),
            contactId: String = CallSessionCoordinator.contactId.value,
            contactName: String = CallSessionCoordinator.contactName.value.ifBlank { "Voice Call" },
            isIncoming: Boolean = false
        ): Intent {
            return createIntent(context, callId, contactId, contactName, isIncoming)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val activeCoordinatorId = CallSessionCoordinator.currentCallId.value.orEmpty()
        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty().ifBlank { activeCoordinatorId }
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty().ifBlank { CallSessionCoordinator.contactId.value }
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: CallSessionCoordinator.contactName.value.ifBlank { "Voice Call" }
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        if (callId.isBlank() ||
            CallSessionGuard.isVideoCall(callId, null) ||
            CallSessionGuard.isVideoCallActive() ||
            VideoCallManager.isCallActive()
        ) {
            Log.w(tag, "Blocked invalid/video session in ActiveAudioCallActivity: callId=$callId")
            finish()
            return
        }

        CallSessionCoordinator.markUiPresented(callId)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityActiveAudioCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupViewModel()
        setupViews()
        observeViewModel()
        bindToAudioCallService()

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun bindToAudioCallService() {
        val bindIntent = Intent(this, OpenChatCallService::class.java)
        bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootActiveAudio) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopCapsule.updatePadding(top = systemBars.top)
            binding.layoutBottomControls.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun setupViewModel() {
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return CallViewModel(
                    context = applicationContext,
                    initialCallId = callId,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isIncoming
                ) as T
            }
        }
        callViewModel = ViewModelProvider(this, factory)[CallViewModel::class.java]
    }

    private fun setupViews() {
        binding.textContactName.text = contactName
        binding.textContactInitial.text = contactName.take(1).uppercase()

        binding.btnMute.setOnClickListener {
            callViewModel.toggleMute()
        }

        binding.btnSpeaker.setOnClickListener {
            callViewModel.toggleSpeaker()
        }

        binding.btnEndCall.setOnClickListener {
            callViewModel.endCall()
            finish()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            callViewModel.uiState.collectLatest { state ->
                if (state.contactName.isNotBlank()) {
                    binding.textContactName.text = state.contactName
                    binding.textContactInitial.text = state.contactName.take(1).uppercase()
                }
                if (state.durationSeconds > 0 || state.connectionState == WebRtcCallState.CONNECTED) {
                    val mins = state.durationSeconds / 60
                    val secs = state.durationSeconds % 60
                    binding.textCallTimer.text = String.format("%02d:%02d", mins, secs)
                } else {
                    binding.textCallTimer.text = state.callStatus
                }

                if (state.isMuted) {
                    binding.btnMute.setImageResource(R.drawable.ic_mic_off_24)
                    binding.btnMute.setColorFilter(0xFFFF8A80.toInt())
                } else {
                    binding.btnMute.setImageResource(R.drawable.ic_mic_24)
                    binding.btnMute.setColorFilter(0xFFFFFFFF.toInt())
                }

                if (state.isSpeakerOn) {
                    binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up_24)
                    binding.btnSpeaker.setColorFilter(0xFF5DD69A.toInt())
                } else {
                    binding.btnSpeaker.setImageResource(R.drawable.ic_volume_off_24)
                    binding.btnSpeaker.setColorFilter(0xFFFFFFFF.toInt())
                }

                if (!state.isCallActive || state.connectionState == WebRtcCallState.ENDED) {
                    finish()
                }
            }
        }
    }

    override fun onDestroy() {
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
        callService = null
        super.onDestroy()
        // Intentionally DO NOT call OpenChatCallService.stopCallService(this):
        // Active calls continue running in OpenChatCallService when the UI is closed.
    }
}

