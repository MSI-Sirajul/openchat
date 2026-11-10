package com.msi.openchat.feature.videocall

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.util.Rational
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityVideoCallBinding
import com.msi.openchat.core.service.AppServiceManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen Video Call Activity
 * Supports lock screen awakening, PiP (Picture-in-Picture), draggable local preview,
 * proximity sensor detection, and real-time WebRTC audio/video rendering.
 */
class VideoCallActivity : AppCompatActivity(), SensorEventListener {
    private val tag = "OpenChat_VideoCallUI"

    private lateinit var binding: ActivityVideoCallBinding
    private val repository = VideoCallRepository()

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialCardX = 0f
    private var initialCardY = 0f

    private var isOutgoing = false
    private var targetPeerId = ""
    private var targetPeerName = ""
    private var targetCallId = ""

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true

        if (cameraGranted && micGranted) {
            proceedWithCall()
        } else {
            Toast.makeText(
                this,
                "Camera and Microphone permissions are required for video calls",
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"

        fun startOutgoing(context: Context, peerId: String, peerName: String) {
            val intent = Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_IS_OUTGOING, true)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            context.startActivity(intent)
        }

        fun startIncoming(context: Context, callId: String, callerId: String, callerName: String) {
            val intent = Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_IS_INCOMING, true)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, callerId)
                putExtra(EXTRA_PEER_NAME, callerName)
            }
            context.startActivity(intent)
        }

        fun createResumeIntent(context: Context): Intent {
            return Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowAndLockScreen()

        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isOutgoing = intent.getBooleanExtra(EXTRA_IS_OUTGOING, false)
        val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
        targetCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        targetPeerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        targetPeerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "OpenChat Contact"

        setupSensors()
        setupViews()
        setupDraggablePreview()
        checkPermissionsAndStart(isIncoming)
        observeState()
    }

    private fun setupWindowAndLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
                        "OpenChat:VideoProximityLock"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Proximity setup error: ${e.message}")
        }
    }

    private fun setupViews() {
        // Attach WebRTC SurfaceViewRenderers
        repository.attachLocalRenderer(binding.localVideoView)
        repository.attachRemoteRenderer(binding.remoteVideoView)

        // Minimize / PiP button
        binding.btnMinimize.setOnClickListener {
            enterPipMode()
        }

        // Call controls
        binding.btnMute.setOnClickListener {
            repository.toggleMute()
        }

        binding.btnCameraToggle.setOnClickListener {
            repository.toggleCamera()
        }

        binding.btnSwitchCamera.setOnClickListener {
            repository.switchCamera()
        }

        binding.btnSpeaker.setOnClickListener {
            repository.toggleSpeaker()
        }

        binding.btnEndCall.setOnClickListener {
            repository.endCall("Call ended by user")
            finish()
        }

        // Outgoing cancel button
        binding.btnCancelOutgoing.setOnClickListener {
            repository.endCall("Cancelled by caller")
            finish()
        }

        // Incoming Accept & Decline
        binding.btnAcceptIncoming.setOnClickListener {
            AppServiceManager.stopCallRinging(this)
            repository.acceptIncomingCall(this, targetCallId, targetPeerId, targetPeerName)
        }

        binding.btnDeclineIncoming.setOnClickListener {
            AppServiceManager.stopCallRinging(this)
            repository.declineIncomingCall(this, targetCallId, targetPeerId)
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDraggablePreview() {
        binding.cardLocalPreview.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialCardX = view.x
                    initialCardY = view.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    val parentWidth = binding.rootVideoCall.width.toFloat()
                    val parentHeight = binding.rootVideoCall.height.toFloat()
                    val cardWidth = view.width.toFloat()
                    val cardHeight = view.height.toFloat()

                    var newX = initialCardX + dx
                    var newY = initialCardY + dy

                    // Boundary checks
                    newX = newX.coerceIn(0f, parentWidth - cardWidth)
                    newY = newY.coerceIn(0f, parentHeight - cardHeight)

                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Smooth snap to left or right screen border
                    val parentWidth = binding.rootVideoCall.width.toFloat()
                    val cardWidth = view.width.toFloat()
                    val centerX = view.x + (cardWidth / 2f)

                    val targetX = if (centerX < (parentWidth / 2f)) {
                        16f * resources.displayMetrics.density
                    } else {
                        parentWidth - cardWidth - (16f * resources.displayMetrics.density)
                    }

                    view.animate()
                        .x(targetX)
                        .setDuration(200)
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun checkPermissionsAndStart(isIncoming: Boolean) {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasCamera && hasMic) {
            proceedWithCall()
        } else {
            val needed = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun proceedWithCall() {
        if (isOutgoing) {
            binding.layoutOutgoingOverlay.visibility = View.VISIBLE
            binding.layoutIncomingOverlay.visibility = View.GONE
            binding.textOutgoingName.text = targetPeerName
            binding.textOutgoingAvatarInitial.text = targetPeerName.take(1).uppercase()
            repository.startOutgoingCall(this, targetPeerId, targetPeerName)
        } else if (intent.getBooleanExtra(EXTRA_IS_INCOMING, false)) {
            AppServiceManager.startCallRinging(this)
            binding.layoutIncomingOverlay.visibility = View.VISIBLE
            binding.layoutOutgoingOverlay.visibility = View.GONE
            binding.textIncomingName.text = targetPeerName
            binding.textIncomingAvatarInitial.text = targetPeerName.take(1).uppercase()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repository.callState.collectLatest { state ->
                updateUiForState(state)
            }
        }
    }

    private fun updateUiForState(state: VideoCallUiState) {
        binding.textPeerName.text = state.peerName.ifBlank { targetPeerName }
        binding.textCallTimer.text = state.formattedDuration

        // Microphone mute state
        if (state.isMicMuted) {
            binding.btnMute.setImageResource(R.drawable.ic_mic_off_24)
            binding.btnMute.setColorFilter(0xFFFF8A80.toInt())
        } else {
            binding.btnMute.setImageResource(R.drawable.ic_mic_24)
            binding.btnMute.setColorFilter(0xFFFFFFFF.toInt())
        }

        // Camera enable/disable state
        if (state.isCameraEnabled) {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_camera_24)
            binding.btnCameraToggle.setColorFilter(0xFFFFFFFF.toInt())
            binding.layoutLocalCameraOff.visibility = View.GONE
        } else {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_videocam_off_24)
            binding.btnCameraToggle.setColorFilter(0xFFFF8A80.toInt())
            binding.layoutLocalCameraOff.visibility = View.VISIBLE
        }

        // Speakerphone state
        if (state.isSpeakerOn) {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up_24)
            binding.btnSpeaker.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_off_24)
            binding.btnSpeaker.setColorFilter(0xFFB0BEC5.toInt())
        }

        // Network reconnecting indicator
        binding.textReconnectingBanner.visibility = if (state.isReconnecting) View.VISIBLE else View.GONE

        // State Machine UI transitions
        when (state.status) {
            is VideoCallStatus.OutgoingCalling -> {
                binding.layoutOutgoingOverlay.visibility = View.VISIBLE
                binding.layoutIncomingOverlay.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.GONE
                binding.cardLocalPreview.visibility = View.VISIBLE
                binding.layoutRemotePlaceholder.visibility = View.GONE
            }
            is VideoCallStatus.IncomingRinging -> {
                binding.layoutIncomingOverlay.visibility = View.VISIBLE
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.GONE
            }
            is VideoCallStatus.Connecting -> {
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.layoutIncomingOverlay.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.VISIBLE
                binding.layoutRemotePlaceholder.visibility = View.VISIBLE
                binding.textPlaceholderStatus.text = "Connecting video..."
                binding.textRemoteAvatarInitial.text = state.peerName.take(1).uppercase()
            }
            is VideoCallStatus.Connected -> {
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.layoutIncomingOverlay.visibility = View.GONE
                binding.layoutBottomControls.visibility = View.VISIBLE
                binding.cardLocalPreview.visibility = View.VISIBLE

                if (state.hasRemoteVideo) {
                    binding.layoutRemotePlaceholder.visibility = View.GONE
                } else {
                    binding.layoutRemotePlaceholder.visibility = View.VISIBLE
                    binding.textPlaceholderStatus.text = "Waiting for remote video..."
                    binding.textRemoteAvatarInitial.text = state.peerName.take(1).uppercase()
                }
            }
            is VideoCallStatus.Reconnecting -> {
                binding.textReconnectingBanner.visibility = View.VISIBLE
            }
            is VideoCallStatus.Ended -> {
                AppServiceManager.stopCallRinging(this)
                Toast.makeText(this, state.status.reason, Toast.LENGTH_SHORT).show()
                finish()
            }
            is VideoCallStatus.Idle -> {
                // Initial or cleared state
            }
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.w(tag, "PiP entry failed: ${e.message}")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Automatically enter PiP when user presses Home or switches apps during an active connected call
        val state = repository.callState.value
        if (state.status is VideoCallStatus.Connected) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            // Hide overlays and controls in PiP mode
            binding.layoutTopBar.visibility = View.GONE
            binding.layoutBottomControls.visibility = View.GONE
            binding.cardLocalPreview.visibility = View.GONE
            binding.textReconnectingBanner.visibility = View.GONE
        } else {
            // Restore controls when expanded back to full-screen
            binding.layoutTopBar.visibility = View.VISIBLE
            binding.layoutBottomControls.visibility = View.VISIBLE
            binding.cardLocalPreview.visibility = View.VISIBLE
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
        AppServiceManager.stopCallRinging(this)
        releaseProximityLock()
        try {
            binding.localVideoView.release()
            binding.remoteVideoView.release()
        } catch (_: Exception) {}
    }
}
