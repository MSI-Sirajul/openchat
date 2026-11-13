package com.msi.openchat.service.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
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
                if (CallSessionGuard.isVideoCall(callId, null)) {
                    Log.w(tag, "Audio receiver ignored video callId=$callId")
                    return
                }
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
                            val currentUid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
                            if (!currentUid.isNullOrBlank()) {
                                ServiceLocator.callRepository.clearIncomingAudioCall(currentUid)
                            }
                            ServiceLocator.callRepository.updateAudioCallStatus(
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
                            Log.i(tag, "Successfully declined audio call $callId from notification action")
                        } catch (e: Exception) {
                            Log.e(tag, "Error declining audio call from receiver: ${e.message}")
                        }
                    }
                }
            }

            ACTION_ACCEPT_CALL -> {
                if (CallSessionGuard.isVideoCall(callId, null)) {
                    Log.w(tag, "Audio receiver ignored video accept callId=$callId")
                    return
                }
                AppServiceManager.stopCallRinging(context)
                OpenChatNotificationManager.cancelIncomingCallNotification(context)
                context.sendBroadcast(Intent(ACTION_DISMISS_INCOMING_UI).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_CALL_ID, callId)
                })

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        ServiceLocator.initialize(context.applicationContext)
                        val currentUid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
                        if (!currentUid.isNullOrBlank()) {
                            ServiceLocator.callRepository.clearIncomingAudioCall(currentUid)
                        }
                    } catch (_: Exception) {}
                }

                // Start foreground call service only if RECORD_AUDIO permission is already granted;
                // otherwise ActiveAudioCallActivity requests permission first to prevent Android 14+ FGS SecurityException.
                val hasMicPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMicPermission) {
                    OpenChatCallService.startCallService(context, callId, callerName)
                }

                // Launch ActiveAudioCallActivity directly
                val launchIntent = com.msi.openchat.feature.calls.ActiveAudioCallActivity.createIntent(
                    context = context,
                    callId = callId,
                    contactId = callerId,
                    contactName = callerName,
                    isIncoming = true
                )
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
