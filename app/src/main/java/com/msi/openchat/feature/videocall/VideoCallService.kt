package com.msi.openchat.feature.videocall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.msi.openchat.core.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground Service that keeps active video calls alive,
 * displays the ongoing active call notification, and handles background/PiP execution.
 */
class VideoCallService : Service() {
    private val tag = "OpenChat_VideoCallSvc"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var stateObserverJob: Job? = null

    companion object {
        const val CHANNEL_ACTIVE_VIDEO = Constants.CHANNEL_ACTIVE_VIDEO_CALLS
        const val NOTIFICATION_ID = 2027

        const val ACTION_START = "com.msi.openchat.action.START_VIDEO_CALL"
        const val ACTION_STOP = "com.msi.openchat.action.STOP_VIDEO_CALL"
        const val ACTION_END_CALL = "com.msi.openchat.action.END_VIDEO_CALL"
        const val ACTION_TOGGLE_MUTE = "com.msi.openchat.action.MUTE_VIDEO_CALL"
        const val EXTRA_PEER_NAME = "extra_peer_name"

        fun startService(context: Context, peerName: String) {
            val intent = Intent(context, VideoCallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PEER_NAME, peerName)
            }
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

        fun stopService(context: Context) {
            val intent = Intent(context, VideoCallService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.stopService(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_END_CALL -> {
                VideoCallManager.endCall("Ended from notification")
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MUTE -> {
                VideoCallManager.toggleMute()
            }
            else -> {
                val peerName = intent?.getStringExtra(EXTRA_PEER_NAME) ?: "OpenChat Contact"
                startForegroundWithNotification(peerName, "00:00", isMuted = false)
                observeCallState()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_ACTIVE_VIDEO,
                "Active Video Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing active video call status and controls"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification(peerName: String, duration: String, isMuted: Boolean) {
        val notification = buildOngoingNotification(peerName, duration, isMuted)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ requires declaring foregroundServiceType flags
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(tag, "startForeground error: ${e.message}")
        }
    }

    private fun updateNotification(peerName: String, duration: String, isMuted: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, buildOngoingNotification(peerName, duration, isMuted))
    }

    private fun buildOngoingNotification(peerName: String, duration: String, isMuted: Boolean): Notification {
        val contentIntent = ActiveVideoCallActivity.createResumeIntent(this)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // End Call Intent
        val endCallIntent = Intent(this, VideoCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this,
            1,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mute Toggle Intent
        val muteIntent = Intent(this, VideoCallService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            2,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteActionTitle = if (isMuted) "Unmute" else "Mute"

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE_VIDEO)
            .setSmallIcon(R.drawable.ic_video_24)
            .setContentTitle("Ongoing Video Call: $peerName")
            .setContentText("Duration: $duration")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_call_end_24, "End Call", endPendingIntent)
            .addAction(if (isMuted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_24, muteActionTitle, mutePendingIntent)
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
                            peerName = state.peerName.ifBlank { "Contact" },
                            duration = state.formattedDuration,
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
    }
}
