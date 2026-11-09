package com.msi.openchat.service.call

import android.content.Context
import android.util.Log
import com.msi.openchat.data.model.IceServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors

enum class WebRtcCallState {
    IDLE,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    DISCONNECTED,
    FAILED,
    ENDED
}

enum class NetworkQuality {
    EXCELLENT,
    GOOD,
    FAIR,
    POOR,
    UNKNOWN
}

data class CallNetworkStats(
    val rttMs: Int = 0,
    val packetsLost: Int = 0,
    val jitterMs: Int = 0,
    val packetsReceived: Int = 0,
    val quality: NetworkQuality = NetworkQuality.UNKNOWN
)

/**
 * WebRtcManager handles audio-only WebRTC communication.
 * All WebRTC PeerConnection operations are strictly serialized onto a dedicated
 * single-threaded worker executor (rtcExecutor) to prevent native crashes (SIGABRT)
 * on the signaling_thread caused by re-entrancy, race conditions, or unhandled JNI exceptions.
 */
class WebRtcManager(
    private val context: Context,
    private val onIceCandidate: (candidate: IceCandidate) -> Unit,
    private val onCallStateChanged: (state: WebRtcCallState) -> Unit
) {
    private val tag = "OpenChatWebRtc"
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    // Dedicated single-threaded executor for all WebRTC operations
    private val rtcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenChat-WebRtc-Worker").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    companion object {
        @Volatile
        private var isFactoryInitialized = false
        private var sharedAdm: JavaAudioDeviceModule? = null
        private var sharedFactory: PeerConnectionFactory? = null

        @Synchronized
        fun initializeWebRtc(context: Context) {
            if (!isFactoryInitialized) {
                try {
                    val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                    PeerConnectionFactory.initialize(options)
                    isFactoryInitialized = true
                    Log.i("OpenChatWebRtc", "PeerConnectionFactory globally initialized")
                } catch (t: Throwable) {
                    Log.e("OpenChatWebRtc", "Error initializing PeerConnectionFactory: ${t.message}", t)
                }
            }
        }

        @Synchronized
        fun getSharedFactory(context: Context): PeerConnectionFactory? {
            initializeWebRtc(context)
            if (sharedFactory == null) {
                try {
                    val adm = try {
                        JavaAudioDeviceModule.builder(context.applicationContext)
                            .setUseHardwareAcousticEchoCanceler(false)
                            .setUseHardwareNoiseSuppressor(false)
                            .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                            .setUseStereoInput(false)
                            .setUseStereoOutput(false)
                            .createAudioDeviceModule()
                    } catch (t: Throwable) {
                        Log.w("OpenChatWebRtc", "Custom ADM init failed: ${t.message}, fallback to default", t)
                        JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
                    }
                    sharedAdm = adm

                    sharedFactory = PeerConnectionFactory.builder()
                        .setAudioDeviceModule(adm)
                        .createPeerConnectionFactory()
                    Log.i("OpenChatWebRtc", "Shared PeerConnectionFactory created successfully")
                } catch (t: Throwable) {
                    Log.e("OpenChatWebRtc", "Error creating shared PeerConnectionFactory: ${t.message}", t)
                }
            }
            return sharedFactory
        }
    }

    private var currentObserver: OpenChatPeerConnectionObserver? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private val audioSwitchManager = AudioSwitchManager(context)

    // Pending offer and candidate queuing to eliminate timing races
    private var pendingRemoteOffer: SessionDescription? = null
    private var pendingOnAnswerCreated: ((SessionDescription) -> Unit)? = null
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    @Volatile
    private var hasRemoteDescriptionSet = false

    private val _callState = MutableStateFlow(WebRtcCallState.IDLE)
    val callState: StateFlow<WebRtcCallState> = _callState.asStateFlow()

    private val statsCollector = WebRtcStatsCollector(scope)
    val networkStats: StateFlow<CallNetworkStats> = statsCollector.networkStats

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val isDisposing = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isDisposed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val teardownLock = Any()

    init {
        getSharedFactory(context)
    }

    private fun executeOnRtcThread(action: () -> Unit) {
        if (!rtcExecutor.isShutdown && !rtcExecutor.isTerminated) {
            rtcExecutor.execute {
                try {
                    action()
                } catch (t: Throwable) {
                    Log.e(tag, "Uncaught error on WebRtcWorker thread: ${t.message}", t)
                }
            }
        } else {
            Log.w(tag, "executeOnRtcThread skipped: executor is shut down")
        }
    }

    fun startCall(
        iceServers: List<IceServerConfig>,
        isCaller: Boolean,
        onOfferCreated: ((SessionDescription) -> Unit)? = null
    ) {
        executeOnRtcThread {
            try {
                isDisposing.set(false)
                isDisposed.set(false)
                audioSwitchManager.startCallAudio()
                updateCallState(WebRtcCallState.CONNECTING)

                hasRemoteDescriptionSet = false

                val factory = getSharedFactory(context)
                if (factory == null) {
                    Log.e(tag, "PeerConnectionFactory is null during startCall")
                    updateCallState(WebRtcCallState.FAILED)
                    return@executeOnRtcThread
                }

                val distinctUrls = mutableSetOf<String>()
                val rtcIceServers = mutableListOf<PeerConnection.IceServer>()
                for (server in iceServers) {
                    for (url in server.urls) {
                        if (distinctUrls.add(url)) {
                            try {
                                val builder = PeerConnection.IceServer.builder(url)
                                if (!server.username.isNullOrBlank()) {
                                    builder.setUsername(server.username)
                                }
                                if (!server.credential.isNullOrBlank()) {
                                    builder.setPassword(server.credential)
                                }
                                rtcIceServers.add(builder.createIceServer())
                            } catch (_: Throwable) {}
                        }
                    }
                }
                if (distinctUrls.add("stun:stun.l.google.com:19302")) {
                    rtcIceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
                }

                val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                }

                val observer = OpenChatPeerConnectionObserver(
                    onIceCandidateGenerated = { candidate ->
                        onIceCandidate(candidate)
                    },
                    onIceConnectionStateChange = { state ->
                        Log.i(tag, "PeerConnection onIceConnectionChange: $state")
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> {
                                updateCallState(WebRtcCallState.CONNECTED)
                                statsCollector.start({ peerConnection }, ::executeOnRtcThread, intervalMs = 1500L)
                            }
                            PeerConnection.IceConnectionState.DISCONNECTED -> {
                                updateCallState(WebRtcCallState.RECONNECTING)
                            }
                            PeerConnection.IceConnectionState.FAILED -> {
                                updateCallState(WebRtcCallState.FAILED)
                                statsCollector.stop()
                            }
                            PeerConnection.IceConnectionState.CLOSED -> {
                                updateCallState(WebRtcCallState.ENDED)
                                statsCollector.stop()
                            }
                            else -> Unit
                        }
                    },
                    onSignalingChangeCallback = { state ->
                        Log.d(tag, "SignalingState changed: $state")
                    },
                    onConnectionChangeCallback = { newState ->
                        Log.i(tag, "PeerConnection onConnectionChange: $newState")
                        when (newState) {
                            PeerConnection.PeerConnectionState.CONNECTED -> {
                                updateCallState(WebRtcCallState.CONNECTED)
                                statsCollector.start({ peerConnection }, ::executeOnRtcThread, intervalMs = 1500L)
                            }
                            PeerConnection.PeerConnectionState.FAILED -> {
                                updateCallState(WebRtcCallState.FAILED)
                                statsCollector.stop()
                            }
                            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                                updateCallState(WebRtcCallState.RECONNECTING)
                            }
                            PeerConnection.PeerConnectionState.CLOSED -> {
                                updateCallState(WebRtcCallState.ENDED)
                                statsCollector.stop()
                            }
                            else -> Unit
                        }
                    },
                    onTrackAddedCallback = { track ->
                        Log.i(tag, "Remote audio track received via onTrackAdded: ${track.id()}")
                        remoteAudioTrack = track
                        try {
                            track.setEnabled(true)
                        } catch (_: Throwable) {}
                    }
                )

                currentObserver = observer
                peerConnection = factory.createPeerConnection(rtcConfig, observer)

                // Create and attach local audio track with safe optional constraints
                try {
                    val audioConstraints = MediaConstraints().apply {
                        optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                        optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                    }
                    val src = factory.createAudioSource(audioConstraints)
                    localAudioSource = src
                    val track = factory.createAudioTrack("openchat_local_audio", src)
                    track.setEnabled(true)
                    localAudioTrack = track

                    val streamId = "openchat_stream"
                    peerConnection?.addTrack(track, listOf(streamId))
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to create/add local audio track: ${t.message}", t)
                }

                if (isCaller) {
                    createOfferInternal(onOfferCreated)
                } else {
                    // Callee mode: check if an offer arrived before startCall completed
                    val cachedOffer = pendingRemoteOffer
                    val cachedCallback = pendingOnAnswerCreated
                    if (cachedOffer != null && cachedCallback != null) {
                        Log.i(tag, "Applying queued remote offer now that PeerConnection is created")
                        pendingRemoteOffer = null
                        pendingOnAnswerCreated = null
                        handleRemoteOfferInternal(cachedOffer, cachedCallback)
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Error starting WebRTC call: ${t.message}", t)
                updateCallState(WebRtcCallState.FAILED)
            }
        }
    }

    private fun updateCallState(state: WebRtcCallState) {
        _callState.value = state
        CallSessionCoordinator.updateCallState(state)
        scope.launch {
            try {
                onCallStateChanged(state)
            } catch (t: Throwable) {
                Log.e(tag, "Error notifying call state change: ${t.message}")
            }
        }
    }

    private fun createOfferInternal(onOfferCreated: ((SessionDescription) -> Unit)?) {
        val pc = peerConnection
        if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
            Log.w(tag, "createOffer aborted: PeerConnection is null or closed")
            return
        }

        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                try {
                    if (desc == null) {
                        Log.e(tag, "createOffer returned null SessionDescription")
                        return
                    }
                    Log.d(tag, "Offer created successfully. Dispatching setLocalDescription to worker thread...")

                    executeOnRtcThread {
                        val activePc = peerConnection
                        if (activePc == null || activePc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                            Log.w(tag, "Cannot setLocalDescription: PeerConnection is null or closed")
                            return@executeOnRtcThread
                        }

                        try {
                            activePc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    try {
                                        Log.d(tag, "Local description set successfully (Offer)")
                                        scope.launch {
                                            try {
                                                onOfferCreated?.invoke(desc)
                                            } catch (t: Throwable) {
                                                Log.e(tag, "Error in onOfferCreated callback: ${t.message}", t)
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        Log.e(tag, "Exception in onSetSuccess (Offer): ${t.message}", t)
                                    }
                                }
                                override fun onCreateFailure(err: String?) {
                                    try { Log.e(tag, "SetLocalDescription onCreateFailure: $err") } catch (_: Throwable) {}
                                }
                                override fun onSetFailure(err: String?) {
                                    try { Log.e(tag, "SetLocalDescription onSetFailure: $err") } catch (_: Throwable) {}
                                }
                            }, desc)
                        } catch (t: Throwable) {
                            Log.e(tag, "Exception during setLocalDescription: ${t.message}", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Exception in onCreateSuccess (Offer): ${t.message}", t)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                try { Log.e(tag, "CreateOffer onCreateFailure: $err") } catch (_: Throwable) {}
            }
            override fun onSetFailure(err: String?) {
                try { Log.e(tag, "CreateOffer onSetFailure: $err") } catch (_: Throwable) {}
            }
        }, sdpConstraints)
    }

    fun handleRemoteOffer(offerDesc: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        executeOnRtcThread {
            try {
                if (isDisposing.get() || isDisposed.get()) {
                    Log.w(tag, "handleRemoteOffer ignored: call is disposing or disposed")
                    return@executeOnRtcThread
                }
                val pc = peerConnection
                if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                    Log.i(tag, "PeerConnection not yet ready for remote offer. Queuing offer...")
                    pendingRemoteOffer = offerDesc
                    pendingOnAnswerCreated = onAnswerCreated
                    return@executeOnRtcThread
                }

                val currentState = pc.signalingState()
                if (hasRemoteDescriptionSet || currentState != PeerConnection.SignalingState.STABLE) {
                    Log.w(tag, "handleRemoteOffer ignored: already set or state is $currentState (expected STABLE)")
                    return@executeOnRtcThread
                }

                handleRemoteOfferInternal(offerDesc, onAnswerCreated)
            } catch (t: Throwable) {
                Log.e(tag, "Exception in handleRemoteOffer: ${t.message}", t)
            }
        }
    }

    private fun handleRemoteOfferInternal(offerDesc: SessionDescription, onAnswerCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        try {
            val currentState = pc.signalingState()
            Log.d(tag, "Setting remote offer in state: $currentState")
            if (currentState != PeerConnection.SignalingState.STABLE) {
                Log.w(tag, "handleRemoteOfferInternal warning: state is $currentState")
                return
            }

            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    try {
                        Log.d(tag, "Remote offer set successfully. Dispatching answer creation to worker thread...")
                        executeOnRtcThread {
                            try {
                                hasRemoteDescriptionSet = true
                                drainPendingRemoteCandidatesInternal()
                                createAnswerInternal(onAnswerCreated)
                            } catch (t: Throwable) {
                                Log.e(tag, "Error post-remote-offer: ${t.message}", t)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(tag, "Exception in onSetSuccess (RemoteOffer): ${t.message}", t)
                    }
                }
                override fun onCreateFailure(err: String?) {
                    try { Log.e(tag, "SetRemoteDescription (offer) onCreateFailure: $err") } catch (_: Throwable) {}
                }
                override fun onSetFailure(err: String?) {
                    try { Log.e(tag, "SetRemoteDescription (offer) onSetFailure: $err") } catch (_: Throwable) {}
                }
            }, offerDesc)
        } catch (t: Throwable) {
            Log.e(tag, "Exception in handleRemoteOfferInternal: ${t.message}", t)
        }
    }

    private fun createAnswerInternal(onAnswerCreated: (SessionDescription) -> Unit) {
        val pc = peerConnection
        if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
            Log.w(tag, "createAnswer ignored: PeerConnection is null or closed")
            return
        }

        val currentState = pc.signalingState()
        if (currentState != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
            Log.w(tag, "createAnswer warning: expected HAVE_REMOTE_OFFER, found $currentState")
            return
        }

        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(answerDesc: SessionDescription?) {
                try {
                    if (answerDesc == null) {
                        Log.e(tag, "createAnswer returned null SessionDescription")
                        return
                    }
                    Log.d(tag, "Answer created successfully. Dispatching setLocalDescription to worker thread...")

                    executeOnRtcThread {
                        val activePc = peerConnection
                        if (activePc == null || activePc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                            return@executeOnRtcThread
                        }

                        try {
                            activePc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() {
                                    try {
                                        Log.d(tag, "Local description set successfully (Answer)")
                                        scope.launch {
                                            try {
                                                onAnswerCreated(answerDesc)
                                            } catch (t: Throwable) {
                                                Log.e(tag, "Error in onAnswerCreated callback: ${t.message}", t)
                                            }
                                        }
                                    } catch (t: Throwable) {
                                        Log.e(tag, "Exception in onSetSuccess (Answer): ${t.message}", t)
                                    }
                                }
                                override fun onCreateFailure(err: String?) {
                                    try { Log.e(tag, "SetLocalDescription (Answer) onCreateFailure: $err") } catch (_: Throwable) {}
                                }
                                override fun onSetFailure(err: String?) {
                                    try { Log.e(tag, "SetLocalDescription (Answer) onSetFailure: $err") } catch (_: Throwable) {}
                                }
                            }, answerDesc)
                        } catch (t: Throwable) {
                            Log.e(tag, "Exception during setLocalDescription for answer: ${t.message}", t)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Exception in onCreateSuccess (Answer): ${t.message}", t)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                try { Log.e(tag, "CreateAnswer onCreateFailure: $err") } catch (_: Throwable) {}
            }
            override fun onSetFailure(err: String?) {
                try { Log.e(tag, "CreateAnswer onSetFailure: $err") } catch (_: Throwable) {}
            }
        }, sdpConstraints)
    }

    fun handleRemoteAnswer(answerDesc: SessionDescription) {
        executeOnRtcThread {
            try {
                if (isDisposing.get() || isDisposed.get()) {
                    Log.w(tag, "handleRemoteAnswer ignored: call is disposing or disposed")
                    return@executeOnRtcThread
                }
                val pc = peerConnection
                if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                    Log.w(tag, "handleRemoteAnswer ignored: PeerConnection is null or closed")
                    return@executeOnRtcThread
                }

                val currentState = pc.signalingState()
                Log.d(tag, "Handling remote answer in state: $currentState (hasRemoteDesc=$hasRemoteDescriptionSet)")

                // Guard: Remote answer can ONLY be set in HAVE_LOCAL_OFFER state
                // Duplicate answers arriving from Firebase in STABLE or other states MUST be safely ignored
                if (hasRemoteDescriptionSet || currentState != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                    Log.w(tag, "Ignoring duplicate or unexpected remote answer: state=$currentState, hasRemoteDesc=$hasRemoteDescriptionSet")
                    return@executeOnRtcThread
                }

                pc.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        try {
                            Log.d(tag, "Remote answer set successfully. Dispatching candidate drain to worker thread...")
                            executeOnRtcThread {
                                try {
                                    hasRemoteDescriptionSet = true
                                    drainPendingRemoteCandidatesInternal()
                                } catch (t: Throwable) {
                                    Log.e(tag, "Error post-remote-answer: ${t.message}", t)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e(tag, "Exception in onSetSuccess (RemoteAnswer): ${t.message}", t)
                        }
                    }
                    override fun onCreateFailure(err: String?) {
                        try { Log.e(tag, "SetRemoteDescription (answer) onCreateFailure: $err") } catch (_: Throwable) {}
                    }
                    override fun onSetFailure(err: String?) {
                        try { Log.e(tag, "SetRemoteDescription (answer) onSetFailure: $err") } catch (_: Throwable) {}
                    }
                }, answerDesc)
            } catch (t: Throwable) {
                Log.e(tag, "Exception in handleRemoteAnswer: ${t.message}", t)
            }
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate?) {
        executeOnRtcThread {
            try {
                if (candidate == null || candidate.sdp.isNullOrBlank()) {
                    Log.w(tag, "addRemoteIceCandidate ignored: candidate is null or empty")
                    return@executeOnRtcThread
                }
                if (isDisposing.get() || isDisposed.get()) {
                    Log.d(tag, "addRemoteIceCandidate ignored: disposing/disposed")
                    return@executeOnRtcThread
                }

                val pc = peerConnection
                if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                    Log.d(tag, "addRemoteIceCandidate ignored: pc is null or closed")
                    return@executeOnRtcThread
                }

                // Queue candidates until peerConnection is ready AND remote description is established
                if (!hasRemoteDescriptionSet) {
                    Log.d(tag, "Queuing ICE candidate (remoteDesc not set yet): mid=${candidate.sdpMid}")
                    synchronized(pendingRemoteCandidates) {
                        if (pendingRemoteCandidates.size < 100) {
                            pendingRemoteCandidates.add(candidate)
                        }
                    }
                    return@executeOnRtcThread
                }

                try {
                    pc.addIceCandidate(candidate)
                } catch (t: Throwable) {
                    Log.w(tag, "Error adding remote ICE candidate: ${t.message}")
                }
            } catch (t: Throwable) {
                Log.e(tag, "Error in addRemoteIceCandidate: ${t.message}", t)
            }
        }
    }

    private fun drainPendingRemoteCandidatesInternal() {
        val candidatesToApply: List<IceCandidate>
        synchronized(pendingRemoteCandidates) {
            if (pendingRemoteCandidates.isEmpty()) return
            candidatesToApply = ArrayList(pendingRemoteCandidates)
            pendingRemoteCandidates.clear()
        }

        val pc = peerConnection
        if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED || isDisposing.get() || isDisposed.get()) return

        Log.d(tag, "Draining ${candidatesToApply.size} queued ICE candidates")
        for (candidate in candidatesToApply) {
            try {
                if (candidate != null && !candidate.sdp.isNullOrBlank()) {
                    pc.addIceCandidate(candidate)
                }
            } catch (t: Throwable) {
                Log.w(tag, "Failed to apply queued ICE candidate: ${t.message}")
            }
        }
    }

    fun toggleMute(): Boolean {
        val newMuted = !_isMuted.value
        executeOnRtcThread {
            localAudioTrack?.setEnabled(!newMuted)
            audioSwitchManager.setMicrophoneMute(newMuted)
        }
        _isMuted.value = newMuted
        CallSessionCoordinator.updateMuteState(newMuted)
        return newMuted
    }

    fun toggleSpeaker(): Boolean {
        val newSpeaker = !_isSpeakerOn.value
        executeOnRtcThread {
            audioSwitchManager.setSpeakerphoneOn(newSpeaker)
        }
        _isSpeakerOn.value = newSpeaker
        CallSessionCoordinator.updateSpeakerState(newSpeaker)
        return newSpeaker
    }

    fun endCall() {
        if (isDisposed.get() || isDisposing.getAndSet(true)) {
            Log.d(tag, "endCall skipped: already disposing or disposed")
            return
        }

        currentObserver?.isDetached = true
        statsCollector.stop()
        statsCollector.reset()
        updateCallState(WebRtcCallState.ENDED)

        // Ensure disposal is NEVER called from WebRTC observer callbacks
        // and strictly serialized on the dedicated non-callback worker thread
        executeOnRtcThread {
            synchronized(teardownLock) {
                try {
                    Log.i(tag, "Starting safe WebRTC call-teardown on non-callback worker thread...")
                    currentObserver?.isDetached = true
                    currentObserver = null
                    audioSwitchManager.stopCallAudio()

                    synchronized(pendingRemoteCandidates) {
                        pendingRemoteCandidates.clear()
                    }
                    hasRemoteDescriptionSet = false
                    pendingRemoteOffer = null
                    pendingOnAnswerCreated = null

                    // 1. Disable and dispose local/remote audio tracks
                    try {
                        localAudioTrack?.setEnabled(false)
                        localAudioTrack?.dispose()
                    } catch (t: Throwable) {
                        Log.w(tag, "Safe catch disposing localAudioTrack: ${t.message}")
                    }
                    localAudioTrack = null

                    try {
                        remoteAudioTrack?.setEnabled(false)
                        remoteAudioTrack?.dispose()
                    } catch (t: Throwable) {
                        Log.w(tag, "Safe catch disposing remoteAudioTrack: ${t.message}")
                    }
                    remoteAudioTrack = null

                    try {
                        localAudioSource?.dispose()
                    } catch (t: Throwable) {
                        Log.w(tag, "Safe catch disposing localAudioSource: ${t.message}")
                    }
                    localAudioSource = null

                    // 2. Call peerConnection.close() BEFORE dispose()
                    val pcToDispose = peerConnection
                    peerConnection = null

                    if (pcToDispose != null) {
                        try {
                            Log.d(tag, "Closing PeerConnection prior to disposal...")
                            pcToDispose.close()
                        } catch (t: Throwable) {
                            Log.w(tag, "Safe catch closing PeerConnection: ${t.message}")
                        }

                        // Allow native signaling thread in libjingle_peerconnection_so.so
                        // to cleanly exit state loops and release internal ownership
                        try {
                            Thread.sleep(60)
                        } catch (_: InterruptedException) {}

                        // 3. Dispose PeerConnection safely, exactly once
                        try {
                            Log.d(tag, "Disposing PeerConnection safely...")
                            pcToDispose.dispose()
                        } catch (t: Throwable) {
                            Log.w(tag, "Safe catch disposing PeerConnection: ${t.message}")
                        }
                    }

                    // NOTE: The shared PeerConnectionFactory and JavaAudioDeviceModule are intentionally kept alive
                    // across calls. Repeatedly destroying and recreating the global PeerConnectionFactory causes
                    // native assertion failures (!env->ExceptionCheck() / SIGABRT) inside libjingle_peerconnection_so.so.

                    isDisposed.set(true)
                    Log.i(tag, "WebRTC call resources fully released safely")
                } catch (t: Throwable) {
                    Log.e(tag, "Error during WebRTC teardown: ${t.message}", t)
                } finally {
                    isDisposing.set(false)
                }
            }
        }
    }
}
