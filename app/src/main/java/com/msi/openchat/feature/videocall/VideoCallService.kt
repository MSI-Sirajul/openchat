package com.msi.openchat.feature.videocall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer

/**
 * Foreground Service (camera | microphone | phoneCall) that owns the Video Call
 * WebRTC session, foreground notification, and lifecycle across Activity recreation and PiP.
 * Video Call Activities bind to this service via LocalBinder solely to render video frames and UI.
 */
class VideoCallService : Service() {
    private val tag = "OpenChat_VideoCallSvc"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null

    inner class LocalBinder : Binder() {
        fun getService(): VideoCallService = this@VideoCallService
    }

    private val binder = LocalBinder()

    val callState: StateFlow<VideoCallUiState>
        get() = VideoCallManager.uiState

    companion object {
        const val CHANNEL_ACTIVE_VIDEO = Constants.CHANNEL_ACTIVE_VIDEO_CALLS
        const val NOTIFICATION_ID = 2027

        const val ACTION_START = "com.msi.openchat.action.START_VIDEO_CALL"
        const val ACTION_ACCEPT = "com.msi.openchat.action.SERVICE_ACCEPT_VIDEO_CALL"
        const val ACTION_STOP = "com.msi.openchat.action.STOP_VIDEO_CALL"
        const val ACTION_END_CALL = "com.msi.openchat.action.END_VIDEO_CALL"
        const val ACTION_TOGGLE_MUTE = "com.msi.openchat.action.MUTE_VIDEO_CALL"
        const val ACTION_SWITCH_CAMERA = "com.msi.openchat.action.SERVICE_SWITCH_CAMERA_VIDEO_CALL"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_NAME = "extra_peer_name"
        const val EXTRA_TARGET_ACTIVITY = "extra_target_activity"

        fun bindIntent(context: Context): Intent = Intent(context, VideoCallService::class.java)

        fun startService(context: Context, peerName: String) {
            val intent = Intent(context, VideoCallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
            }
            dispatchStartForeground(context, intent)
        }

        fun startAndAcceptCall(
            context: Context,
            callId: String,
            peerUserId: String,
            peerName: String,
            targetActivityClass: String = VideoCallActivity::class.java.name
        ) {
            VideoCallManager.resumeActivityClassName = targetActivityClass
            val intent = Intent(context, VideoCallService::class.java).apply {
                action = ACTION_ACCEPT
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_PEER_ID, peerUserId)
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_TARGET_ACTIVITY, targetActivityClass)
            }
            dispatchStartForeground(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, VideoCallService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }

        private fun dispatchStartForeground(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("OpenChat_VideoCallSvc", "Failed to start VideoCallService: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(applicationContext)
        VideoCallManager.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TARGET_ACTIVITY)?.takeIf { it.isNotBlank() }?.let {
            VideoCallManager.resumeActivityClassName = it
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_END_CALL -> {
                VideoCallManager.endCall(getString(R.string.call_ended_from_notif))
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                VideoCallManager.toggleMute()
            }
            ACTION_SWITCH_CAMERA -> {
                VideoCallManager.switchCamera()
            }
            ACTION_ACCEPT -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                val peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
                val peerName = intent.getStringExtra(EXTRA_PEER_NAME) ?: getString(R.string.home_openchat_contact)
                startForegroundWithNotification(peerName, getString(R.string.common_connecting), isConnected = false, isMuted = false)
                observeCallState()
                if (callId.isNotBlank()) {
                    VideoCallManager.acceptIncomingCall(applicationContext, callId, peerId, peerName)
                }
            }
            else -> {
                val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: getString(R.string.home_openchat_contact)
                val cur = VideoCallManager.uiState.value
                startForegroundWithNotification(
                    peerName = cur.peerName.ifBlank { peerName },
                    duration = cur.formattedDuration,
                    isConnected = cur.status is VideoCallStatus.Connected,
                    isMuted = cur.isMicMuted
                )
                observeCallState()
            }
        }
        return START_NOT_STICKY
    }

    // Service-owned WebRTC operations exposed to bound thin video Activities

    fun startOutgoingCall(receiverId: String, receiverName: String) {
        VideoCallManager.startOutgoingCall(applicationContext, receiverId, receiverName)
    }

    fun acceptIncomingCall(callId: String, callerId: String, callerName: String) {
        VideoCallManager.acceptIncomingCall(applicationContext, callId, callerId, callerName)
    }

    fun declineIncomingCall(callId: String, callerId: String) {
        VideoCallManager.declineIncomingCall(applicationContext, callId, callerId)
    }

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        VideoCallManager.attachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        VideoCallManager.attachRemoteRenderer(renderer)
    }

    fun detachLocalRenderer() {
        VideoCallManager.detachLocalRenderer()
    }

    fun detachRenderers() {
        VideoCallManager.detachRenderers()
    }

    fun toggleMute() {
        VideoCallManager.toggleMute()
    }

    fun toggleCamera() {
        VideoCallManager.toggleCamera()
    }

    fun switchCamera() {
        VideoCallManager.switchCamera()
    }

    fun toggleSpeaker() {
        VideoCallManager.toggleSpeaker()
    }

    fun endCall(reason: String = "") {
        VideoCallManager.endCall(reason.ifBlank { getString(R.string.call_ended_sentence) })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ACTIVE_VIDEO,
                getString(R.string.notif_channel_ongoing_video),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_ongoing_video_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(
        peerName: String,
        duration: String,
        isConnected: Boolean,
        isMuted: Boolean
    ) {
        val notification = buildOngoingNotification(peerName, duration, isConnected, isMuted)
        val hasCamera = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        var serviceType = 0
        if (hasCamera) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (hasMic) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (serviceType == 0) {
                    serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.w(tag, "startForeground permission/eligibility fallback triggered: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (fallbackEx: Exception) {
                Log.e(tag, "startForeground fallback failed, stopping VideoCallService: ${fallbackEx.message}")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground error: ${e.message}")
            stopSelf()
        }
    }

    private fun updateNotification(
        peerName: String,
        duration: String,
        isConnected: Boolean,
        isMuted: Boolean
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildOngoingNotification(peerName, duration, isConnected, isMuted))
    }

    private fun buildOngoingNotification(
        peerName: String,
        duration: String,
        isConnected: Boolean,
        isMuted: Boolean
    ): Notification {
        val contentIntent = if (VideoCallManager.resumeActivityClassName == ActiveVideoCallActivity::class.java.name) {
            ActiveVideoCallActivity.createResumeIntent(this)
        } else {
            VideoCallActivity.createResumeIntent(this)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            Constants.REQ_VIDEO_ONGOING_CONTENT,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endCallIntent = Intent(this, VideoCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_VIDEO_ONGOING_END,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, VideoCallService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_VIDEO_ONGOING_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val switchCamIntent = Intent(this, VideoCallService::class.java).apply {
            action = ACTION_SWITCH_CAMERA
        }
        val switchCamPendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_VIDEO_ONGOING_SWITCH_CAM,
            switchCamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = getString(if (isMuted) R.string.call_unmute else R.string.call_mute)
        val statusText = if (isConnected) {
            getString(R.string.call_connected_duration, duration)
        } else {
            getString(R.string.common_connecting)
        }

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE_VIDEO)
            .setSmallIcon(R.drawable.ic_video_24)
            .setContentTitle(getString(R.string.call_video_notif_title, peerName))
            .setContentText(statusText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(if (isMuted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_24, muteActionTitle, mutePendingIntent)
            .addAction(R.drawable.ic_cameraswitch_24, getString(R.string.call_video_notif_flip_short), switchCamPendingIntent)
            .addAction(R.drawable.ic_call_end_24, getString(R.string.call_end), endPendingIntent)
            .build()
    }

    private fun observeCallState() {
        stateObserverJob?.cancel()
        stateObserverJob = serviceScope.launch {
            VideoCallManager.uiState.collectLatest { state ->
                when (state.status) {
                    is VideoCallStatus.Ended,
                    is VideoCallStatus.Idle -> {
                        stopForeground(true)
                        stopSelf()
                    }
                    else -> {
                        updateNotification(
                            peerName = state.peerName.ifBlank { getString(R.string.common_contact) },
                            duration = state.formattedDuration,
                            isConnected = state.status is VideoCallStatus.Connected,
                            isMuted = state.isMicMuted
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObserverJob?.cancel()
        serviceScope.cancel()
    }
}
