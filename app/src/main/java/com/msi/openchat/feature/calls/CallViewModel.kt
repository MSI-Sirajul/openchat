package com.msi.openchat.feature.calls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.repository.CallRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.service.call.CallNetworkStats
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.CallSessionGuard
import com.msi.openchat.service.call.OpenChatCallService
import com.msi.openchat.service.call.WebRtcCallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CallUiState(
    val callId: String = "",
    val contactName: String = "",
    val contactId: String = "",
    val isCaller: Boolean = true,
    val isIncomingWaiting: Boolean = false,
    val connectionState: WebRtcCallState = WebRtcCallState.IDLE,
    val callStatus: String = "",
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCallActive: Boolean = true,
    val networkStats: CallNetworkStats = CallNetworkStats(),
    val errorMessage: String? = null
)

/**
 * Thin ViewModel proxy bound to OpenChatCallService / CallSessionCoordinator.
 * WebRTC PeerConnection, signaling, AudioManager focus, and proximity lock are owned
 * strictly by OpenChatCallService so calls survive Activity backgrounding and launch-less notification accept.
 */
class CallViewModel(
    private val context: Context,
    val initialCallId: String?,
    val contactId: String,
    val contactName: String,
    val isIncoming: Boolean,
    val isAlreadyAnswered: Boolean = false,
    private val callRepository: CallRepository = ServiceLocator.callRepository,
    private val userRepository: UserRepository = ServiceLocator.userRepository
) : ViewModel() {

    private var boundService: OpenChatCallService? = null

    private val _uiState = MutableStateFlow(
        CallSessionCoordinator.uiState.value.takeIf {
            it.isCallActive && (initialCallId.isNullOrBlank() || it.callId == initialCallId)
        } ?: CallUiState(
            callId = initialCallId.orEmpty(),
            contactName = contactName,
            contactId = contactId,
            isCaller = !isIncoming,
            isIncomingWaiting = isIncoming && !isAlreadyAnswered,
            callStatus = when {
                isIncoming && !isAlreadyAnswered -> ServiceLocator.getString(R.string.call_incoming_generic)
                isIncoming -> ServiceLocator.getString(R.string.common_connecting)
                else -> ServiceLocator.getString(R.string.call_calling_ellipsis)
            }
        )
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    init {
        val cid = initialCallId.orEmpty()
        if (CallSessionGuard.isVideoCall(cid, null) ||
            VideoCallManager.isCallActive() ||
            CallSessionGuard.isVideoCallActive()
        ) {
            _uiState.value = _uiState.value.copy(
                callStatus = ServiceLocator.getString(R.string.call_blocked),
                errorMessage = ServiceLocator.getString(R.string.call_another_type_active),
                isCallActive = false
            )
        } else {
            observeCoordinatorState()
            if (cid.isNotBlank()) {
                // Idempotent: if service is already running for this callId, attaches with NO reconnect.
                OpenChatCallService.startCallService(
                    context = context,
                    callId = cid,
                    contactName = contactName,
                    contactId = contactId,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isAlreadyAnswered
                )
            } else if (!isIncoming) {
                initiateOutgoingCall()
            }
        }
    }

    fun attachBoundService(service: OpenChatCallService?) {
        boundService = service
        if (service != null) {
            val cid = initialCallId.orEmpty().ifBlank { _uiState.value.callId }
            if (cid.isNotBlank()) {
                service.startOrAttachCallSession(
                    callId = cid,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isAlreadyAnswered
                )
            }
        }
    }

    private fun observeCoordinatorState() {
        viewModelScope.launch {
            CallSessionCoordinator.uiState.collect { svcState ->
                val targetId = initialCallId.orEmpty().ifBlank { _uiState.value.callId }
                if (targetId.isBlank() || svcState.callId == targetId || svcState.callId.isBlank()) {
                    if (svcState.callId.isNotBlank() || !svcState.isCallActive) {
                        _uiState.value = svcState
                    }
                }
            }
        }
    }

    private fun initiateOutgoingCall() {
        if (VideoCallManager.isCallActive() || CallSessionGuard.isVideoCallActive()) {
            _uiState.value = _uiState.value.copy(
                callStatus = ServiceLocator.getString(R.string.call_busy_status),
                errorMessage = ServiceLocator.getString(R.string.call_video_is_active),
                isCallActive = false
            )
            return
        }
        viewModelScope.launch {
            when (val res = callRepository.initiateAudioCall(contactId)) {
                is Resource.Success -> {
                    val call = res.data
                    _uiState.value = _uiState.value.copy(
                        callId = call.id,
                        callStatus = ServiceLocator.getString(R.string.call_calling_ellipsis)
                    )
                    OpenChatCallService.startOutgoingCallService(
                        context = context,
                        callId = call.id,
                        contactId = contactId,
                        contactName = contactName
                    )
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        callStatus = ServiceLocator.getString(R.string.call_failed_title),
                        errorMessage = res.message,
                        isCallActive = false
                    )
                }
                else -> Unit
            }
        }
    }

    fun acceptCall() {
        val svc = boundService
        if (svc != null) {
            svc.acceptWaitingIncomingCall()
        } else {
            val cid = _uiState.value.callId
            if (cid.isNotBlank()) {
                OpenChatCallService.acceptCallService(context, cid, contactId, contactName)
            }
        }
    }

    fun declineCall() {
        val svc = boundService
        if (svc != null) {
            svc.declineCall()
        } else {
            val cid = _uiState.value.callId
            viewModelScope.launch {
                if (cid.isNotBlank()) {
                    try {
                        callRepository.updateCallStatus(
                            cid,
                            "declined",
                            endReason = ServiceLocator.getString(R.string.call_declined_by_user)
                        )
                        callRepository.sendSignaling(cid, contactId, "bye", "{\"reason\":\"declined\"}")
                    } catch (_: Exception) {}
                }
                OpenChatCallService.stopCallService(context)
            }
        }
    }

    fun declineWithMessage(messageText: String = ServiceLocator.getString(R.string.call_quick_reply_default)) {
        viewModelScope.launch {
            try {
                val chatRepo = ServiceLocator.chatRepository
                when (val convRes = chatRepo.getOrCreateConversation(contactId)) {
                    is Resource.Success -> chatRepo.sendTextMessage(convRes.data.id, messageText)
                    else -> Unit
                }
            } catch (_: Exception) {}
            declineCall()
        }
    }

    fun onPermissionGranted() {
        boundService?.onPermissionGranted() ?: run {
            val cid = _uiState.value.callId
            if (cid.isNotBlank()) {
                OpenChatCallService.startCallService(
                    context = context,
                    callId = cid,
                    contactName = contactName,
                    contactId = contactId,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isAlreadyAnswered
                )
            } else if (!isIncoming) {
                initiateOutgoingCall()
            }
        }
    }

    fun toggleMute() {
        boundService?.toggleMute() ?: CallSessionCoordinator.toggleMute()
    }

    fun toggleSpeaker() {
        boundService?.toggleSpeaker() ?: CallSessionCoordinator.toggleSpeaker()
    }

    fun endCall() {
        boundService?.endCall() ?: run {
            CallSessionCoordinator.endCall()
            OpenChatCallService.stopCallService(context)
        }
    }

    override fun onCleared() {
        super.onCleared()
        boundService = null
        // Intentionally DO NOT end the call or stop OpenChatCallService here:
        // WebRTC audio call persists in the foreground service when UI is closed/backgrounded.
    }
}

