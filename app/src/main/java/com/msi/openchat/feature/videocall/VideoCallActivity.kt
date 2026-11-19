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
import com.example.databinding.ActivityVideoCallBinding
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Full-screen edge-to-edge Video Call Activity.
 * - Supports showWhenLocked / turnScreenOn for lock screen awakening
 * - Supports autoAccept=true (callId, peerUserId, autoAccept) from notification Accept action with zero intermediate UI
 * - Binds strictly to VideoCallService (which owns WebRTC + foreground notification) so calls survive in PiP
 * - Intercepts Back press & onUserLeaveHint() to enter 16:9 Picture-in-Picture with RemoteActions before finishing
 */
class VideoCallActivity : AppCompatActivity(), SensorEventListener {
    private val tag = "OpenChat_VideoCallUI"

    private lateinit var binding: ActivityVideoCallBinding
    private var videoService: VideoCallService? = null
    private var isServiceBound = false
    private var pendingServiceAction: ((VideoCallService) -> Unit)? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? VideoCallService.LocalBinder ?: return
            val svc = binder.getService()
            videoService = svc
            isServiceBound = true
            pendingServiceAction?.invoke(svc)
            pendingServiceAction = null
            if (!isFinishing && hasCameraAndMicPermissions()) {
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

    private var isOutgoing = false
    private var isIncoming = false
    private var autoAccept = false
    private var targetPeerId = ""
    private var targetPeerName = ""
    private var targetCallId = ""

    private var hasHandledCallStart = false
    private var localRendererAttached = false
    private var remoteRendererAttached = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (cameraGranted && micGranted) {
            attachRenderers()
            proceedWithCall()
        } else {
            Toast.makeText(
                this,
                getString(R.string.call_video_permissions_required_caps),
                Toast.LENGTH_LONG
            ).show()
            if (isIncoming && targetCallId.isNotBlank()) {
                VideoCallManager.initialize(applicationContext)
                VideoCallManager.declineIncomingCall(applicationContext, targetCallId, targetPeerId)
            }
            finish()
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_IS_OUTGOING = "extra_is_outgoing"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_AUTO_ACCEPT = "autoAccept"

        fun createAutoAcceptIntent(
            context: Context,
            callId: String,
            peerUserId: String,
            peerName: String
        ): Intent {
            return Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("callId", callId)
                putExtra("peerUserId", peerUserId)
                putExtra(EXTRA_AUTO_ACCEPT, true)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerUserId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_IS_INCOMING, true)
            }
        }

        fun startOutgoing(context: Context, peerId: String, peerName: String) {
            val intent = Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_IS_OUTGOING, true)
                putExtra(EXTRA_PEER_ID, peerId)
                putExtra("peerUserId", peerId)
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
                putExtra("callId", callId)
                putExtra(EXTRA_PEER_ID, callerId)
                putExtra("peerUserId", callerId)
                putExtra(EXTRA_PEER_NAME, callerName)
            }
            context.startActivity(intent)
        }

        fun createResumeIntent(context: Context): Intent {
            return Intent(context, VideoCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowAndLockScreen()

        binding = ActivityVideoCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        VideoCallManager.initialize(applicationContext)
        VideoCallManager.resumeActivityClassName = VideoCallActivity::class.java.name

        parseIntentExtras(intent)

        setupEdgeToEdge()
        setupSensors()
        setupViews()
        setupDraggablePreview()
        setupBackNavigation()
        bindVideoService()
        observeState()
        checkPermissionsAndStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val previousCallId = targetCallId
        parseIntentExtras(intent)
        if (autoAccept || (targetCallId.isNotBlank() && targetCallId != previousCallId)) {
            hasHandledCallStart = false
            checkPermissionsAndStart()
        }
    }

    private fun parseIntentExtras(sourceIntent: Intent?) {
        val currentState = VideoCallManager.uiState.value
        isOutgoing = sourceIntent?.getBooleanExtra(EXTRA_IS_OUTGOING, false) ?: false
        isIncoming = sourceIntent?.getBooleanExtra(EXTRA_IS_INCOMING, false) ?: false
        autoAccept = sourceIntent?.getBooleanExtra(EXTRA_AUTO_ACCEPT, false)
            ?: sourceIntent?.getBooleanExtra("auto_accept", false)
            ?: false
        targetCallId = sourceIntent?.getStringExtra("callId")
            ?.ifBlank { null }
            ?: sourceIntent?.getStringExtra(EXTRA_CALL_ID)?.ifBlank { null }
            ?: currentState.callId
        targetPeerId = sourceIntent?.getStringExtra("peerUserId")
            ?.ifBlank { null }
            ?: sourceIntent?.getStringExtra(EXTRA_PEER_ID)?.ifBlank { null }
            ?: currentState.peerId
        targetPeerName = sourceIntent?.getStringExtra(EXTRA_PEER_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: currentState.peerName.ifBlank { getString(R.string.home_openchat_contact) }
        if (autoAccept) {
            isIncoming = true
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
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupEdgeToEdge() {
        val topBase = binding.layoutTopBar.paddingTop
        val bottomBase = binding.layoutBottomControls.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootVideoCall) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopBar.updatePadding(top = systemBars.top + (topBase / 3))
            binding.layoutBottomControls.updatePadding(bottom = systemBars.bottom + (bottomBase / 2))
            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isInPictureInPictureMode && isCallPipEligible()) {
                    val entered = enterPipMode()
                    if (entered) return
                }
                if (isCallPipEligible()) {
                    // Keep active call running in VideoCallService even if PiP couldn't be entered
                    moveTaskToBack(true)
                    return
                }
                releaseRenderers()
                withVideoService { it.endCall(getString(R.string.call_cancelled)) }
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

    private inline fun withVideoService(crossinline block: (VideoCallService) -> Unit) {
        val svc = videoService
        if (svc != null) {
            block(svc)
        } else {
            // Execute via VideoCallManager immediately and keep service bound for ongoing lifecycle
            when {
                else -> {
                    pendingServiceAction = { service -> block(service) }
                    bindVideoService()
                }
            }
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
                        "OpenChat:VideoProximityLock"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Proximity setup error: ${e.message}")
        }
    }

    private fun hasCameraAndMicPermissions(): Boolean {
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return hasCamera && hasMic
    }

    private fun attachRenderers() {
        if (!hasCameraAndMicPermissions()) return
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!inPip && !localRendererAttached && binding.cardLocalPreview.visibility == View.VISIBLE) {
            binding.localVideoView.setZOrderMediaOverlay(true)
            (videoService?.attachLocalRenderer(binding.localVideoView)
                ?: VideoCallManager.attachLocalRenderer(binding.localVideoView))
            localRendererAttached = true
        }
        if (!remoteRendererAttached) {
            (videoService?.attachRemoteRenderer(binding.remoteVideoView)
                ?: VideoCallManager.attachRemoteRenderer(binding.remoteVideoView))
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
        if (hasCameraAndMicPermissions()) {
            attachRenderers()
        }

        // Minimize / PiP button
        binding.btnMinimize.setOnClickListener {
            enterPipMode()
        }

        // Call controls routed through VideoCallService
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
            val reason = getString(R.string.call_ended_by_user)
            videoService?.endCall(reason) ?: VideoCallManager.endCall(reason)
            finish()
        }

        // Outgoing cancel button
        binding.btnCancelOutgoing.setOnClickListener {
            releaseRenderers()
            val reason = getString(R.string.call_cancelled_by_caller)
            videoService?.endCall(reason) ?: VideoCallManager.endCall(reason)
            finish()
        }

        // Incoming Accept & Decline (only shown if opened without autoAccept)
        binding.btnAcceptIncoming.setOnClickListener {
            acceptCall()
        }

        binding.btnDeclineIncoming.setOnClickListener {
            AppServiceManager.stopCallRinging(this)
            OpenChatNotificationManager.cancelIncomingVideoCallNotification(this)
            videoService?.declineIncomingCall(targetCallId, targetPeerId)
                ?: VideoCallManager.declineIncomingCall(applicationContext, targetCallId, targetPeerId)
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

                    newX = newX.coerceIn(0f, (parentWidth - cardWidth).coerceAtLeast(0f))
                    newY = newY.coerceIn(0f, (parentHeight - cardHeight).coerceAtLeast(0f))

                    view.x = newX
                    view.y = newY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val parentWidth = binding.rootVideoCall.width.toFloat()
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

    private fun checkPermissionsAndStart() {
        if (autoAccept) {
            // Suppress intermediate incoming UI immediately while permissions/connection proceed
            binding.layoutIncomingOverlay.visibility = View.GONE
            binding.layoutOutgoingOverlay.visibility = View.GONE
            binding.layoutBottomControls.visibility = View.VISIBLE
            binding.textPeerName.text = targetPeerName
        }

        if (hasCameraAndMicPermissions()) {
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
        if (hasHandledCallStart) return
        hasHandledCallStart = true

        attachRenderers()

        when {
            autoAccept && targetCallId.isNotBlank() -> {
                acceptCall()
            }
            isOutgoing && targetPeerId.isNotBlank() -> {
                binding.layoutOutgoingOverlay.visibility = View.VISIBLE
                binding.layoutIncomingOverlay.visibility = View.GONE
                binding.textOutgoingName.text = targetPeerName
                binding.textOutgoingAvatarInitial.text = targetPeerName.take(1).uppercase()
                VideoCallService.startService(this, targetPeerName)
                videoService?.startOutgoingCall(targetPeerId, targetPeerName)
                    ?: VideoCallManager.startOutgoingCall(applicationContext, targetPeerId, targetPeerName)
            }
            isIncoming && targetCallId.isNotBlank() -> {
                AppServiceManager.startCallRinging(this)
                binding.layoutIncomingOverlay.visibility = View.VISIBLE
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.textIncomingName.text = targetPeerName
                binding.textIncomingAvatarInitial.text = targetPeerName.take(1).uppercase()
            }
        }
    }

    /**
     * Immediately accepts an incoming video call: stops ringing, cancels incoming alert,
     * hides any incoming overlay, promotes VideoCallService to foreground (camera|microphone),
     * sends answer + ICE via WebRTC, and attaches renderers directly with no intermediate screen.
     */
    private fun acceptCall() {
        if (targetCallId.isBlank()) return
        AppServiceManager.stopCallRinging(this)
        OpenChatNotificationManager.cancelIncomingVideoCallNotification(this)
        sendBroadcast(Intent(VideoCallActionReceiver.ACTION_DISMISS_INCOMING_VIDEO_UI).apply {
            setPackage(packageName)
            putExtra(VideoCallActionReceiver.EXTRA_CALL_ID, targetCallId)
        })

        binding.layoutIncomingOverlay.visibility = View.GONE
        binding.layoutOutgoingOverlay.visibility = View.GONE
        binding.layoutBottomControls.visibility = View.VISIBLE
        binding.layoutRemotePlaceholder.visibility = View.VISIBLE
        binding.textPlaceholderStatus.text = getString(R.string.call_video_connecting)
        binding.textRemoteAvatarInitial.text = targetPeerName.take(1).uppercase()
        binding.textPeerName.text = targetPeerName

        VideoCallService.startAndAcceptCall(
            context = this,
            callId = targetCallId,
            peerUserId = targetPeerId,
            peerName = targetPeerName,
            targetActivityClass = VideoCallActivity::class.java.name
        )
        attachRenderers()
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
        binding.textReconnectingBanner.visibility =
            if (!inPip && state.isReconnecting) View.VISIBLE else View.GONE

        // Update dynamic PiP RemoteAction icons (e.g., Mute/Unmute) while in PiP
        if (inPip) {
            VideoCallPipHelper.updateParamsIfInPip(this, binding.remoteVideoView, state.isMicMuted)
        }

        // State Machine UI transitions
        when (state.status) {
            is VideoCallStatus.OutgoingCalling -> {
                if (!inPip) {
                    binding.layoutOutgoingOverlay.visibility = View.VISIBLE
                    binding.layoutIncomingOverlay.visibility = View.GONE
                    binding.layoutBottomControls.visibility = View.GONE
                    binding.cardLocalPreview.visibility = View.VISIBLE
                }
                binding.layoutRemotePlaceholder.visibility = View.GONE
            }
            is VideoCallStatus.IncomingRinging -> {
                if (!autoAccept && !inPip) {
                    binding.layoutIncomingOverlay.visibility = View.VISIBLE
                    binding.layoutOutgoingOverlay.visibility = View.GONE
                    binding.layoutBottomControls.visibility = View.GONE
                }
            }
            is VideoCallStatus.Connecting -> {
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.layoutIncomingOverlay.visibility = View.GONE
                if (!inPip) {
                    binding.layoutBottomControls.visibility = View.VISIBLE
                    binding.cardLocalPreview.visibility = View.VISIBLE
                }
                binding.layoutRemotePlaceholder.visibility = View.VISIBLE
                binding.textPlaceholderStatus.text = getString(R.string.call_video_connecting)
                binding.textRemoteAvatarInitial.text = state.peerName.ifBlank { targetPeerName }.take(1).uppercase()
            }
            is VideoCallStatus.Connected -> {
                binding.layoutOutgoingOverlay.visibility = View.GONE
                binding.layoutIncomingOverlay.visibility = View.GONE
                if (!inPip) {
                    binding.layoutBottomControls.visibility = View.VISIBLE
                    binding.cardLocalPreview.visibility = View.VISIBLE
                }

                if (state.hasRemoteVideo) {
                    binding.layoutRemotePlaceholder.visibility = View.GONE
                } else {
                    binding.layoutRemotePlaceholder.visibility = View.VISIBLE
                    binding.textPlaceholderStatus.text = getString(R.string.call_video_waiting_remote)
                    binding.textRemoteAvatarInitial.text = state.peerName.ifBlank { targetPeerName }.take(1).uppercase()
                }
            }
            is VideoCallStatus.Reconnecting -> {
                if (!inPip) {
                    binding.textReconnectingBanner.visibility = View.VISIBLE
                }
            }
            is VideoCallStatus.Ended -> {
                releaseRenderers()
                AppServiceManager.stopCallRinging(this)
                Toast.makeText(this, state.status.reason, Toast.LENGTH_SHORT).show()
                finish()
            }
            is VideoCallStatus.Idle -> {
                // Initial or cleared state
            }
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
            binding.layoutIncomingOverlay.visibility = View.GONE
            binding.layoutOutgoingOverlay.visibility = View.GONE
        } else {
            binding.layoutTopBar.visibility = View.VISIBLE
            binding.layoutBottomControls.visibility = View.VISIBLE
            binding.cardLocalPreview.visibility = View.VISIBLE
            if (!isFinishing && VideoCallManager.isCallActive() && hasCameraAndMicPermissions()) {
                attachRenderers()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!isFinishing && VideoCallManager.isCallActive() && hasCameraAndMicPermissions()) {
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

    override fun onStop() {
        releaseRenderers()
        super.onStop()
    }

    override fun onDestroy() {
        AppServiceManager.stopCallRinging(this)
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
