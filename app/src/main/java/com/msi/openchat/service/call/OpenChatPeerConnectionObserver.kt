package com.msi.openchat.service.call

import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.CandidatePairChangeEvent
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver

/**
 * Dedicated PeerConnection.Observer with comprehensive logging for every state change
 * and complete Throwable containment across all callbacks to prevent native signaling thread SIGABRT crashes.
 * All parameters are marked nullable to prevent Kotlin's auto-generated Intrinsics.checkNotNullParameter
 * from throwing unhandled NullPointerExceptions across the JNI boundary (e.g. during end-of-candidates).
 */
class OpenChatPeerConnectionObserver(
    private val onIceCandidateGenerated: (IceCandidate) -> Unit,
    private val onIceConnectionStateChange: (PeerConnection.IceConnectionState) -> Unit,
    private val onSignalingChangeCallback: ((PeerConnection.SignalingState) -> Unit)? = null,
    private val onConnectionChangeCallback: ((PeerConnection.PeerConnectionState) -> Unit)? = null,
    private val onTrackAddedCallback: ((AudioTrack) -> Unit)? = null
) : PeerConnection.Observer {

    private val tag = "OpenChatWebRtc_Observer"

    @Volatile
    var isDetached: Boolean = false

    override fun onIceCandidate(candidate: IceCandidate?) {
        try {
            if (isDetached) return
            if (candidate == null) {
                Log.d(tag, "[onIceCandidate] Null candidate received (End of ICE gathering)")
                return
            }
            if (!candidate.sdp.isNullOrBlank()) {
                onIceCandidateGenerated(candidate)
            }
        } catch (t: Throwable) {
            Log.e(tag, "[onIceCandidate] Error in candidate callback: ${t.message}", t)
        }
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
        try {
            if (isDetached) return
            val count = candidates?.size ?: 0
            Log.d(tag, "[onIceCandidatesRemoved] Removed $count candidates")
        } catch (t: Throwable) {
            Log.e(tag, "[onIceCandidatesRemoved] Exception: ${t.message}", t)
        }
    }

    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
        try {
            if (isDetached || state == null) return
            Log.i(tag, "[onSignalingChange] New SignalingState=$state")
            onSignalingChangeCallback?.invoke(state)
        } catch (t: Throwable) {
            Log.e(tag, "[onSignalingChange] Callback error: ${t.message}", t)
        }
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        try {
            if (isDetached || state == null) return
            Log.i(tag, "[onIceConnectionChange] New IceConnectionState=$state")
            onIceConnectionStateChange(state)
        } catch (t: Throwable) {
            Log.e(tag, "[onIceConnectionChange] Callback error: ${t.message}", t)
        }
    }

    override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState?) {
        try {
            if (isDetached) return
            Log.d(tag, "[onStandardizedIceConnectionChange] state=$state")
        } catch (t: Throwable) {
            Log.e(tag, "[onStandardizedIceConnectionChange] Exception: ${t.message}", t)
        }
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        try {
            if (isDetached || newState == null) return
            Log.i(tag, "[onConnectionChange] New PeerConnectionState=$newState")
            onConnectionChangeCallback?.invoke(newState)
        } catch (t: Throwable) {
            Log.e(tag, "[onConnectionChange] Exception: ${t.message}", t)
        }
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        try {
            if (isDetached) return
            Log.d(tag, "[onIceConnectionReceivingChange] receiving=$receiving")
        } catch (t: Throwable) {
            Log.e(tag, "[onIceConnectionReceivingChange] Exception: ${t.message}", t)
        }
    }

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
        try {
            if (isDetached) return
            Log.i(tag, "[onIceGatheringChange] New IceGatheringState=$state")
        } catch (t: Throwable) {
            Log.e(tag, "[onIceGatheringChange] Exception: ${t.message}", t)
        }
    }

    override fun onIceCandidateError(event: IceCandidateErrorEvent?) {
        try {
            if (isDetached) return
            Log.w(tag, "[onIceCandidateError] WebRTC ICE candidate error event received")
        } catch (t: Throwable) {
            Log.e(tag, "[onIceCandidateError] Exception: ${t.message}", t)
        }
    }

    override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
        try {
            if (isDetached) return
            Log.d(tag, "[onSelectedCandidatePairChanged]")
        } catch (t: Throwable) {
            Log.e(tag, "[onSelectedCandidatePairChanged] Exception: ${t.message}", t)
        }
    }

    override fun onAddStream(stream: MediaStream?) {
        try {
            if (isDetached) return
            stream?.audioTracks?.forEach { track ->
                if (track != null) {
                    try {
                        track.setEnabled(true)
                    } catch (_: Throwable) {}
                    onTrackAddedCallback?.invoke(track)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "[onAddStream] Exception: ${t.message}", t)
        }
    }

    override fun onRemoveStream(stream: MediaStream?) {
        try {
            if (isDetached) return
            Log.i(tag, "[onRemoveStream] StreamId=${stream?.id}")
        } catch (t: Throwable) {
            Log.e(tag, "[onRemoveStream] Exception: ${t.message}", t)
        }
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
        try {
            if (isDetached) return
            Log.d(tag, "[onDataChannel] ChannelId=${dataChannel?.id()}, label=${dataChannel?.label()}")
        } catch (t: Throwable) {
            Log.e(tag, "[onDataChannel] Exception: ${t.message}", t)
        }
    }

    override fun onRenegotiationNeeded() {
        try {
            if (isDetached) return
            Log.d(tag, "[onRenegotiationNeeded] Renegotiation requested by WebRTC")
        } catch (t: Throwable) {
            Log.e(tag, "[onRenegotiationNeeded] Exception: ${t.message}", t)
        }
    }

    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
        try {
            if (isDetached) return
            val track = receiver?.track()
            if (track is AudioTrack) {
                try {
                    track.setEnabled(true)
                } catch (_: Throwable) {}
                onTrackAddedCallback?.invoke(track)
            }
        } catch (t: Throwable) {
            Log.e(tag, "[onAddTrack] Exception: ${t.message}", t)
        }
    }

    override fun onRemoveTrack(receiver: RtpReceiver?) {
        try {
            if (isDetached) return
            Log.d(tag, "[onRemoveTrack] Track=${receiver?.track()?.id()}")
        } catch (t: Throwable) {
            Log.e(tag, "[onRemoveTrack] Exception: ${t.message}", t)
        }
    }

    override fun onTrack(transceiver: RtpTransceiver?) {
        try {
            if (isDetached) return
            val track = transceiver?.receiver?.track()
            if (track is AudioTrack) {
                try {
                    track.setEnabled(true)
                } catch (_: Throwable) {}
                onTrackAddedCallback?.invoke(track)
            }
        } catch (t: Throwable) {
            Log.e(tag, "[onTrack] Exception: ${t.message}", t)
        }
    }
}
