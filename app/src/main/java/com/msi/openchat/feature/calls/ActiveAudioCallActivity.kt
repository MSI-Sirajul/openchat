package com.msi.openchat.feature.calls

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityActiveAudioCallBinding
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.OpenChatCallService
import com.msi.openchat.service.call.WebRtcCallState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen edge-to-edge Activity for active ongoing voice calls.
 * Displays contact info, duration timer, mute/speaker toggles, and manages proximity sensor.
 */
class ActiveAudioCallActivity : AppCompatActivity(), SensorEventListener {
    private val tag = "OpenChat_ActiveAudio"

    private lateinit var binding: ActivityActiveAudioCallBinding
    private var callId: String = ""
    private var contactId: String = ""
    private var contactName: String = "Voice Call"
    private var isIncoming: Boolean = false

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private lateinit var callViewModel: CallViewModel

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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityActiveAudioCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
        contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "Voice Call"
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        setupEdgeToEdge()
        setupSensors()
        setupViewModel()
        setupViews()
        observeViewModel()

        // Start ongoing call foreground service
        OpenChatCallService.startCallService(this, callId, contactName)
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootActiveAudio) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopCapsule.updatePadding(top = systemBars.top)
            binding.layoutBottomControls.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupSensors() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && powerManager != null) {
                if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximityWakeLock = powerManager.newWakeLock(
                        PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                        "OpenChat:ActiveAudioProximityLock"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Proximity setup error: ${e.message}")
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
                    isAlreadyAnswered = isIncoming // If arriving from Incoming screen, already answered
                ) as T
            }
        }
        callViewModel = ViewModelProvider(this, factory)[CallViewModel::class.java]
        if (isIncoming) {
            callViewModel.acceptCall()
        }
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
                val mins = state.durationSeconds / 60
                val secs = state.durationSeconds % 60
                binding.textCallTimer.text = String.format("%02d:%02d", mins, secs)

                // Microphone mute state
                if (state.isMuted) {
                    binding.btnMute.setImageResource(R.drawable.ic_mic_off_24)
                    binding.btnMute.setColorFilter(0xFFFF8A80.toInt())
                } else {
                    binding.btnMute.setImageResource(R.drawable.ic_mic_24)
                    binding.btnMute.setColorFilter(0xFFFFFFFF.toInt())
                }

                // Speakerphone state
                if (state.isSpeakerOn) {
                    binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up_24)
                    binding.btnSpeaker.setColorFilter(0xFF5DD69A.toInt())
                } else {
                    binding.btnSpeaker.setImageResource(R.drawable.ic_volume_off_24)
                    binding.btnSpeaker.setColorFilter(0xFFFFFFFF.toInt())
                }

                if (state.connectionState == WebRtcCallState.ENDED) {
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        proximitySensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        releaseProximityLock()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            if (distance < maxRange) {
                acquireProximityLock()
            } else {
                releaseProximityLock()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun acquireProximityLock() {
        try {
            if (proximityWakeLock?.isHeld == false) {
                proximityWakeLock?.acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {}
    }

    private fun releaseProximityLock() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseProximityLock()
        OpenChatCallService.stopCallService(this)
    }
}
