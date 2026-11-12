package com.msi.openchat.feature.videocall

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.SurfaceViewRenderer

/**
 * Repository interface exposing video call state to ViewModels and UI Activities.
 */
class VideoCallRepository {

    val callState: StateFlow<VideoCallUiState>
        get() = VideoCallManager.uiState

    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        VideoCallManager.attachLocalRenderer(renderer)
    }

    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        VideoCallManager.attachRemoteRenderer(renderer)
    }

    fun detachRenderers() {
        VideoCallManager.detachRenderers()
    }

    fun startOutgoingCall(context: Context, receiverId: String, receiverName: String) {
        VideoCallManager.startOutgoingCall(context, receiverId, receiverName)
    }

    fun acceptIncomingCall(context: Context, callId: String, callerId: String, callerName: String) {
        VideoCallManager.acceptIncomingCall(context, callId, callerId, callerName)
    }

    fun declineIncomingCall(context: Context, callId: String, callerId: String) {
        VideoCallManager.declineIncomingCall(context, callId, callerId)
    }

    fun toggleMute() {
        VideoCallManager.toggleMute()
    }

    fun toggleCamera() {
        VideoCallManager.toggleCamera()
    }

    fun switchCamera() {
        VideoCallManager.switchCamera()
    }

    fun toggleSpeaker() {
        VideoCallManager.toggleSpeaker()
    }

    fun endCall(reason: String = "Call ended") {
        VideoCallManager.endCall(reason)
    }
}
