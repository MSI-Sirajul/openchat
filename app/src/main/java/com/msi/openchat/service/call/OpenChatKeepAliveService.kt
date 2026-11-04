package com.msi.openchat.service.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background Keep-Alive service that maintains the Firebase Realtime Database connection
 * alive while the app is backgrounded, idle, or when the screen is locked/off.
 * Ensures instant detection of incoming calls and chat messages at all times.
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

        fun start(context: Context) {
            val intent = Intent(context, OpenChatKeepAliveService::class.java).apply {
                action = ACTION_START
            }
            try {
                context.startService(intent)
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(tag, "Received stop action")
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                observeFirebaseSignaling()
            }
        }
        return START_STICKY
    }

    private fun observeFirebaseSignaling() {
        val uid = ServiceLocator.sessionManager.getUserId() ?: FirebaseManager.auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            stopSelf()
            return
        }

        if (currentUserId == uid && callListener != null) return
        currentUserId = uid

        // Remove old listeners if any
        removeListeners()

        Log.i(tag, "Listening to background Firebase Realtime events for user: $uid")

        // 1. Incoming Call listener on /users/{uid}/incoming_call
        val incomingCallNode = FirebaseManager.usersRef.child(uid).child("incoming_call")
        val cListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    val callId = snapshot.child("call_id").getValue(String::class.java).orEmpty()
                    val callerId = snapshot.child("caller_id").getValue(String::class.java).orEmpty()
                    val callerName = snapshot.child("caller_name").getValue(String::class.java) ?: "Friend"

                    if (callId.isNotBlank()) {
                        Log.i(tag, "Incoming background call detected: $callId from $callerId")
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
        Log.i(tag, "App task cleared from recent apps - ensuring keep-alive service persistence")
        try {
            val restartIntent = Intent(applicationContext, OpenChatKeepAliveService::class.java).apply {
                action = ACTION_START
                setPackage(packageName)
            }
            val pendingIntent = android.app.PendingIntent.getService(
                applicationContext,
                2026,
                restartIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
            alarmManager?.set(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1000,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to schedule service resurrection on task removed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        removeListeners()
        serviceScope.cancel()
        Log.i(tag, "OpenChatKeepAliveService destroyed")
    }
}
