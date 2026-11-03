package com.msi.openchat.service.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.msi.openchat.core.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service that maintains active voice calls.
 * Displays an ongoing notification on the dedicated CHANNEL_ACTIVE_CALLS
 * with real-time duration updates and functional "End Call" and "Speakerphone" actions.
 */
class OpenChatCallService : Service() {
    private val tag = "OpenChatCallService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var observerJob: Job? = null
    private var currentContactName: String = "OpenChat Call"
    private var currentCallId: String = ""

    companion object {
        const val ACTION_START_CALL = "com.msi.openchat.action.START_CALL"
        const val ACTION_STOP_CALL = "com.msi.openchat.action.STOP_CALL"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"

        fun startCallService(context: Context, callId: String, contactName: String) {
            val intent = Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopCallService(context: Context) {
            val intent = Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null && intent == null) {
            // Re-created by Android OS with START_STICKY
            val activeId = CallSessionCoordinator.currentCallId.value.orEmpty()
            val activeName = CallSessionCoordinator.contactName.value
            if (activeId.isNotBlank()) {
                currentCallId = activeId
                currentContactName = activeName.ifBlank { "OpenChat Call" }
                val recoveryNotification = buildOngoingCallNotification(currentContactName, CallSessionCoordinator.durationSeconds.value, CallSessionCoordinator.isSpeakerOn.value)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        Constants.NOTIFICATION_ID_ACTIVE_CALL,
                        recoveryNotification,
                        getCallForegroundServiceType()
                    )
                } else {
                    startForeground(
                        Constants.NOTIFICATION_ID_ACTIVE_CALL,
                        recoveryNotification
                    )
                }
                startStateObserver()
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_START_CALL -> {
                currentCallId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                currentContactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "OpenChat Call"
                val initialNotification = buildOngoingCallNotification(currentContactName, 0, false)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        Constants.NOTIFICATION_ID_ACTIVE_CALL,
                        initialNotification,
                        getCallForegroundServiceType()
                    )
                } else {
                    startForeground(
                        Constants.NOTIFICATION_ID_ACTIVE_CALL,
                        initialNotification
                    )
                }
                startStateObserver()
            }
            ACTION_STOP_CALL -> {
                observerJob?.cancel()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.cancel(Constants.NOTIFICATION_ID_ACTIVE_CALL)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun getCallForegroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
    }

    private fun startStateObserver() {
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            combine(
                CallSessionCoordinator.durationSeconds,
                CallSessionCoordinator.isSpeakerOn
            ) { duration, isSpeakerOn ->
                Pair(duration, isSpeakerOn)
            }.collect { (duration, isSpeakerOn) ->
                val notification = buildOngoingCallNotification(currentContactName, duration, isSpeakerOn)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(Constants.NOTIFICATION_ID_ACTIVE_CALL, notification)
            }
        }
    }

    private fun buildOngoingCallNotification(
        contactName: String,
        durationSeconds: Int,
        isSpeakerOn: Boolean
    ): Notification {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        val durationFormatted = String.format("%02d:%02d", minutes, seconds)

        // Launch MainActivity without recreating (singleTop)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2002,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // End Call action
        val endCallIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_END_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, currentCallId)
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            this,
            (currentCallId + "_end").hashCode(),
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Speakerphone Toggle action
        val speakerIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_TOGGLE_SPEAKER
            putExtra(CallActionReceiver.EXTRA_CALL_ID, currentCallId)
        }
        val speakerPendingIntent = PendingIntent.getBroadcast(
            this,
            (currentCallId + "_speaker").hashCode(),
            speakerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val speakerTitle = if (isSpeakerOn) "Earpiece" else "Speaker"
        val speakerIcon = if (isSpeakerOn) android.R.drawable.stat_sys_speakerphone else android.R.drawable.ic_lock_silent_mode_off

        return NotificationCompat.Builder(this, Constants.CHANNEL_ACTIVE_CALLS)
            .setContentTitle("Active Voice Call • $contactName")
            .setContentText("Connected • $durationFormatted")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", endPendingIntent)
            .addAction(speakerIcon, speakerTitle, speakerPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_ACTIVE_CALLS,
                "Active Call Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active voice call duration and audio controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(Constants.NOTIFICATION_ID_ACTIVE_CALL)
        Log.i(tag, "OpenChatCallService destroyed and ongoing notification removed.")
    }
}
