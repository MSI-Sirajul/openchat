package com.msi.openchat.service.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * FriendActionReceiver
 * Handles direct Accept and Cancel action buttons from the "Friend Requests" notification (Channel A)
 * without requiring the app to be open in the foreground.
 */
class FriendActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FriendActionReceiver"
        const val ACTION_ACCEPT_FRIEND_REQUEST = "com.msi.openchat.action.ACCEPT_FRIEND_REQUEST"
        const val ACTION_CANCEL_FRIEND_REQUEST = "com.msi.openchat.action.CANCEL_FRIEND_REQUEST"

        const val EXTRA_REQUEST_ID = "extra_request_id"
        const val EXTRA_SENDER_ID = "extra_sender_id"
        const val EXTRA_SENDER_NAME = "extra_sender_name"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val senderId = intent.getStringExtra(EXTRA_SENDER_ID).orEmpty()
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0)

        Log.i(TAG, "onReceive action=$action requestId=$requestId senderId=$senderId notifId=$notifId")

        // Cancel notification immediately from shade
        if (notifId != 0) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.cancel(notifId)
        }

        ServiceLocator.initialize(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    ACTION_ACCEPT_FRIEND_REQUEST -> {
                        if (requestId.isNotBlank()) {
                            val res = ServiceLocator.friendRepository.acceptFriendRequest(requestId)
                            when (res) {
                                is Resource.Success -> {
                                    Log.i(TAG, "Accepted friend request $requestId from notification. Conv: ${res.data}")
                                }
                                is Resource.Error -> {
                                    Log.w(TAG, "Failed to accept friend request $requestId: ${res.message}")
                                }
                                else -> Unit
                            }
                        }
                    }
                    ACTION_CANCEL_FRIEND_REQUEST -> {
                        if (requestId.isNotBlank()) {
                            val res = ServiceLocator.friendRepository.declineFriendRequest(requestId)
                            Log.i(TAG, "Cancelled friend request $requestId from notification: $res")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing friend action $action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
