package com.msi.openchat.service.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.MainActivity
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for handling call notification actions:
 * - Answer incoming call
 * - Decline incoming call
 * - End active ongoing call
 * - Toggle speakerphone during active call
 */
class CallActionReceiver : BroadcastReceiver() {
    private val tag = "OpenChat_CallAction"

    companion object {
        const val ACTION_ACCEPT_CALL = "com.msi.openchat.action.ACCEPT_CALL"
        const val ACTION_DECLINE_CALL = "com.msi.openchat.action.DECLINE_CALL"
        const val ACTION_END_CALL = "com.msi.openchat.action.END_CALL"
        const val ACTION_TOGGLE_SPEAKER = "com.msi.openchat.action.TOGGLE_SPEAKER"
        const val ACTION_DISMISS_INCOMING_UI = "com.msi.openchat.action.DISMISS_INCOMING_UI"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALLER_ID = "extra_caller_id"
        const val EXTRA_CALLER_NAME = "extra_caller_name"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        val callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "Caller"

        Log.i(tag, "Received call action: $action for callId=$callId")

        when (action) {
            ACTION_DECLINE_CALL -> {
                AppServiceManager.stopCallRinging(context)
                OpenChatNotificationManager.cancelIncomingCallNotification(context)
                context.sendBroadcast(Intent(ACTION_DISMISS_INCOMING_UI).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_CALL_ID, callId)
                })

                if (callId.isNotBlank()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            ServiceLocator.initialize(context.applicationContext)
                            ServiceLocator.callRepository.updateCallStatus(
                                callId = callId,
                                status = "declined",
                                endReason = "Declined from notification"
                            )
                            if (callerId.isNotBlank()) {
                                ServiceLocator.callRepository.sendSignaling(
                                    callId = callId,
                                    receiverId = callerId,
                                    type = "bye",
                                    payload = "{\"reason\":\"declined\"}"
                                )
                            }
                            Log.i(tag, "Successfully declined call $callId from notification action")
                        } catch (e: Exception) {
                            Log.e(tag, "Error declining call from receiver: ${e.message}")
                        }
                    }
                }
            }

            ACTION_ACCEPT_CALL -> {
                AppServiceManager.stopCallRinging(context)
                OpenChatNotificationManager.cancelIncomingCallNotification(context)
                context.sendBroadcast(Intent(ACTION_DISMISS_INCOMING_UI).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_CALL_ID, callId)
                })

                // Keep channel alive in foreground immediately showing duration and end call action
                OpenChatCallService.startCallService(context, callId, callerName)

                // Launch MainActivity and navigate directly to active in-call screen
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    setAction(OpenChatNotificationManager.ACTION_ACCEPT_CALL)
                    putExtra(OpenChatNotificationManager.EXTRA_CALL_ID, callId)
                    putExtra(OpenChatNotificationManager.EXTRA_CALLER_ID, callerId)
                    putExtra(OpenChatNotificationManager.EXTRA_CALLER_NAME, callerName)
                }
                context.startActivity(launchIntent)
            }

            ACTION_END_CALL -> {
                Log.i(tag, "Ending active call via CallSessionCoordinator")
                CallSessionCoordinator.endCall()
                OpenChatCallService.stopCallService(context)
            }

            ACTION_TOGGLE_SPEAKER -> {
                Log.i(tag, "Toggling speakerphone via CallSessionCoordinator")
                CallSessionCoordinator.toggleSpeaker()
            }
        }
    }
}
