package com.msi.openchat.service.call

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
import com.example.MainActivity
import com.example.R
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.backend.BackendManager
import com.msi.openchat.core.backend.ConnectionState
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * OpenChat WhatsApp-Grade Always-On Foreground Keep-Alive & Data Sync Service.
 *
 * Capabilities:
 * - Runs as a foreground service with FOREGROUND_SERVICE_TYPE_DATA_SYNC (Android 14+ compatible).
 * - START_STICKY with stopWithTask = false to prevent OS termination when cleared from recents.
 * - Maintains active WebSocket and Firebase Realtime Database connections for instant message and call delivery.
 * - Integrates directly with BackendManager to ensure outbox synchronization and inbox processing.
 */
class OpenChatKeepAliveService : Service() {
    private val tag = "OpenChat_KeepAlive"
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var callListener: ValueEventListener? = null
    private var convListener: ChildEventListener? = null
    private var currentUserId: String? = null

    companion object {
        const val ACTION_START = "com.msi.openchat.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "com.msi.openchat.action.STOP_KEEP_ALIVE"
        const val NOTIFICATION_ID_KEEP_ALIVE = 2026
        const val CHANNEL_SYNC = "openchat_sync_persistent"

        fun start(context: Context) {
            val intent = Intent(context, OpenChatKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("OpenChat_KeepAlive", "Failed to start keep alive service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenChatKeepAliveService::class.java).apply {
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
        Log.i(tag, "OpenChatKeepAliveService created")
        createNotificationChannel()
        promoteToForeground("Connecting to OpenChat...")
        BackendManager.initialize(applicationContext)
        observeConnectionState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(tag, "Received stop action")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                promoteToForeground("OpenChat active & syncing")
                observeSignalingAndIncoming()
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channel = NotificationChannel(
                CHANNEL_SYNC,
                "Background Connection & Sync",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps OpenChat connected for instant message and call delivery"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildPersistentNotification(statusText: String): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_SYNC else Constants.CHANNEL_NETWORK

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_chat_24)
            .setContentTitle("OpenChat")
            .setContentText(statusText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun promoteToForeground(statusText: String) {
        val notification = buildPersistentNotification(statusText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_KEEP_ALIVE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_KEEP_ALIVE, notification)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to startForeground: ${e.message}")
        }
    }

    private fun updateForegroundNotification(statusText: String) {
        val notification = buildPersistentNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID_KEEP_ALIVE, notification)
    }

    private fun observeConnectionState() {
        serviceScope.launch {
            BackendManager.connectionState.collectLatest { state ->
                val label = when (state) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Reconnecting -> state.reason
                    is ConnectionState.Offline -> "Offline - reconnecting"
                }
                updateForegroundNotification(label)
            }
        }
    }

    private fun observeSignalingAndIncoming() {
        val uid = ServiceLocator.sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            return
        }

        if (currentUserId == uid && callListener != null) return
        currentUserId = uid

        removeListeners()
        Log.i(tag, "Listening to background events for user: $uid")

        // 1. Incoming Call listener on /users/{uid}/incoming_call
        val incomingCallNode = FirebaseManager.usersRef.child(uid).child("incoming_call")
        val cListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val callId = snapshot.child("call_id").getValue(String::class.java).orEmpty()
                    val callerId = snapshot.child("caller_id").getValue(String::class.java).orEmpty()
                    val callerName = snapshot.child("caller_name").getValue(String::class.java) ?: "Friend"

                    if (callId.isNotBlank()) {
                        Log.i(tag, "Incoming background call: $callId from $callerId")
                        serviceScope.launch {
                            try {
                                ServiceLocator.callRepository.sendSignaling(callId, callerId, "ringing", "{}")
                                ServiceLocator.callRepository.updateCallStatus(callId, "ringing")
                            } catch (_: Exception) {}

                            OpenChatNotificationManager.showIncomingCallNotification(
                                context = applicationContext,
                                callId = callId,
                                callerId = callerId,
                                callerName = callerName
                            )
                        }
                    }
                } else {
                    OpenChatNotificationManager.cancelIncomingCallNotification(applicationContext)
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        incomingCallNode.addValueEventListener(cListener)
        callListener = cListener

        // 2. Incoming Messages listener on /conversations/{uid}
        val convNode = FirebaseManager.conversationsRef.child(uid)
        val cnListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {}

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val lastSenderId = snapshot.child("last_message_sender_id").getValue(String::class.java)
                val unreadCount = snapshot.child("unread_count").getValue(Int::class.java) ?: 0
                val convId = snapshot.child("id").getValue(String::class.java).orEmpty()
                val lastText = snapshot.child("last_message_text").getValue(String::class.java) ?: "New message"

                if (!lastSenderId.isNullOrBlank() && lastSenderId != uid && unreadCount > 0) {
                    serviceScope.launch {
                        ServiceLocator.chatRepository.markMessagesAsDelivered(convId)
                        val senderProfile = when (val p = ServiceLocator.userRepository.getUserProfile(lastSenderId)) {
                            is com.msi.openchat.core.common.Resource.Success -> p.data
                            else -> null
                        }
                        val senderName = senderProfile?.displayName ?: "Friend"

                        OpenChatNotificationManager.showMessageNotification(
                            context = applicationContext,
                            conversationId = convId,
                            senderId = lastSenderId,
                            senderName = senderName,
                            content = lastText,
                            messageType = "text"
                        )
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        convNode.addChildEventListener(cnListener)
        convListener = cnListener
    }

    private fun removeListeners() {
        val uid = currentUserId ?: return
        callListener?.let { l: ValueEventListener ->
            FirebaseManager.usersRef.child(uid).child("incoming_call").removeEventListener(l)
        }
        convListener?.let { l: ChildEventListener ->
            FirebaseManager.conversationsRef.child(uid).removeEventListener(l)
        }
        callListener = null
        convListener = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(tag, "App cleared from recent apps - resurrecting keep-alive foreground service")
        try {
            val restartIntent = Intent(applicationContext, OpenChatKeepAliveService::class.java).apply {
                action = ACTION_START
                setPackage(packageName)
            }
            val pendingIntent = PendingIntent.getService(
                applicationContext,
                2026,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to schedule service resurrection: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListeners()
        serviceScope.cancel()
        Log.i(tag, "OpenChatKeepAliveService destroyed")
    }
}
