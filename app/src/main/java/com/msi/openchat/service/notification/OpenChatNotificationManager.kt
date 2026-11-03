package com.msi.openchat.service.notification

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.example.MainActivity
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.feature.calls.IncomingCallActivity
import com.msi.openchat.service.call.CallActionReceiver

object OpenChatNotificationManager {
    private const val TAG = "OpenChatNotification"

    const val ACTION_ACCEPT_CALL = "com.msi.openchat.action.ACCEPT_CALL"
    const val ACTION_DECLINE_CALL = "com.msi.openchat.action.DECLINE_CALL"
    const val EXTRA_CALL_ID = "incoming_call_id"
    const val EXTRA_CALLER_ID = "incoming_caller_id"
    const val EXTRA_CALLER_NAME = "incoming_caller_name"
    const val EXTRA_CONVERSATION_ID = "navigate_conversation_id"
    const val EXTRA_CONTACT_NAME = "contact_name"
    const val EXTRA_CONTACT_ID = "contact_id"

    fun initChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // 1. Channel for Incoming Calls (High Importance, Ringtone Sound & Vibration)
            val incomingCallChannel = NotificationChannel(
                Constants.CHANNEL_INCOMING_CALLS,
                "Incoming Voice Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alerts and ringtones for incoming voice calls"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
                setSound(ringtoneUri, audioAttributes)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
                setShowBadge(true)
            }

            // 2. Channel for Ongoing Calls (Low Importance foreground status)
            val activeCallChannel = NotificationChannel(
                Constants.CHANNEL_ACTIVE_CALLS,
                "Active Call Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active call notification and duration status"
                setShowBadge(false)
            }

            // 3. Channel for Missed Calls
            val missedCallChannel = NotificationChannel(
                Constants.CHANNEL_MISSED_CALLS,
                "Missed Calls",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts for missed audio calls"
                setShowBadge(true)
            }

            // 4. Channel for Direct Messages (High Importance, Popup Heads-Up & Notification Sound)
            val messageChannel = NotificationChannel(
                Constants.CHANNEL_MESSAGES,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up popups and sounds for text, voice, and media messages"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)
                val msgSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                    .build()
                setSound(msgSoundUri, audioAttributes)
                setShowBadge(true)
            }

            // 5. Channel for Social & Friend Requests
            val friendsChannel = NotificationChannel(
                Constants.CHANNEL_FRIENDS,
                "Friend Requests & Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts for incoming friend requests and acceptances"
                setShowBadge(true)
            }

            // 6. Channel for Network Stability Alerts
            val networkChannel = NotificationChannel(
                Constants.CHANNEL_NETWORK,
                "Connection & Network Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Alerts when network connection is unstable or lost"
            }

            manager.createNotificationChannel(incomingCallChannel)
            manager.createNotificationChannel(activeCallChannel)
            manager.createNotificationChannel(missedCallChannel)
            manager.createNotificationChannel(messageChannel)
            manager.createNotificationChannel(friendsChannel)
            manager.createNotificationChannel(networkChannel)

            Log.d(TAG, "All OpenChat notification channels initialized.")
        }
    }

    fun showIncomingCallNotification(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        // 1. Full-screen intent pointing to dedicated IncomingCallActivity
        val fullScreenIntent = IncomingCallActivity.createIntent(
            context = context,
            callId = callId,
            callerId = callerId,
            callerName = callerName
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Accept Call Action (via CallActionReceiver)
        val acceptIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_ACCEPT_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(CallActionReceiver.EXTRA_CALLER_ID, callerId)
            putExtra(CallActionReceiver.EXTRA_CALLER_NAME, callerName)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            (callId + "_accept").hashCode(),
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Decline Call Action (via CallActionReceiver)
        val declineIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = CallActionReceiver.ACTION_DECLINE_CALL
            putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(CallActionReceiver.EXTRA_CALLER_ID, callerId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            (callId + "_decline").hashCode(),
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_INCOMING_CALLS)
            .setContentTitle("Incoming Voice Call")
            .setContentText("$callerName is calling...")
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // Use Android 12+ CallStyle when available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson,
                    declinePendingIntent,
                    acceptPendingIntent
                )
            )
        } else {
            builder.addAction(android.R.drawable.ic_menu_call, "Answer", acceptPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
        }

        manager.notify(Constants.NOTIFICATION_ID_CALL, builder.build())
        Log.d(TAG, "Dispatched CallStyle incoming notification for $callId from $callerName")

        // If screen is off or device is locked, launch full-screen incoming call activity directly
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isScreenOff = powerManager?.isInteractive == false
        val isLocked = keyguardManager?.isKeyguardLocked == true

        if (isScreenOff || isLocked) {
            try {
                context.startActivity(fullScreenIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to directly launch IncomingCallActivity: ${e.message}")
            }
        }
    }

    fun showMissedCallNotification(context: Context, callerName: String) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            201,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_MISSED_CALLS)
            .setContentTitle("Missed Call")
            .setContentText("You missed a voice call from $callerName")
            .setSmallIcon(android.R.drawable.stat_notify_missed_call)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(201, notification)
    }

    fun cancelIncomingCallNotification(context: Context) {
        AppServiceManager.stopCallRinging(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(Constants.NOTIFICATION_ID_CALL)
    }

    fun showMessageNotification(
        context: Context,
        conversationId: String,
        senderId: String,
        senderName: String,
        content: String,
        messageType: String = "text"
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val displayBody = when (messageType) {
            "audio" -> "🎧 Voice message"
            "image" -> "📸 Photo"
            "file" -> "📎 Attachment: ${content.ifBlank { "File" }}"
            else -> content
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONTACT_NAME, senderName)
            putExtra(EXTRA_CONTACT_ID, senderId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notifId = conversationId.hashCode().coerceAtLeast(100)

        // 1. Direct Reply RemoteInput for inline replies from notification shade
        val replyInput = androidx.core.app.RemoteInput.Builder(NotificationReplyReceiver.KEY_TEXT_REPLY)
            .setLabel("Reply to $senderName...")
            .build()

        val replyIntent = Intent(context, NotificationReplyReceiver::class.java).apply {
            action = NotificationReplyReceiver.ACTION_REPLY_MESSAGE
            putExtra(NotificationReplyReceiver.EXTRA_CONVERSATION_ID, conversationId)
            putExtra(NotificationReplyReceiver.EXTRA_RECIPIENT_ID, senderId)
            putExtra(NotificationReplyReceiver.EXTRA_RECIPIENT_NAME, senderName)
            putExtra(NotificationReplyReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPendingIntent
        ).addRemoteInput(replyInput)
            .setAllowGeneratedReplies(true)
            .build()

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_MESSAGES)
            .setContentTitle(senderName)
            .setContentText(displayBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayBody))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSound(soundUri)
            .setVibrate(longArrayOf(0, 200, 100, 200))
            .setContentIntent(pendingIntent)
            .addAction(replyAction)
            .setAutoCancel(true)
            .build()

        manager.notify(notifId, notification)
        Log.d(TAG, "Dispatched message notification with inline Reply action for conversation $conversationId from $senderName")
    }

    fun showFriendRequestNotification(
        context: Context,
        senderName: String
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            301,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_FRIENDS)
            .setContentTitle("New Friend Request")
            .setContentText("$senderName sent you a friend request")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        manager.notify(301, notification)
    }
}
