package com.msi.openchat.feature.videocall

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.R
import com.example.databinding.ActivityActiveVideoCallBinding
import com.msi.openchat.service.call.CallSessionCoordinator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen edge-to-edge Active Video Call Activity
 * Hosts full-screen remote video, draggable local self-view preview,
 * Picture-in-Picture (PiP), proximity detection, and call controls.
 */
class ActiveVideoCallActivity : AppCompatActivity(), SensorEventListener {
    private val tag = "OpenChat_ActiveVideo"

    private lateinit var binding: ActivityActiveVideoCallBinding
    private val repository = VideoCallRepository()

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialCardX = 0f
    private var initialCardY = 0f

    private var callId: String = ""
    private var peerId: String = ""
    private var peerName: String = "OpenChat Contact"
    private var isIncoming: Boolean = false
    private var sessionStarted = false
    private var renderersAttached = false

    private val videoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camGranted = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (camGranted && micGranted) {
            startVideoCallSession()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required for video calls", Toast.LENGTH_SHORT).show()
            if (isIncoming && callId.isNotBlank()) {
                repository.declineIncomingCall(this, callId, peerId)
            } else {
                repository.endCall("Permissions denied")
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "active_video_call_id"
        const val EXTRA_PEER_ID = "active_video_peer_id"
        const val EXTRA_PEER_NAME = "active_video_peer_name"
        const val EXTRA_IS_INCOMING = "active_video_is_incoming"

        fun createIntent(
            context: Context,
            callId: String,
            peerId: String,
            peerName: String,
            isIncoming: Boolean
        ): Intent {
            return Intent(context, ActiveVideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
            }
        }

        fun createResumeIntent(context: Context): Intent {
            return Intent(context, ActiveVideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
        peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: "OpenChat Contact"
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)

        if (callId.startsWith("aud_") || CallSessionCoordinator.isActive()) {
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityActiveVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupSensors()
        observeState()

        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasCam && hasMic) {
            startVideoCallSession()
        } else {
            val missing = mutableListOf<String>()
            if (!hasCam) missing.add(Manifest.permission.CAMERA)
            if (!hasMic) missing.add(Manifest.permission.RECORD_AUDIO)
            videoPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startVideoCallSession() {
        if (sessionStarted) return
        sessionStarted = true
        setupViews()
        setupDraggablePreview()

        // If arriving from an incoming video call accept, connect to the video call
        if (isIncoming && callId.isNotBlank()) {
            repository.acceptIncomingCall(this, callId, peerId, peerName)
        }
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootActiveVideo) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopBar.updatePadding(top = systemBars.top)
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
                        "OpenChat:ActiveVideoProximityLock"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Proximity setup error: ${e.message}")
        }
    }

    private fun setupViews() {
        // Attach WebRTC SurfaceViewRenderers
        binding.localVideoView.setZOrderMediaOverlay(true)
        repository.attachLocalRenderer(binding.localVideoView)
        repository.attachRemoteRenderer(binding.remoteVideoView)
        renderersAttached = true

        // Minimize / PiP
        binding.btnMinimize.setOnClickListener {
            enterPipMode()
        }

        // Controls
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

        binding.textPeerName.text = peerName
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

                    val parentWidth = binding.rootActiveVideo.width.toFloat()
                    val parentHeight = binding.rootActiveVideo.height.toFloat()
                    val cardWidth = view.width.toFloat()
                    val cardHeight = view.height.toFloat()

                    var newX = initialCardX + dx
                    var newY = initialCardY + dy

                    newX = newX.coerceIn(0f, parentWidth - cardWidth)
                    newY = newY.coerceIn(0f, parentHeight - cardHeight)

                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val parentWidth = binding.rootActiveVideo.width.toFloat()
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

    private fun observeState() {
        lifecycleScope.launch {
            repository.callState.collectLatest { state ->
                updateUiForState(state)
            }
        }
    }

    private fun updateUiForState(state: VideoCallUiState) {
        binding.textPeerName.text = state.peerName.ifBlank { peerName }
        binding.textCallTimer.text = state.formattedDuration

        // Microphone mute
        if (state.isMicMuted) {
            binding.btnMute.setImageResource(R.drawable.ic_mic_off_24)
            binding.btnMute.setColorFilter(0xFFFF8A80.toInt())
        } else {
            binding.btnMute.setImageResource(R.drawable.ic_mic_24)
            binding.btnMute.setColorFilter(0xFFFFFFFF.toInt())
        }

        // Camera toggle
        if (state.isCameraEnabled) {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_camera_24)
            binding.btnCameraToggle.setColorFilter(0xFFFFFFFF.toInt())
            binding.layoutLocalCameraOff.visibility = View.GONE
        } else {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_videocam_off_24)
            binding.btnCameraToggle.setColorFilter(0xFFFF8A80.toInt())
            binding.layoutLocalCameraOff.visibility = View.VISIBLE
        }

        // Speakerphone
        if (state.isSpeakerOn) {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up_24)
            binding.btnSpeaker.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_off_24)
            binding.btnSpeaker.setColorFilter(0xFFB0BEC5.toInt())
        }

        // Reconnecting overlay
        binding.textReconnectingBanner.visibility = if (state.isReconnecting) View.VISIBLE else View.GONE

        // Remote video visibility
        if (state.hasRemoteVideo) {
            binding.layoutRemotePlaceholder.visibility = View.GONE
        } else {
            binding.layoutRemotePlaceholder.visibility = View.VISIBLE
            binding.textPlaceholderStatus.text = "Waiting for remote video..."
            binding.textRemoteAvatarInitial.text = state.peerName.ifBlank { peerName }.take(1).uppercase()
        }

        if (state.status is VideoCallStatus.Ended) {
            Toast.makeText(this, state.status.reason, Toast.LENGTH_SHORT).show()
            finish()
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
        val state = repository.callState.value
        if (state.status is VideoCallStatus.Connected) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            binding.layoutTopBar.visibility = View.GONE
            binding.layoutBottomControls.visibility = View.GONE
            binding.cardLocalPreview.visibility = View.GONE
            binding.textReconnectingBanner.visibility = View.GONE
        } else {
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
        releaseProximityLock()
        try {
            repository.detachRenderers()
        } catch (_: Exception) {}
        if (renderersAttached) {
            renderersAttached = false
            try { binding.localVideoView.clearImage() } catch (_: Exception) {}
            try { binding.localVideoView.release() } catch (_: Exception) {}
            try { binding.remoteVideoView.clearImage() } catch (_: Exception) {}
            try { binding.remoteVideoView.release() } catch (_: Exception) {}
        }
    }
}
