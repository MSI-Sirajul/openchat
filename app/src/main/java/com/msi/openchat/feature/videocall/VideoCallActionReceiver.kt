package com.msi.openchat.feature.videocall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Dedicated BroadcastReceiver for Video Call notification actions.
 * Strictly isolated from Audio Call actions to ensure no cross-triggering.
 */
class VideoCallActionReceiver : BroadcastReceiver() {
    private val tag = "OpenChat_VideoAction"

    companion object {
        const val ACTION_ACCEPT_VIDEO_CALL = "com.msi.openchat.action.ACCEPT_VIDEO_CALL"
        const val ACTION_DECLINE_VIDEO_CALL = "com.msi.openchat.action.DECLINE_VIDEO_CALL"
        const val ACTION_END_VIDEO_CALL = "com.msi.openchat.action.END_VIDEO_CALL"
        const val ACTION_DISMISS_INCOMING_VIDEO_UI = "com.msi.openchat.action.DISMISS_INCOMING_VIDEO_UI"

        const val EXTRA_CALL_ID = "extra_video_call_id"
        const val EXTRA_CALLER_ID = "extra_video_caller_id"
        const val EXTRA_CALLER_NAME = "extra_video_caller_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Caller"

        Log.i(tag, "Received video action: $action for callId=$callId")

        when (action) {
            ACTION_DECLINE_VIDEO_CALL -> {
                AppServiceManager.stopCallRinging(context)
                OpenChatNotificationManager.cancelIncomingVideoCallNotification(context)
                context.sendBroadcast(Intent(ACTION_DISMISS_INCOMING_VIDEO_UI).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_CALL_ID, callId)
                })

                if (callId.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            VideoCallManager.initialize(context.applicationContext)
                            VideoCallManager.declineIncomingCall(context, callId, callerId)
                            Log.i(tag, "Declined video call $callId from notification action")
                        } catch (e: Exception) {
                            Log.e(tag, "Error declining video call: ${e.message}")
                        }
                    }
                }
            }

            ACTION_ACCEPT_VIDEO_CALL -> {
                AppServiceManager.stopCallRinging(context)
                OpenChatNotificationManager.cancelIncomingVideoCallNotification(context)
                context.sendBroadcast(Intent(ACTION_DISMISS_INCOMING_VIDEO_UI).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_CALL_ID, callId)
                })

                // Launch ActiveVideoCallActivity directly
                val launchIntent = ActiveVideoCallActivity.createIntent(
                    context = context,
                    callId = callId,
                    peerId = callerId,
                    peerName = callerName,
                    isIncoming = true
                )
                context.startActivity(launchIntent)
            }

            ACTION_END_VIDEO_CALL -> {
                Log.i(tag, "Ending active video call via VideoCallManager")
                VideoCallManager.endCall("Ended from notification")
                VideoCallService.stopService(context)
            }
        }
    }
}
