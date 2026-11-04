package com.msi.openchat.feature.calls

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.repository.CallRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.service.call.CallNetworkStats
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.OpenChatCallService
import com.msi.openchat.service.call.WebRtcCallState
import com.msi.openchat.service.call.WebRtcManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap

data class CallUiState(
    val callId: String = "",
    val contactName: String = "",
    val contactId: String = "",
    val isCaller: Boolean = true,
    val isIncomingWaiting: Boolean = false,
    val connectionState: WebRtcCallState = WebRtcCallState.IDLE,
    val callStatus: String = "Connecting...", // Incoming call..., Calling..., Ringing..., Connecting..., Connected, Ended
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCallActive: Boolean = true,
    val networkStats: CallNetworkStats = CallNetworkStats(),
    val errorMessage: String? = null
)

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

    private val _uiState = MutableStateFlow(
        CallUiState(
            callId = initialCallId.orEmpty(),
            contactName = contactName,
            contactId = contactId,
            isCaller = !isIncoming,
            isIncomingWaiting = isIncoming && !isAlreadyAnswered,
            callStatus = if (isIncoming && !isAlreadyAnswered) "Incoming call..." else if (isIncoming) "Connecting..." else "Calling..."
        )
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var webRtcManager: WebRtcManager? = null
    private var durationTimerJob: Job? = null
    private var lastSignalingTimestamp: String? = null
    private var callTimeoutJob: Job? = null

    // Deduplication set for processed signaling messages
    private val processedSignalingIds = ConcurrentHashMap.newKeySet<String>()

    init {
        initWebRtc()

        val callId = initialCallId.orEmpty()
        CallSessionCoordinator.registerActiveCall(this, callId, contactId, contactName)

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (isIncoming && callId.isNotBlank()) {
            startObservingSignaling(callId)
            if (isAlreadyAnswered) {
                // Already answered from notification or incoming activity
                viewModelScope.launch {
                    try {
                        callRepository.updateCallStatus(callId, "connected")
                    } catch (_: Exception) {}
                }
                if (hasMicPermission) {
                    startWebRtcCall(isCaller = false)
                }
            } else {
                // Incoming call waiting for user answer/decline
                viewModelScope.launch {
                    try {
                        callRepository.updateCallStatus(callId, "ringing")
                        callRepository.sendSignaling(callId, contactId, "ringing", "{}")
                    } catch (_: Exception) {}
                }
            }
        } else if (!isIncoming) {
            // Initiating outgoing call if permission granted
            if (hasMicPermission) {
                initiateOutgoingCall()
            }
        }
    }

    fun acceptCall() {
        val callId = _uiState.value.callId
        _uiState.value = _uiState.value.copy(
            isIncomingWaiting = false,
            callStatus = "Connecting..."
        )
        if (callId.isNotBlank()) {
            startObservingSignaling(callId)
            viewModelScope.launch {
                try {
                    callRepository.updateCallStatus(callId, "connected")
                } catch (_: Exception) {}
            }
        }
        startWebRtcCall(isCaller = false)
    }

    fun declineCall() {
        val callId = _uiState.value.callId
        viewModelScope.launch {
            if (callId.isNotBlank()) {
                try {
                    callRepository.updateCallStatus(callId, "declined", endReason = "Declined by user")
                    callRepository.sendSignaling(callId, contactId, "bye", "{\"reason\":\"declined\"}")
                } catch (_: Exception) {}
            }
            endCallInternal("Call Declined")
        }
    }

    fun declineWithMessage(messageText: String = "Can't talk right now. What's up?") {
        val callId = _uiState.value.callId
        viewModelScope.launch {
            try {
                val chatRepo = ServiceLocator.chatRepository
                when (val convRes = chatRepo.getOrCreateConversation(contactId)) {
                    is Resource.Success -> {
                        chatRepo.sendTextMessage(convRes.data.id, messageText)
                    }
                    else -> Unit
                }
            } catch (_: Exception) {}
            declineCall()
        }
    }

    fun onPermissionGranted() {
        val currentCallId = _uiState.value.callId
        if (isIncoming && currentCallId.isNotBlank() && !_uiState.value.isIncomingWaiting) {
            startWebRtcCall(isCaller = false)
        } else if (!isIncoming && currentCallId.isBlank()) {
            initiateOutgoingCall()
        }
    }

    private fun initWebRtc() {
        val manager = WebRtcManager(
            context = context,
            onIceCandidate = { candidate ->
                sendIceCandidate(candidate)
            },
            onCallStateChanged = { state ->
                handleWebRtcStateChange(state)
            }
        )
        webRtcManager = manager

        viewModelScope.launch {
            manager.networkStats.collect { stats ->
                _uiState.value = _uiState.value.copy(networkStats = stats)
            }
        }
    }

    private fun initiateOutgoingCall() {
        viewModelScope.launch {
            when (val res = callRepository.initiateCall(contactId)) {
                is Resource.Success -> {
                    val call = res.data
                    _uiState.value = _uiState.value.copy(callId = call.id, callStatus = "Calling...")
                    CallSessionCoordinator.registerActiveCall(this@CallViewModel, call.id, contactId, contactName)
                    startObservingSignaling(call.id)
                    startWebRtcCall(isCaller = true)
                    startOutgoingCallTimeout()
                }
                is Resource.Error -> {
                    _uiState.value = _uiState.value.copy(
                        callStatus = "Call Failed",
                        errorMessage = res.message,
                        isCallActive = false
                    )
                }
                else -> Unit
            }
        }
    }

    private fun startOutgoingCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = viewModelScope.launch {
            delay(35_000L) // 35-second unanswered timeout
            if (isActive && _uiState.value.isCallActive && _uiState.value.connectionState != WebRtcCallState.CONNECTED) {
                android.util.Log.i("CallViewModel", "Outgoing call timed out with no answer")
                val cid = _uiState.value.callId
                if (cid.isNotBlank()) {
                    callRepository.updateCallStatus(cid, "missed", endReason = "No answer")
                    callRepository.sendSignaling(cid, contactId, "bye", "{\"reason\":\"timeout\"}")
                }
                endCallInternal("No Answer")
            }
        }
    }

    private fun startWebRtcCall(isCaller: Boolean) {
        viewModelScope.launch {
            val iceServers = callRepository.getIceServers()
            webRtcManager?.startCall(
                iceServers = iceServers,
                isCaller = isCaller,
                onOfferCreated = { offerDesc ->
                    sendOffer(offerDesc)
                }
            )
        }
    }

    private fun sendOffer(desc: SessionDescription) {
        val callId = _uiState.value.callId
        if (callId.isBlank()) return
        viewModelScope.launch {
            val payload = JSONObject().apply {
                put("sdp", desc.description)
                put("type", desc.type.canonicalForm())
            }
            callRepository.sendSignaling(callId, contactId, "offer", payload.toString())
        }
    }

    private fun sendAnswer(desc: SessionDescription) {
        val callId = _uiState.value.callId
        if (callId.isBlank()) return
        viewModelScope.launch {
            val payload = JSONObject().apply {
                put("sdp", desc.description)
                put("type", desc.type.canonicalForm())
            }
            callRepository.sendSignaling(callId, contactId, "answer", payload.toString())
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate?) {
        val callId = _uiState.value.callId
        if (candidate == null || callId.isBlank() || candidate.sdp.isNullOrBlank()) return
        viewModelScope.launch {
            try {
                val payload = JSONObject().apply {
                    put("sdp", candidate.sdp)
                    if (candidate.sdpMid != null) {
                        put("sdpMid", candidate.sdpMid)
                    }
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                callRepository.sendSignaling(callId, contactId, "ice_candidate", payload.toString())
            } catch (t: Throwable) {
                android.util.Log.e("CallViewModel", "Failed to send ICE candidate: ${t.message}", t)
            }
        }
    }

    private var signalingJob: Job? = null
    private var callSessionJob: Job? = null

    private fun startObservingSignaling(targetCallId: String) {
        if (targetCallId.isBlank()) return

        // 1. Observe real-time WebRTC signaling messages
        signalingJob?.cancel()
        signalingJob = viewModelScope.launch {
            callRepository.observeSignalingMessages(targetCallId).collect { msg ->
                if (!_uiState.value.isCallActive) return@collect
                if (msg.senderId == contactId) {
                    val id = msg.id
                    if (id == null || processedSignalingIds.add(id)) {
                        handleIncomingSignalingMessage(msg.type, msg.payload)
                    }
                }
            }
        }

        // 2. Observe real-time call status changes
        callSessionJob?.cancel()
        callSessionJob = viewModelScope.launch {
            callRepository.observeCallSession(targetCallId).collect { session ->
                if (!_uiState.value.isCallActive) return@collect
                when (session.status) {
                    "ringing" -> {
                        if (_uiState.value.isCaller && _uiState.value.callStatus == "Calling...") {
                            _uiState.value = _uiState.value.copy(callStatus = "Ringing...")
                        }
                    }
                    "connecting" -> {
                        if (_uiState.value.callStatus in listOf("Calling...", "Ringing...")) {
                            _uiState.value = _uiState.value.copy(callStatus = "Connecting...")
                        }
                    }
                    "connected" -> {
                        callTimeoutJob?.cancel()
                        if (_uiState.value.callStatus != "Connected") {
                            _uiState.value = _uiState.value.copy(callStatus = "Connected")
                            startDurationTimer()
                        }
                    }
                    "declined" -> endCallInternal("Call Declined")
                    "missed" -> endCallInternal("No Answer")
                    "ended" -> endCallInternal("Call Ended")
                    "failed" -> endCallInternal("Call Failed")
                }
            }
        }
    }

    private fun handleIncomingSignalingMessage(type: String, payloadStr: String) {
        try {
            android.util.Log.d("CallViewModel", "Handling incoming signaling: type=$type, length=${payloadStr.length}")
            val payload = JSONObject(payloadStr)
            when (type) {
                "ringing" -> {
                    if (_uiState.value.isCaller && _uiState.value.callStatus == "Calling...") {
                        android.util.Log.i("CallViewModel", "Recipient device confirmed ringing state")
                        _uiState.value = _uiState.value.copy(callStatus = "Ringing...")
                    }
                }
                "offer" -> {
                    callTimeoutJob?.cancel()
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotBlank()) {
                        val desc = SessionDescription(SessionDescription.Type.OFFER, sdp)
                        webRtcManager?.handleRemoteOffer(desc) { answerDesc ->
                            sendAnswer(answerDesc)
                        }
                    }
                }
                "answer" -> {
                    callTimeoutJob?.cancel()
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotBlank()) {
                        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        webRtcManager?.handleRemoteAnswer(desc)
                    }
                }
                "ice_candidate" -> {
                    val sdp = payload.optString("sdp")
                    val sdpMid = payload.optString("sdpMid").takeIf { it.isNotBlank() }
                    val sdpMLineIndex = payload.optInt("sdpMLineIndex", -1)
                    if (sdp.isNotBlank()) {
                        val candidate = IceCandidate(sdpMid, if (sdpMLineIndex >= 0) sdpMLineIndex else 0, sdp)
                        webRtcManager?.addRemoteIceCandidate(candidate)
                    }
                }
                "bye" -> {
                    val reason = payload.optString("reason", "Call ended")
                    val displayStatus = when (reason) {
                        "declined" -> "Call Declined"
                        "busy" -> "User Busy"
                        "timeout" -> "No Answer"
                        else -> "Call Ended"
                    }
                    endCallInternal(displayStatus)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("CallViewModel", "Error parsing signaling message ($type): ${t.message}", t)
        }
    }

    private fun handleWebRtcStateChange(state: WebRtcCallState) {
        _uiState.value = _uiState.value.copy(
            connectionState = state,
            callStatus = when (state) {
                WebRtcCallState.CONNECTING -> {
                    if (_uiState.value.callStatus in listOf("Calling...", "Ringing...")) {
                        _uiState.value.callStatus
                    } else {
                        "Connecting..."
                    }
                }
                WebRtcCallState.CONNECTED -> "Connected"
                WebRtcCallState.RECONNECTING -> "Reconnecting..."
                WebRtcCallState.DISCONNECTED -> "Reconnecting..."
                WebRtcCallState.FAILED -> "Connection Failed"
                WebRtcCallState.ENDED -> "Call Ended"
                WebRtcCallState.IDLE -> _uiState.value.callStatus
            }
        )

        when (state) {
            WebRtcCallState.CONNECTED -> {
                startDurationTimer()
                OpenChatCallService.startCallService(context, _uiState.value.callId, contactName)
                val callId = _uiState.value.callId
                if (callId.isNotBlank()) {
                    viewModelScope.launch {
                        callRepository.updateCallStatus(callId, "connected")
                    }
                }
            }
            WebRtcCallState.FAILED, WebRtcCallState.ENDED -> {
                endCallInternal(if (state == WebRtcCallState.FAILED) "Call Failed" else "Call ended")
            }
            else -> Unit
        }
    }

    private fun startDurationTimer() {
        callTimeoutJob?.cancel()
        durationTimerJob?.cancel()
        durationTimerJob = viewModelScope.launch {
            while (isActive && _uiState.value.isCallActive) {
                delay(1000)
                val nextDuration = _uiState.value.durationSeconds + 1
                _uiState.value = _uiState.value.copy(durationSeconds = nextDuration)
                CallSessionCoordinator.updateDuration(nextDuration)
            }
        }
    }

    fun toggleMute() {
        val newMuted = webRtcManager?.toggleMute() ?: false
        _uiState.value = _uiState.value.copy(isMuted = newMuted)
    }

    fun toggleSpeaker() {
        val newSpeaker = webRtcManager?.toggleSpeaker() ?: false
        _uiState.value = _uiState.value.copy(isSpeakerOn = newSpeaker)
    }

    fun endCall() {
        val callId = _uiState.value.callId
        val duration = _uiState.value.durationSeconds
        viewModelScope.launch {
            if (callId.isNotBlank()) {
                callRepository.sendSignaling(callId, contactId, "bye", "{\"reason\":\"ended\"}")
                callRepository.updateCallStatus(callId, "ended", durationSeconds = duration, endReason = "Normal hangup")
            }
            endCallInternal("Call ended")
        }
    }

    private fun endCallInternal(reason: String) {
        callTimeoutJob?.cancel()
        durationTimerJob?.cancel()
        signalingJob?.cancel()
        callSessionJob?.cancel()
        webRtcManager?.endCall()
        CallSessionCoordinator.unregisterActiveCall(_uiState.value.callId)
        OpenChatCallService.stopCallService(context)
        _uiState.value = _uiState.value.copy(
            callStatus = reason,
            isCallActive = false
        )
    }

    override fun onCleared() {
        super.onCleared()
        endCallInternal("Disposed")
    }
}
