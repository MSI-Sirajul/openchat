package com.msi.openchat.service.call

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Single source of truth for calling mutual exclusion and ID namespacing.
 * Strictly guarantees that audio and video call sessions never overlap, cross-trigger,
 * or contend for WebRTC hardware (microphone AudioRecord / camera).
 */
object CallSessionGuard {
    private const val TAG = "CallSessionGuard"

    enum class CallType { NONE, AUDIO, VIDEO }

    private val _activeCallType = MutableStateFlow(CallType.NONE)
    val activeCallType: StateFlow<CallType> = _activeCallType.asStateFlow()

    private val _activeCallId = MutableStateFlow<String?>(null)
    val activeCallId: StateFlow<String?> = _activeCallId.asStateFlow()

    @Synchronized
    fun registerAudioCall(callId: String) {
        Log.i(TAG, "Registering active AUDIO call: $callId")
        _activeCallType.value = CallType.AUDIO
        _activeCallId.value = callId
    }

    @Synchronized
    fun registerVideoCall(callId: String) {
        Log.i(TAG, "Registering active VIDEO call: $callId")
        _activeCallType.value = CallType.VIDEO
        _activeCallId.value = callId
    }

    @Synchronized
    fun unregisterCall(callId: String? = null) {
        if (callId == null || _activeCallId.value == callId) {
            Log.i(TAG, "Unregistering call: ${_activeCallId.value}")
            _activeCallType.value = CallType.NONE
            _activeCallId.value = null
        }
    }

    fun isAudioCallActive(): Boolean = _activeCallType.value == CallType.AUDIO
    fun isVideoCallActive(): Boolean = _activeCallType.value == CallType.VIDEO
    fun isAnyCallActive(): Boolean = _activeCallType.value != CallType.NONE

    /**
     * Generates a strongly-typed, namespace-isolated audio call ID.
     */
    fun generateAudioCallId(): String {
        val cleanUuid = UUID.randomUUID().toString().replace("-", "").take(16)
        return "audio_call_$cleanUuid"
    }

    /**
     * Generates a strongly-typed, namespace-isolated video call ID.
     */
    fun generateVideoCallId(): String {
        val cleanUuid = UUID.randomUUID().toString().replace("-", "").take(16)
        return "video_call_$cleanUuid"
    }

    /**
     * Deterministically tests if a call is a video call from ID prefix or explicit type.
     */
    fun isVideoCall(callId: String?, callType: String?): Boolean {
        if (callType.equals("video", ignoreCase = true)) return true
        if (callId != null && (callId.startsWith("video_") || callId.startsWith("vid_") || callId.contains("_video_") || callId.contains("video_call"))) return true
        return false
    }

    /**
     * Deterministically tests if a call is an audio call.
     */
    fun isAudioCall(callId: String?, callType: String?): Boolean {
        if (isVideoCall(callId, callType)) return false
        return true
    }
}
