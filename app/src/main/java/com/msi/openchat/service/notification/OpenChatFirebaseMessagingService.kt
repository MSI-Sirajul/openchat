package com.msi.openchat.service.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.security.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenChatFirebaseMessagingService : FirebaseMessagingService() {
    private val tag = "OpenChatFCM"

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(tag, "Refreshed FCM token: $token")
        val sessionManager = SessionManager(applicationContext)
        val userId = sessionManager.getUserId() ?: return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.userRepository.updateFcmToken(userId, token)
            } catch (e: Exception) {
                Log.e(tag, "Failed to upload FCM token to Firebase: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(tag, "Received push message: ${remoteMessage.data}")

        val type = remoteMessage.data["type"] ?: "new_message"
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "OpenChat"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: "You have a new update"
        val senderId = remoteMessage.data["sender_id"]
        val conversationId = remoteMessage.data["conversation_id"]
        val callId = remoteMessage.data["call_id"]

        OpenChatNotificationManager.initChannels(applicationContext)

        when (type) {
            "incoming_call" -> {
                val callerName = remoteMessage.data["caller_name"] ?: title
                val callType = remoteMessage.data["call_type"] ?: "audio"
                if (callType == "video") {
                    OpenChatNotificationManager.showIncomingVideoCallNotification(
                        context = applicationContext,
                        callId = callId.orEmpty(),
                        callerId = senderId.orEmpty(),
                        callerName = callerName
                    )
                } else {
                    OpenChatNotificationManager.showIncomingCallNotification(
                        context = applicationContext,
                        callId = callId.orEmpty(),
                        callerId = senderId.orEmpty(),
                        callerName = callerName
                    )
                }
            }
            "new_message" -> {
                OpenChatNotificationManager.showMessageNotification(
                    context = applicationContext,
                    conversationId = conversationId.orEmpty(),
                    senderId = senderId.orEmpty(),
                    senderName = title,
                    content = body,
                    messageType = remoteMessage.data["message_type"] ?: "text"
                )
            }
            "friend_request" -> {
                val requestId = remoteMessage.data["request_id"] ?: remoteMessage.data["id"].orEmpty()
                val reqSenderId = remoteMessage.data["sender_id"].orEmpty()
                val reqSenderName = remoteMessage.data["sender_name"] ?: title
                OpenChatNotificationManager.showFriendRequestNotification(
                    context = applicationContext,
                    requestId = requestId,
                    senderId = reqSenderId,
                    senderName = reqSenderName
                )
            }
            "friend_accepted" -> {
                val convId = remoteMessage.data["conversation_id"].orEmpty()
                val accepterId = remoteMessage.data["accepter_id"] ?: remoteMessage.data["friend_id"].orEmpty()
                val accepterName = remoteMessage.data["accepter_name"] ?: remoteMessage.data["friend_name"] ?: title
                OpenChatNotificationManager.showFriendAcceptedNotification(
                    context = applicationContext,
                    conversationId = convId,
                    friendId = accepterId,
                    friendName = accepterName
                )
            }
            else -> {
                OpenChatNotificationManager.showMessageNotification(
                    context = applicationContext,
                    conversationId = conversationId.orEmpty(),
                    senderId = senderId.orEmpty(),
                    senderName = title,
                    content = body
                )
            }
        }
    }
}

