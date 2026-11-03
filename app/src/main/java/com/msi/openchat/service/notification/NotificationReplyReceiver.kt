package com.msi.openchat.service.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling direct replies sent from the push notification channel.
 * Uses Android RemoteInput to extract text, sends it via ChatRepository, and updates
 * the notification with inline delivery confirmation.
 */
class NotificationReplyReceiver : BroadcastReceiver() {
    private val tag = "OpenChat_ReplyReceiver"

    companion object {
        const val ACTION_REPLY_MESSAGE = "com.msi.openchat.action.REPLY_MESSAGE"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        const val EXTRA_RECIPIENT_ID = "extra_recipient_id"
        const val EXTRA_RECIPIENT_NAME = "extra_recipient_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY_MESSAGE) return

        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty()
        val recipientId = intent.getStringExtra(EXTRA_RECIPIENT_ID).orEmpty()
        val recipientName = intent.getStringExtra(EXTRA_RECIPIENT_NAME) ?: "Chat"
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 100)

        val remoteInputResults = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInputResults?.getCharSequence(KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()

        if (conversationId.isBlank() || replyText.isBlank()) {
            Log.w(tag, "Aborting inline reply: empty conversationId or replyText")
            return
        }

        Log.i(tag, "Received inline reply for conversation $conversationId: '$replyText'")

        // Send message asynchronously in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.initialize(context.applicationContext)
                ServiceLocator.chatRepository.sendTextMessage(conversationId, replyText)
                Log.i(tag, "Inline reply successfully sent to $conversationId")

                // Update notification to provide immediate visual feedback to the user
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val repliedNotification = NotificationCompat.Builder(context, Constants.CHANNEL_MESSAGES)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(recipientName)
                    .setContentText("You: $replyText")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setTimeoutAfter(3000)
                    .build()

                manager?.notify(notificationId, repliedNotification)
            } catch (e: Exception) {
                Log.e(tag, "Error sending inline reply: ${e.message}", e)
            }
        }
    }
}
