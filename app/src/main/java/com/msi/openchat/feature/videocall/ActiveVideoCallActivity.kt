package com.msi.openchat.feature.videocall

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.MotionEvent
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
import com.example.R
import com.example.databinding.ActivityActiveVideoCallBinding
import com.msi.openchat.service.call.CallSessionCoordinator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen edge-to-edge Active Video Call Activity.
 * Binds to VideoCallService (which owns WebRTC + foreground notification),
 * renders local & remote streams, and enters 16:9 Picture-in-Picture on Back / leave.
 */
class ActiveVideoCallActivity : AppCompatActivity(), SensorEventListener {
    private val tag = "OpenChat_ActiveVideo"

    private lateinit var binding: ActivityActiveVideoCallBinding
    private var videoService: VideoCallService? = null
    private var isServiceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? VideoCallService.LocalBinder ?: return
            videoService = binder.getService()
            isServiceBound = true
            if (sessionStarted && !isFinishing && hasCameraAndMicPermissions()) {
                attachRenderers()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            videoService = null
            isServiceBound = false
        }
    }

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
    private var localRendererAttached = false
    private var remoteRendererAttached = false

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
                VideoCallManager.declineIncomingCall(applicationContext, callId, peerId)
            } else {
                VideoCallManager.endCall("Permissions denied")
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
                putExtra("callId", callId)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra("peerUserId", peerId)
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
        VideoCallManager.initialize(applicationContext)
        VideoCallManager.resumeActivityClassName = ActiveVideoCallActivity::class.java.name

        val currentState = VideoCallManager.uiState.value
        callId = intent.getStringExtra(EXTRA_CALL_ID)?.ifBlank { null }
            ?: intent.getStringExtra("callId")?.ifBlank { null }
            ?: currentState.callId
        peerId = intent.getStringExtra(EXTRA_PEER_ID)?.ifBlank { null }
            ?: intent.getStringExtra("peerUserId")?.ifBlank { null }
            ?: currentState.peerId
        peerName = intent.getStringExtra(EXTRA_PEER_NAME)?.takeIf { it.isNotBlank() }
            ?: currentState.peerName.ifBlank { "OpenChat Contact" }
        isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false) ||
            intent.getBooleanExtra("autoAccept", false)

        if (callId.startsWith("aud_") || CallSessionCoordinator.isActive()) {
            finish()
            return
        }

        setupWindowAndLockScreen()

        binding = ActivityActiveVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdge()
        setupSensors()
        setupBackNavigation()
        bindVideoService()
        observeState()

        if (hasCameraAndMicPermissions()) {
            startVideoCallSession()
        } else {
            val missing = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.CAMERA)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.RECORD_AUDIO)
            }
            videoPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupWindowAndLockScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
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
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isInPictureInPictureMode && isCallPipEligible()) {
                    val entered = enterPipMode()
                    if (entered) return
                }
                if (isCallPipEligible()) {
                    moveTaskToBack(true)
                    return
                }
                releaseRenderers()
                videoService?.endCall("Call ended") ?: VideoCallManager.endCall("Call ended")
                finish()
            }
        })
    }

    private fun isCallPipEligible(): Boolean {
        val status = VideoCallManager.uiState.value.status
        return status is VideoCallStatus.Connected ||
            status is VideoCallStatus.Connecting ||
            status is VideoCallStatus.Reconnecting
    }

    private fun bindVideoService() {
        if (isServiceBound) return
        try {
            bindService(VideoCallService.bindIntent(this), serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.w(tag, "Unable to bind VideoCallService: ${e.message}")
        }
    }

    private fun hasCameraAndMicPermissions(): Boolean {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasCam && hasMic
    }

    private fun startVideoCallSession() {
        if (sessionStarted) return
        sessionStarted = true
        setupViews()
        setupDraggablePreview()

        if (isIncoming && callId.isNotBlank()) {
            VideoCallService.startAndAcceptCall(
                context = this,
                callId = callId,
                peerUserId = peerId,
                peerName = peerName,
                targetActivityClass = ActiveVideoCallActivity::class.java.name
            )
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

    private fun attachRenderers() {
        if (!hasCameraAndMicPermissions()) return
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !localRendererAttached && binding.cardLocalPreview.visibility == View.VISIBLE) {
            videoService?.attachLocalRenderer(binding.localVideoView)
                ?: VideoCallManager.attachLocalRenderer(binding.localVideoView)
            localRendererAttached = true
        }
        if (!remoteRendererAttached) {
            videoService?.attachRemoteRenderer(binding.remoteVideoView)
                ?: VideoCallManager.attachRemoteRenderer(binding.remoteVideoView)
            remoteRendererAttached = true
        }
    }

    private fun releaseLocalRenderer() {
        try {
            videoService?.detachLocalRenderer() ?: VideoCallManager.detachLocalRenderer()
        } catch (_: Exception) {}
        if (localRendererAttached) {
            localRendererAttached = false
            try { binding.localVideoView.release() } catch (_: Exception) {}
        }
    }

    private fun releaseRenderers() {
        try {
            videoService?.detachRenderers() ?: VideoCallManager.detachRenderers()
        } catch (_: Exception) {}
        if (localRendererAttached) {
            localRendererAttached = false
            try { binding.localVideoView.release() } catch (_: Exception) {}
        }
        if (remoteRendererAttached) {
            remoteRendererAttached = false
            try { binding.remoteVideoView.release() } catch (_: Exception) {}
        }
    }

    private fun setupViews() {
        binding.localVideoView.setZOrderMediaOverlay(true)
        attachRenderers()

        binding.btnMinimize.setOnClickListener {
            enterPipMode()
        }

        binding.btnMute.setOnClickListener {
            videoService?.toggleMute() ?: VideoCallManager.toggleMute()
        }

        binding.btnCameraToggle.setOnClickListener {
            videoService?.toggleCamera() ?: VideoCallManager.toggleCamera()
        }

        binding.btnSwitchCamera.setOnClickListener {
            videoService?.switchCamera() ?: VideoCallManager.switchCamera()
        }

        binding.btnSpeaker.setOnClickListener {
            videoService?.toggleSpeaker() ?: VideoCallManager.toggleSpeaker()
        }

        binding.btnEndCall.setOnClickListener {
            releaseRenderers()
            videoService?.endCall("Call ended by user") ?: VideoCallManager.endCall("Call ended by user")
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

                    newX = newX.coerceIn(0f, (parentWidth - cardWidth).coerceAtLeast(0f))
                    newY = newY.coerceIn(0f, (parentHeight - cardHeight).coerceAtLeast(0f))

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
                        (parentWidth - cardWidth - (16f * resources.displayMetrics.density)).coerceAtLeast(0f)
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
            VideoCallManager.uiState.collectLatest { state ->
                updateUiForState(state)
            }
        }
    }

    private fun updateUiForState(state: VideoCallUiState) {
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        binding.textPeerName.text = state.peerName.ifBlank { peerName }
        binding.textCallTimer.text = state.formattedDuration

        if (state.isMicMuted) {
            binding.btnMute.setImageResource(R.drawable.ic_mic_off_24)
            binding.btnMute.setColorFilter(0xFFFF8A80.toInt())
        } else {
            binding.btnMute.setImageResource(R.drawable.ic_mic_24)
            binding.btnMute.setColorFilter(0xFFFFFFFF.toInt())
        }

        if (state.isCameraEnabled) {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_camera_24)
            binding.btnCameraToggle.setColorFilter(0xFFFFFFFF.toInt())
            binding.layoutLocalCameraOff.visibility = View.GONE
        } else {
            binding.btnCameraToggle.setImageResource(R.drawable.ic_videocam_off_24)
            binding.btnCameraToggle.setColorFilter(0xFFFF8A80.toInt())
            binding.layoutLocalCameraOff.visibility = View.VISIBLE
        }

        if (state.isSpeakerOn) {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_up_24)
            binding.btnSpeaker.setColorFilter(0xFFFFFFFF.toInt())
        } else {
            binding.btnSpeaker.setImageResource(R.drawable.ic_volume_off_24)
            binding.btnSpeaker.setColorFilter(0xFFB0BEC5.toInt())
        }

        binding.textReconnectingBanner.visibility =
            if (!inPip && state.isReconnecting) View.VISIBLE else View.GONE

        if (inPip) {
            VideoCallPipHelper.updateParamsIfInPip(this, binding.remoteVideoView, state.isMicMuted)
        }

        if (state.hasRemoteVideo) {
            binding.layoutRemotePlaceholder.visibility = View.GONE
        } else {
            binding.layoutRemotePlaceholder.visibility = View.VISIBLE
            binding.textPlaceholderStatus.text = "Waiting for remote video..."
            binding.textRemoteAvatarInitial.text = state.peerName.ifBlank { peerName }.take(1).uppercase()
        }

        if (state.status is VideoCallStatus.Ended) {
            releaseRenderers()
            Toast.makeText(this, state.status.reason, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun enterPipMode(): Boolean {
        val isMuted = VideoCallManager.uiState.value.isMicMuted
        return VideoCallPipHelper.enterPip(this, binding.remoteVideoView, isMuted)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isInPictureInPictureMode && isCallPipEligible()) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            releaseLocalRenderer()
            binding.layoutTopBar.visibility = View.GONE
            binding.layoutBottomControls.visibility = View.GONE
            binding.cardLocalPreview.visibility = View.GONE
            binding.textReconnectingBanner.visibility = View.GONE
        } else {
            binding.layoutTopBar.visibility = View.VISIBLE
            binding.layoutBottomControls.visibility = View.VISIBLE
            binding.cardLocalPreview.visibility = View.VISIBLE
            if (sessionStarted && !isFinishing && VideoCallManager.isCallActive()) {
                attachRenderers()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (sessionStarted && !isFinishing && VideoCallManager.isCallActive()) {
            attachRenderers()
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

    override fun onStop() {
        releaseRenderers()
        super.onStop()
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
        releaseProximityLock()
        releaseRenderers()
        if (isServiceBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isServiceBound = false
        }
        videoService = null
        super.onDestroy()
    }
}
