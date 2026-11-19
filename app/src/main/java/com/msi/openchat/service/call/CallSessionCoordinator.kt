package com.msi.openchat.service.call

import android.util.Log
import com.msi.openchat.feature.calls.CallUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Single source of truth for active audio call state and controls.
 * Synchronizes OpenChatCallService (WebRTC + foreground notification),
 * thin call UI Activities/Fragments, and CallActionReceiver.
 */
object CallSessionCoordinator {
    private const val TAG = "CallCoordinator"

    private var activeServiceRef: WeakReference<OpenChatCallService>? = null

    private val _currentCallId = MutableStateFlow<String?>(null)
    val currentCallId: StateFlow<String?> = _currentCallId.asStateFlow()

    private val _contactName = MutableStateFlow("")
    val contactName: StateFlow<String> = _contactName.asStateFlow()

    private val _contactId = MutableStateFlow("")
    val contactId: StateFlow<String> = _contactId.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0)
    val durationSeconds: StateFlow<Int> = _durationSeconds.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val _uiState = MutableStateFlow(CallUiState(isCallActive = false))
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    @Volatile
    private var presentedUiCallId: String? = null

    fun registerService(
        service: OpenChatCallService,
        callId: String,
        contactId: String,
        contactName: String
    ) {
        Log.i(TAG, "Registering OpenChatCallService for callId=$callId, peer=$contactName")
        val isReattachingSameActiveCall =
            _currentCallId.value == callId &&
                _uiState.value.isCallActive &&
                _callState.value != WebRtcCallState.ENDED &&
                _callState.value != WebRtcCallState.IDLE
        CallSessionGuard.registerAudioCall(callId)
        activeServiceRef = WeakReference(service)
        _currentCallId.value = callId
        _contactId.value = contactId
        _contactName.value = contactName
        if (!isReattachingSameActiveCall) {
            _callState.value = WebRtcCallState.CONNECTING
        }
    }

    fun updateUiState(state: CallUiState) {
        _uiState.value = state
        if (state.callId.isNotBlank() && state.isCallActive) {
            _currentCallId.value = state.callId
        }
        if (state.contactId.isNotBlank()) {
            _contactId.value = state.contactId
        }
        if (state.contactName.isNotBlank()) {
            _contactName.value = state.contactName
        }
        _durationSeconds.value = state.durationSeconds
        _isSpeakerOn.value = state.isSpeakerOn
        _isMuted.value = state.isMuted
        _callState.value = state.connectionState
    }

    fun markUiPresented(callId: String) {
        if (callId.isNotBlank()) {
            presentedUiCallId = callId
        }
    }

    fun hasPresentedUiFor(callId: String?): Boolean {
        return !callId.isNullOrBlank() && presentedUiCallId == callId
    }

    fun updateDuration(seconds: Int) {
        _durationSeconds.value = seconds
        if (_uiState.value.durationSeconds != seconds) {
            _uiState.value = _uiState.value.copy(durationSeconds = seconds)
        }
    }

    fun updateSpeakerState(speakerOn: Boolean) {
        _isSpeakerOn.value = speakerOn
        if (_uiState.value.isSpeakerOn != speakerOn) {
            _uiState.value = _uiState.value.copy(isSpeakerOn = speakerOn)
        }
    }

    fun updateMuteState(muted: Boolean) {
        _isMuted.value = muted
        if (_uiState.value.isMuted != muted) {
            _uiState.value = _uiState.value.copy(isMuted = muted)
        }
    }

    fun updateCallState(state: WebRtcCallState) {
        _callState.value = state
    }

    fun toggleSpeaker() {
        val svc = activeServiceRef?.get()
        if (svc != null) {
            svc.toggleSpeaker()
        } else {
            _isSpeakerOn.value = !_isSpeakerOn.value
        }
    }

    fun toggleMute() {
        val svc = activeServiceRef?.get()
        if (svc != null) {
            svc.toggleMute()
        } else {
            _isMuted.value = !_isMuted.value
        }
    }

    fun endCall() {
        Log.i(TAG, "endCall requested via CallSessionCoordinator")
        val svc = activeServiceRef?.get()
        if (svc != null) {
            svc.endCall()
        } else {
            unregisterActiveCall()
        }
    }

    fun isActive(): Boolean =
        _currentCallId.value != null &&
            _callState.value != WebRtcCallState.ENDED &&
            _uiState.value.isCallActive

    fun unregisterActiveCall(callId: String? = null) {
        if (callId == null || _currentCallId.value == callId) {
            Log.i(TAG, "Unregistering active audio call session: ${_currentCallId.value}")
            CallSessionGuard.unregisterCall(callId ?: _currentCallId.value)
            activeServiceRef = null
            _currentCallId.value = null
            _callState.value = WebRtcCallState.ENDED
            _durationSeconds.value = 0
            presentedUiCallId = null
            _uiState.value = _uiState.value.copy(
                connectionState = WebRtcCallState.ENDED,
                isCallActive = false
            )
        }
    }
}

