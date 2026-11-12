package com.msi.openchat.service.call

import android.content.Context
import android.util.Log
import com.msi.openchat.feature.calls.CallViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

/**
 * Single source of truth for active call state and controls.
 * Synchronizes CallViewModel, OpenChatCallService (ongoing call notification),
 * and CallActionReceiver (notification Answer/Decline/End/Speaker actions).
 */
object CallSessionCoordinator {
    private const val TAG = "CallCoordinator"

    private var activeCallViewModelRef: WeakReference<CallViewModel>? = null

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

    fun registerActiveCall(
        viewModel: CallViewModel,
        callId: String,
        contactId: String,
        contactName: String
    ) {
        Log.i(TAG, "Registering active call session: $callId with $contactName")
        CallSessionGuard.registerAudioCall(callId)
        activeCallViewModelRef = WeakReference(viewModel)
        _currentCallId.value = callId
        _contactId.value = contactId
        _contactName.value = contactName
        _durationSeconds.value = 0
        _isSpeakerOn.value = false
        _isMuted.value = false
        _callState.value = WebRtcCallState.CONNECTING
    }

    fun updateDuration(seconds: Int) {
        _durationSeconds.value = seconds
    }

    fun updateSpeakerState(speakerOn: Boolean) {
        _isSpeakerOn.value = speakerOn
    }

    fun updateMuteState(muted: Boolean) {
        _isMuted.value = muted
    }

    fun updateCallState(state: WebRtcCallState) {
        _callState.value = state
    }

    fun toggleSpeaker() {
        val vm = activeCallViewModelRef?.get()
        if (vm != null) {
            vm.toggleSpeaker()
        } else {
            _isSpeakerOn.value = !_isSpeakerOn.value
        }
    }

    fun toggleMute() {
        val vm = activeCallViewModelRef?.get()
        if (vm != null) {
            vm.toggleMute()
        } else {
            _isMuted.value = !_isMuted.value
        }
    }

    fun endCall() {
        Log.i(TAG, "endCall requested via CallSessionCoordinator")
        val vm = activeCallViewModelRef?.get()
        if (vm != null) {
            vm.endCall()
        }
        unregisterActiveCall()
    }

    fun isActive(): Boolean = _currentCallId.value != null

    fun unregisterActiveCall(callId: String? = null) {
        if (callId == null || _currentCallId.value == callId) {
            Log.i(TAG, "Unregistering active call session")
            CallSessionGuard.unregisterCall(callId ?: _currentCallId.value)
            activeCallViewModelRef = null
            _currentCallId.value = null
            _callState.value = WebRtcCallState.ENDED
            _durationSeconds.value = 0
        }
    }
}
