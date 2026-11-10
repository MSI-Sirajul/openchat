package com.msi.openchat.feature.videocall

/**
 * Sealed hierarchy representing the state machine of a video call:
 * IDLE -> OUTGOING_CALLING / INCOMING_RINGING -> CONNECTING -> CONNECTED -> ENDED
 */
sealed class VideoCallStatus {
    object Idle : VideoCallStatus()

    data class OutgoingCalling(
        val callId: String,
        val receiverId: String,
        val receiverName: String
    ) : VideoCallStatus()

    data class IncomingRinging(
        val callId: String,
        val callerId: String,
        val callerName: String
    ) : VideoCallStatus()

    data class Connecting(
        val callId: String,
        val peerName: String
    ) : VideoCallStatus()

    data class Connected(
        val callId: String,
        val peerName: String,
        val durationSeconds: Int = 0
    ) : VideoCallStatus()

    data class Reconnecting(
        val callId: String,
        val peerName: String
    ) : VideoCallStatus()

    data class Ended(
        val reason: String
    ) : VideoCallStatus()
}

/**
 * UI State exposed by VideoCallRepository and consumed by VideoCallActivity & VideoCallService
 */
data class VideoCallUiState(
    val status: VideoCallStatus = VideoCallStatus.Idle,
    val callId: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val isMicMuted: Boolean = false,
    val isCameraEnabled: Boolean = true,
    val isFrontCamera: Boolean = true,
    val isSpeakerOn: Boolean = true,
    val durationSeconds: Int = 0,
    val formattedDuration: String = "00:00",
    val hasRemoteVideo: Boolean = false,
    val isLocalPreviewReady: Boolean = false,
    val networkQualityText: String = "Good",
    val isReconnecting: Boolean = false,
    val errorMessage: String? = null
)
