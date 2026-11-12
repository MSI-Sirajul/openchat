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
    const val EXTRA_AUTO_SEND_HI = "extra_auto_send_hi"
    const val EXTRA_NAVIGATE_FRIEND_REQUESTS = "navigate_friend_requests"

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

            // 1b. Channel for Incoming Video Calls
            val incomingVideoCallChannel = NotificationChannel(
                Constants.CHANNEL_INCOMING_VIDEO_CALLS,
                "Incoming Video Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen alerts and ringtones for incoming video calls"
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

            // 5. Channel for Social & Friend Updates
            val friendsChannel = NotificationChannel(
                Constants.CHANNEL_FRIENDS,
                "Friend Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts for friend updates"
                setShowBadge(true)
            }

            // 5a. Channel A: "Friend Requests" (High Importance, Accept & Cancel Actions)
            val friendRequestsChannel = NotificationChannel(
                Constants.CHANNEL_FRIEND_REQUESTS,
                "Friend Requests",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts for incoming friend requests with instant Accept and Cancel options"
                enableVibration(true)
                setShowBadge(true)
            }

            // 5b. Channel B: "Friend Accepted" (High Importance, Say Hi Action)
            val friendAcceptedChannel = NotificationChannel(
                Constants.CHANNEL_FRIEND_ACCEPTED,
                "Friend Accepted",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when someone accepts your friend request with Say Hi shortcut"
                enableVibration(true)
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

            // 2b. Channel for Ongoing Video Calls (Low Importance foreground status)
            val activeVideoCallChannel = NotificationChannel(
                Constants.CHANNEL_ACTIVE_VIDEO_CALLS,
                "Ongoing Video Calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active video call status and controls"
                setShowBadge(false)
            }

            manager.createNotificationChannel(incomingCallChannel)
            manager.createNotificationChannel(incomingVideoCallChannel)
            manager.createNotificationChannel(activeCallChannel)
            manager.createNotificationChannel(activeVideoCallChannel)
            manager.createNotificationChannel(missedCallChannel)
            manager.createNotificationChannel(messageChannel)
            manager.createNotificationChannel(friendsChannel)
            manager.createNotificationChannel(friendRequestsChannel)
            manager.createNotificationChannel(friendAcceptedChannel)
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

        // 1. Full-screen intent pointing to dedicated full-screen IncomingAudioCallActivity
        val fullScreenIntent = com.msi.openchat.feature.calls.IncomingAudioCallActivity.createIntent(
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

    fun showIncomingVideoCallNotification(
        context: Context,
        callId: String,
        callerId: String,
        callerName: String
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val fullScreenIntent = com.msi.openchat.feature.videocall.IncomingVideoCallActivity.createIntent(
            context = context,
            callId = callId,
            callerId = callerId,
            callerName = callerName
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            (callId + "_video").hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept Action via VideoCallActionReceiver (strictly isolated from audio)
        val acceptIntent = Intent(context, com.msi.openchat.feature.videocall.VideoCallActionReceiver::class.java).apply {
            action = com.msi.openchat.feature.videocall.VideoCallActionReceiver.ACTION_ACCEPT_VIDEO_CALL
            putExtra(com.msi.openchat.feature.videocall.VideoCallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(com.msi.openchat.feature.videocall.VideoCallActionReceiver.EXTRA_CALLER_ID, callerId)
            putExtra(com.msi.openchat.feature.videocall.VideoCallActionReceiver.EXTRA_CALLER_NAME, callerName)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            (callId + "_v_accept").hashCode(),
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Decline Action via VideoCallActionReceiver (strictly isolated from audio)
        val declineIntent = Intent(context, com.msi.openchat.feature.videocall.VideoCallActionReceiver::class.java).apply {
            action = com.msi.openchat.feature.videocall.VideoCallActionReceiver.ACTION_DECLINE_VIDEO_CALL
            putExtra(com.msi.openchat.feature.videocall.VideoCallActionReceiver.EXTRA_CALL_ID, callId)
            putExtra(com.msi.openchat.feature.videocall.VideoCallActionReceiver.EXTRA_CALLER_ID, callerId)
        }
        val declinePendingIntent = PendingIntent.getBroadcast(
            context,
            (callId + "_v_decline").hashCode(),
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val callerPerson = Person.Builder()
            .setName(callerName)
            .setImportant(true)
            .build()

        val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val builder = NotificationCompat.Builder(context, Constants.CHANNEL_INCOMING_VIDEO_CALLS)
            .setContentTitle("Incoming Video Call")
            .setContentText("$callerName is video calling...")
            .setSmallIcon(com.example.R.drawable.ic_video_24)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setSound(ringtoneUri)
            .setVibrate(longArrayOf(0, 1000, 1000, 1000, 1000))
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .setAutoCancel(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    callerPerson,
                    declinePendingIntent,
                    acceptPendingIntent
                )
            )
        } else {
            builder.addAction(com.example.R.drawable.ic_video_24, "Accept", acceptPendingIntent)
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declinePendingIntent)
        }

        manager.notify(Constants.NOTIFICATION_ID_VIDEO_CALL, builder.build())

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isScreenOff = powerManager?.isInteractive == false
        val isLocked = keyguardManager?.isKeyguardLocked == true

        if (isScreenOff || isLocked) {
            try {
                context.startActivity(fullScreenIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to directly launch IncomingVideoCallActivity: ${e.message}")
            }
        }
    }

    fun cancelIncomingVideoCallNotification(context: Context) {
        AppServiceManager.stopCallRinging(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(Constants.NOTIFICATION_ID_VIDEO_CALL)
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

    /**
     * Channel A "Friend Requests":
     * When someone sends a request -> notification "X sent you a friend request"
     * with Accept and Cancel action buttons, handled directly from notification
     * (via FriendActionReceiver, no app open required). On Accept -> accepted; on Cancel -> cancelled (no notif).
     */
    fun showFriendRequestNotification(
        context: Context,
        requestId: String,
        senderId: String,
        senderName: String
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val notifId = (if (requestId.isNotBlank()) requestId.hashCode() else senderId.hashCode())
            .let { (it and 0x7FFFFFFF).coerceAtLeast(3000) }

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_FRIEND_REQUESTS, true)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Accept Action via FriendActionReceiver (BroadcastReceiver, no app open required)
        val acceptIntent = Intent(context, FriendActionReceiver::class.java).apply {
            action = FriendActionReceiver.ACTION_ACCEPT_FRIEND_REQUEST
            putExtra(FriendActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(FriendActionReceiver.EXTRA_SENDER_ID, senderId)
            putExtra(FriendActionReceiver.EXTRA_SENDER_NAME, senderName)
            putExtra(FriendActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val acceptPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId * 3 + 1,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel Action via FriendActionReceiver (BroadcastReceiver, no app open required)
        val cancelIntent = Intent(context, FriendActionReceiver::class.java).apply {
            action = FriendActionReceiver.ACTION_CANCEL_FRIEND_REQUEST
            putExtra(FriendActionReceiver.EXTRA_REQUEST_ID, requestId)
            putExtra(FriendActionReceiver.EXTRA_SENDER_ID, senderId)
            putExtra(FriendActionReceiver.EXTRA_NOTIFICATION_ID, notifId)
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId * 3 + 2,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_FRIEND_REQUESTS)
            .setContentTitle("Friend Request")
            .setContentText("$senderName sent you a friend request")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_input_add, "Accept", acceptPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelPendingIntent)
            .build()

        manager.notify(notifId, notification)
        Log.d(TAG, "Dispatched friend request notification (Channel A) for requestId=$requestId from $senderName")
    }

    fun showFriendRequestNotification(
        context: Context,
        senderName: String
    ) {
        showFriendRequestNotification(context, requestId = "", senderId = "", senderName = senderName)
    }

    /**
     * Channel B "Friend Accepted":
     * When a request is accepted, notify original sender "X accepted your friend request"
     * with a "Say Hi" action button; tapping it opens chat and sends an initial "Hi" message.
     */
    fun showFriendAcceptedNotification(
        context: Context,
        conversationId: String,
        friendId: String,
        friendName: String
    ) {
        initChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val notifId = (conversationId.hashCode() and 0x7FFFFFFF).coerceAtLeast(4000)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONTACT_NAME, friendName)
            putExtra(EXTRA_CONTACT_ID, friendId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Say Hi" Action button: opens chat with autoSendHi = true
        val sayHiIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
            putExtra(EXTRA_CONTACT_NAME, friendName)
            putExtra(EXTRA_CONTACT_ID, friendId)
            putExtra(EXTRA_AUTO_SEND_HI, true)
        }
        val sayHiPendingIntent = PendingIntent.getActivity(
            context,
            notifId * 3 + 1,
            sayHiIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_FRIEND_ACCEPTED)
            .setContentTitle("Friend Request Accepted")
            .setContentText("$friendName accepted your friend request")
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_send, "Say Hi", sayHiPendingIntent)
            .build()

        manager.notify(notifId, notification)
        Log.d(TAG, "Dispatched friend accepted notification (Channel B) for friend $friendName (conv=$conversationId)")
    }

    /**
     * Contextual connection notification handling:
     * - CONNECTED -> hide notification entirely (no permanent notification).
     * - RECONNECTING / SYNCING -> transient notification, auto-cancelled once connected.
     * Uses monochrome app icon (ic_chat_24) for universal rendering across Android versions.
     */
    fun updateConnectionNotification(context: Context, status: String, isConnected: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (isConnected) {
            manager.cancel(Constants.NOTIFICATION_ID_NETWORK)
            return
        }

        initChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            Constants.NOTIFICATION_ID_NETWORK,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_NETWORK)
            .setContentTitle("OpenChat")
            .setContentText(status)
            .setSmallIcon(com.example.R.drawable.ic_chat_24)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        manager.notify(Constants.NOTIFICATION_ID_NETWORK, notification)
    }

    fun cancelConnectionNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(Constants.NOTIFICATION_ID_NETWORK)
    }
}
