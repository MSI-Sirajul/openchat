package com.msi.openchat.feature.videocall

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.CallSignalingMessage
import com.msi.openchat.data.model.IceServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.SoftwareVideoDecoderFactory
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.RendererCommon
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton VideoCallManager
 * Owns the full lifecycle of real-time video calls:
 * - WebRTC PeerConnection with Audio + Video tracks
 * - Local camera capture (Camera2Enumerator) and rendering
 * - Remote video stream rendering
 * - Firebase Realtime Database signaling exchange
 * - Call state machine (Idle -> OutgoingCalling/IncomingRinging -> Connecting -> Connected -> Ended)
 * - Safe ICE candidate queuing & synchronized execution on rtcExecutor to prevent JNI crashes
 */
object VideoCallManager {
    private const val TAG = "OpenChat_VideoCallMgr"

    private val eglConfigAttributes: IntArray by lazy {
        try {
            EglBase.configBuilder().setOpenGlesVersion(2).createConfigAttributes()
        } catch (_: Throwable) {
            EglBase.CONFIG_PLAIN
        }
    }

    val rootEglBase: EglBase by lazy {
        try {
            EglBase.create(null, eglConfigAttributes)
        } catch (_: Throwable) {
            EglBase.create()
        }
    }

    private val managerScope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null
    private var signalingJob: Job? = null
    private var sessionWatchJob: Job? = null

    // Dedicated single-thread worker for all WebRTC JNI operations to guarantee thread safety
    private val rtcExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "OpenChat-VideoRtc-Worker").apply {
            priority = Thread.NORM_PRIORITY + 1
        }
    }

    private var appContext: Context? = null
    private var signaling: VideoCallSignaling? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null

    // WebRTC components
    private var peerConnection: PeerConnection? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    private var localRenderer: SurfaceViewRenderer? = null
    private var remoteRenderer: SurfaceViewRenderer? = null

    // Queued ICE candidates to prevent crashes if candidates arrive before remote SDP is set
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    @Volatile
    private var isRemoteDescriptionSet = false

    // Hardware & Audio management
    private var audioManager: AudioManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // State flow
    private val _uiState = MutableStateFlow(VideoCallUiState())
    val uiState: StateFlow<VideoCallUiState> = _uiState.asStateFlow()

    fun isCallActive(): Boolean {
        val s = _uiState.value.status
        return s !is VideoCallStatus.Idle && s !is VideoCallStatus.Ended
    }

    private val isDisposing = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        signaling = VideoCallSignaling(ServiceLocator.sessionManager)

        initPeerConnectionFactory(context.applicationContext)
    }

    private fun initPeerConnectionFactory(context: Context) {
        if (peerConnectionFactory != null) return
        try {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val isAecSupported = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
            val isNsSupported = JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()

            val adm = try {
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(isAecSupported)
                    .setUseHardwareNoiseSuppressor(isNsSupported)
                    .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .createAudioDeviceModule()
            } catch (t: Throwable) {
                Log.w(TAG, "Custom ADM init failed, using default: ${t.message}")
                JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
            }

            val encoderFactory = try {
                DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, false, true)
            } catch (t: Throwable) {
                Log.w(TAG, "DefaultVideoEncoderFactory fallback to Software: ${t.message}")
                SoftwareVideoEncoderFactory()
            }
            val decoderFactory = try {
                DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
            } catch (t: Throwable) {
                Log.w(TAG, "DefaultVideoDecoderFactory fallback to Software: ${t.message}")
                SoftwareVideoDecoderFactory()
            }

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()

            Log.i(TAG, "PeerConnectionFactory initialized for video calling")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing PeerConnectionFactory: ${e.message}", e)
        }
    }

    private fun runOnRtc(action: () -> Unit) {
        if (!rtcExecutor.isShutdown && !rtcExecutor.isTerminated) {
            rtcExecutor.execute {
                try {
                    action()
                } catch (t: Throwable) {
                    Log.e(TAG, "RTC execution error: ${t.message}", t)
                }
            }
        }
    }

    /**
     * Prepares and attaches local camera preview to the provided SurfaceViewRenderer
     */
    fun attachLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        try {
            renderer.init(rootEglBase.eglBaseContext, null, eglConfigAttributes, org.webrtc.GlRectDrawer())
            renderer.setEnableHardwareScaler(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.setMirror(true)
            renderer.setZOrderMediaOverlay(true)
        } catch (e: Exception) {
            Log.w(TAG, "Local renderer already initialized or failed: ${e.message}")
        }

        setupLocalMediaTracks()
        localVideoTrack?.addSink(renderer)
        _uiState.value = _uiState.value.copy(isLocalPreviewReady = true)
    }

    /**
     * Attaches remote stream to the provided fullscreen SurfaceViewRenderer
     */
    fun attachRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteRenderer = renderer
        try {
            renderer.init(rootEglBase.eglBaseContext, null, eglConfigAttributes, org.webrtc.GlRectDrawer())
            renderer.setEnableHardwareScaler(false)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            renderer.setMirror(false)
        } catch (e: Exception) {
            Log.w(TAG, "Remote renderer already initialized or failed: ${e.message}")
        }

        remoteVideoTrack?.addSink(renderer)
    }

    /**
     * Safely detaches and unbinds SurfaceViewRenderers from media tracks before release
     */
    fun detachRenderers() {
        try {
            localRenderer?.let { localVideoTrack?.removeSink(it) }
        } catch (_: Exception) {}
        try {
            remoteRenderer?.let { remoteVideoTrack?.removeSink(it) }
        } catch (_: Exception) {}
        localRenderer = null
        remoteRenderer = null
    }

    private fun setupLocalMediaTracks() {
        val context = appContext ?: return
        val factory = peerConnectionFactory ?: return

        if (localVideoTrack == null) {
            try {
                val enumerator = Camera2Enumerator(context)
                val deviceNames = enumerator.deviceNames
                var frontCameraName: String? = null
                var backCameraName: String? = null

                for (name in deviceNames) {
                    if (enumerator.isFrontFacing(name) && frontCameraName == null) {
                        frontCameraName = name
                    } else if (enumerator.isBackFacing(name) && backCameraName == null) {
                        backCameraName = name
                    }
                }

                val selectedCamera = frontCameraName ?: backCameraName ?: deviceNames.firstOrNull()
                if (selectedCamera != null) {
                    videoCapturer = enumerator.createCapturer(selectedCamera, null)
                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", rootEglBase.eglBaseContext)
                    videoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
                    videoCapturer!!.initialize(surfaceTextureHelper, context, videoSource!!.capturerObserver)
                    videoCapturer!!.startCapture(1280, 720, 30)

                    localVideoTrack = factory.createVideoTrack("VIDEO_TRACK_LOCAL", videoSource)
                    localVideoTrack?.setEnabled(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera capturer: ${e.message}", e)
            }
        }

        if (localAudioTrack == null) {
            try {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                }
                audioSource = factory.createAudioSource(audioConstraints)
                localAudioTrack = factory.createAudioTrack("AUDIO_TRACK_LOCAL", audioSource)
                localAudioTrack?.setEnabled(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize audio source: ${e.message}", e)
            }
        }
    }

    /**
     * Start an outgoing video call to receiver
     */
    fun startOutgoingCall(context: Context, receiverId: String, receiverName: String) {
        initialize(context)
        acquireWakeLock(context)
        setCommunicationAudioMode(speakerOn = true)
        VideoCallService.startService(context, receiverName)

        _uiState.value = VideoCallUiState(
            status = VideoCallStatus.OutgoingCalling("", receiverId, receiverName),
            peerId = receiverId,
            peerName = receiverName,
            isSpeakerOn = true
        )

        managerScope.launch {
            val res = signaling?.createVideoCall(receiverId, receiverName)
            if (res is Resource.Success && res.data != null) {
                val session = res.data
                val callId = session.id
                com.msi.openchat.service.call.CallSessionGuard.registerVideoCall(callId)
                _uiState.value = _uiState.value.copy(
                    callId = callId,
                    status = VideoCallStatus.OutgoingCalling(callId, receiverId, receiverName)
                )

                // Timeout after 35 seconds if not answered
                startTimeoutTimer(callId, receiverId)

                // Watch call status in RTDB
                observeCallSession(callId, receiverName)

                // Create WebRTC PeerConnection & Offer
                setupPeerConnection(isCaller = true, callId = callId, peerId = receiverId)
            } else {
                endCall("Failed to initiate call")
            }
        }
    }

    /**
     * Receive and answer an incoming call
     */
    fun acceptIncomingCall(context: Context, callId: String, callerId: String, callerName: String) {
        com.msi.openchat.service.call.CallSessionGuard.registerVideoCall(callId)
        initialize(context)
        acquireWakeLock(context)
        setCommunicationAudioMode(speakerOn = true)
        VideoCallService.startService(context, callerName)

        timeoutJob?.cancel()

        _uiState.value = _uiState.value.copy(
            callId = callId,
            peerId = callerId,
            peerName = callerName,
            status = VideoCallStatus.Connecting(callId, callerName),
            isSpeakerOn = true
        )

        managerScope.launch {
            signaling?.updateCallStatus(callId, "connecting")
            observeCallSession(callId, callerName)
            setupPeerConnection(isCaller = false, callId = callId, peerId = callerId)
        }
    }

    /**
     * Decline incoming call
     */
    fun declineIncomingCall(context: Context, callId: String, callerId: String) {
        initialize(context)
        managerScope.launch {
            signaling?.notifyDecline(callId, callerId)
            signaling?.clearIncomingCall(ServiceLocator.sessionManager.getUserId().orEmpty())
            endCall("Call declined")
        }
    }

    private fun setupPeerConnection(isCaller: Boolean, callId: String, peerId: String) {
        runOnRtc {
            val factory = peerConnectionFactory ?: return@runOnRtc

            val defaultStunServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(defaultStunServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                    Log.d(TAG, "SignalingState: $state")
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(TAG, "IceConnectionState: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> {
                            timeoutJob?.cancel()
                            managerScope.launch {
                                signaling?.updateCallStatus(callId, "connected")
                            }
                            _uiState.value = _uiState.value.copy(
                                status = VideoCallStatus.Connected(callId, _uiState.value.peerName),
                                isReconnecting = false
                            )
                            startDurationTimer()
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            _uiState.value = _uiState.value.copy(
                                isReconnecting = true,
                                status = VideoCallStatus.Reconnecting(callId, _uiState.value.peerName)
                            )
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            endCall("Connection failed")
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            endCall("Call closed")
                        }
                        else -> {}
                    }
                }

                override fun onIceConnectionReceivingChange(receiving: Boolean) {}

                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                    Log.d(TAG, "IceGatheringState: $state")
                }

                override fun onIceCandidate(candidate: IceCandidate?) {
                    if (candidate != null) {
                        managerScope.launch {
                            val payload = JSONObject().apply {
                                put("sdpMid", candidate.sdpMid)
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                put("sdp", candidate.sdp)
                            }.toString()
                            signaling?.sendSignaling(callId, peerId, "ice_candidate", payload)
                        }
                    }
                }

                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

                override fun onAddStream(stream: MediaStream?) {
                    Log.d(TAG, "onAddStream with ${stream?.videoTracks?.size} video tracks")
                    if (stream != null && stream.videoTracks.isNotEmpty()) {
                        val videoTrack = stream.videoTracks[0]
                        remoteVideoTrack = videoTrack
                        remoteRenderer?.let { videoTrack.addSink(it) }
                        _uiState.value = _uiState.value.copy(hasRemoteVideo = true)
                    }
                }

                override fun onRemoveStream(stream: MediaStream?) {
                    _uiState.value = _uiState.value.copy(hasRemoteVideo = false)
                }

                override fun onDataChannel(channel: DataChannel?) {}

                override fun onRenegotiationNeeded() {}

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    val track = receiver?.track()
                    Log.d(TAG, "onAddTrack: kind=${track?.kind()}")
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        _uiState.value = _uiState.value.copy(hasRemoteVideo = true)
                    } else if (track is AudioTrack) {
                        remoteAudioTrack = track
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) {
                        remoteVideoTrack = track
                        remoteRenderer?.let { track.addSink(it) }
                        _uiState.value = _uiState.value.copy(hasRemoteVideo = true)
                    }
                }
            })

            // Add local tracks to PeerConnection
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }
            localVideoTrack?.let { peerConnection?.addTrack(it, listOf("ARDAMS")) }

            // Start listening to incoming signaling messages
            listenIncomingSignaling(callId)

            if (isCaller) {
                createOffer(callId, peerId)
            }
        }
    }

    private fun createOffer(callId: String, peerId: String) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        managerScope.launch {
                            val payload = JSONObject().apply {
                                put("type", desc.type.canonicalForm())
                                put("sdp", desc.description)
                            }.toString()
                            signaling?.sendSignaling(callId, peerId, "offer", payload)
                        }
                    }
                    override fun onCreateFailure(err: String?) { Log.e(TAG, "setLocalDescription failed: $err") }
                    override fun onSetFailure(err: String?) { Log.e(TAG, "setLocalDescription failed: $err") }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createOffer failed: $err") }
            override fun onSetFailure(err: String?) {}
        }, sdpConstraints)
    }

    private fun createAnswer(callId: String, peerId: String) {
        val sdpConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        managerScope.launch {
                            val payload = JSONObject().apply {
                                put("type", desc.type.canonicalForm())
                                put("sdp", desc.description)
                            }.toString()
                            signaling?.sendSignaling(callId, peerId, "answer", payload)
                        }
                    }
                    override fun onCreateFailure(err: String?) { Log.e(TAG, "setLocalDescription answer failed: $err") }
                    override fun onSetFailure(err: String?) { Log.e(TAG, "setLocalDescription answer failed: $err") }
                }, desc)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) { Log.e(TAG, "createAnswer failed: $err") }
            override fun onSetFailure(err: String?) {}
        }, sdpConstraints)
    }

    private fun listenIncomingSignaling(callId: String) {
        val uid = ServiceLocator.sessionManager.getUserId().orEmpty()
        signalingJob?.cancel()
        signalingJob = managerScope.launch {
            signaling?.listenSignaling(callId, uid)?.collect { msg ->
                handleSignalingMessage(msg)
            }
        }
    }

    private fun handleSignalingMessage(msg: CallSignalingMessage) {
        runOnRtc {
            try {
                when (msg.type) {
                    "offer" -> {
                        val json = JSONObject(msg.payload)
                        val sdpString = json.getString("sdp")
                        val desc = SessionDescription(SessionDescription.Type.OFFER, sdpString)

                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                isRemoteDescriptionSet = true
                                drainPendingCandidates()
                                createAnswer(msg.callId, msg.senderId)
                            }
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {
                                Log.e(TAG, "setRemoteDescription(offer) error: $err")
                            }
                        }, desc)
                    }
                    "answer" -> {
                        val json = JSONObject(msg.payload)
                        val sdpString = json.getString("sdp")
                        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdpString)

                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                isRemoteDescriptionSet = true
                                drainPendingCandidates()
                            }
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {
                                Log.e(TAG, "setRemoteDescription(answer) error: $err")
                            }
                        }, desc)
                    }
                    "ice_candidate" -> {
                        val json = JSONObject(msg.payload)
                        val candidate = IceCandidate(
                            json.getString("sdpMid"),
                            json.getInt("sdpMLineIndex"),
                            json.getString("sdp")
                        )
                        if (isRemoteDescriptionSet && peerConnection != null) {
                            peerConnection?.addIceCandidate(candidate)
                        } else {
                            pendingRemoteCandidates.add(candidate)
                        }
                    }
                    "bye" -> {
                        val reason = try {
                            JSONObject(msg.payload).optString("reason", "Call ended by peer")
                        } catch (_: Exception) { "Call ended by peer" }
                        endCall(reason)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "handleSignalingMessage error: ${e.message}", e)
            }
        }
    }

    private fun drainPendingCandidates() {
        if (!isRemoteDescriptionSet || peerConnection == null) return
        val iterator = pendingRemoteCandidates.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            peerConnection?.addIceCandidate(candidate)
            iterator.remove()
        }
    }

    private fun observeCallSession(callId: String, peerName: String) {
        sessionWatchJob?.cancel()
        sessionWatchJob = managerScope.launch {
            signaling?.listenCallSession(callId)?.collect { session ->
                if (session == null) return@collect

                when (session.status) {
                    "declined" -> endCall("Declined by $peerName")
                    "busy" -> endCall("$peerName is on another call")
                    "ended" -> endCall(session.endReason ?: "Call ended")
                    "connected" -> {
                        if (_uiState.value.status !is VideoCallStatus.Connected) {
                            _uiState.value = _uiState.value.copy(
                                status = VideoCallStatus.Connected(callId, peerName)
                            )
                            startDurationTimer()
                        }
                    }
                }
            }
        }
    }

    private fun startTimeoutTimer(callId: String, receiverId: String) {
        timeoutJob?.cancel()
        timeoutJob = managerScope.launch {
            delay(35000)
            if (_uiState.value.status is VideoCallStatus.OutgoingCalling) {
                signaling?.updateCallStatus(callId, "ended", "No answer")
                signaling?.sendSignaling(callId, receiverId, "bye", "{\"reason\":\"timeout\"}")
                endCall("No answer")
            }
        }
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = managerScope.launch {
            var seconds = 0
            while (isActive) {
                delay(1000)
                seconds++
                val mins = seconds / 60
                val secs = seconds % 60
                val formatted = String.format("%02d:%02d", mins, secs)
                _uiState.value = _uiState.value.copy(
                    durationSeconds = seconds,
                    formattedDuration = formatted
                )
            }
        }
    }

    /**
     * Mute / Unmute local microphone
     */
    fun toggleMute() {
        val currentMuted = _uiState.value.isMicMuted
        val newMuted = !currentMuted
        localAudioTrack?.setEnabled(!newMuted)
        _uiState.value = _uiState.value.copy(isMicMuted = newMuted)
    }

    /**
     * Enable / Disable local camera capture
     */
    fun toggleCamera() {
        val currentEnabled = _uiState.value.isCameraEnabled
        val newEnabled = !currentEnabled
        localVideoTrack?.setEnabled(newEnabled)
        _uiState.value = _uiState.value.copy(isCameraEnabled = newEnabled)
    }

    /**
     * Flip camera front <-> back
     */
    fun switchCamera() {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                _uiState.value = _uiState.value.copy(isFrontCamera = isFront)
                localRenderer?.setMirror(isFront)
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                Log.w(TAG, "Failed to switch camera: $errorDescription")
            }
        })
    }

    /**
     * Toggle Speakerphone / Earpiece
     */
    fun toggleSpeaker() {
        val current = _uiState.value.isSpeakerOn
        val newSpeaker = !current
        setCommunicationAudioMode(speakerOn = newSpeaker)
        _uiState.value = _uiState.value.copy(isSpeakerOn = newSpeaker)
    }

    private fun setCommunicationAudioMode(speakerOn: Boolean) {
        val am = audioManager ?: return
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {
            Log.w(TAG, "Error configuring audioManager: ${e.message}")
        }
    }

    private fun acquireWakeLock(context: Context) {
        try {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "OpenChat:VideoCallWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire(60 * 60 * 1000L) // 1 hour safety limit
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {}
    }

    /**
     * Terminates the current call session, cleans up WebRTC tracks, releases camera and mic
     */
    fun endCall(reason: String = "Call ended") {
        if (!isDisposing.compareAndSet(false, true)) return

        val state = _uiState.value
        val callId = state.callId
        val peerId = state.peerId
        val duration = state.durationSeconds

        com.msi.openchat.service.call.CallSessionGuard.unregisterCall(callId)

        timerJob?.cancel()
        timeoutJob?.cancel()
        signalingJob?.cancel()
        sessionWatchJob?.cancel()

        managerScope.launch {
            if (callId.isNotBlank()) {
                signaling?.updateCallStatus(callId, "ended", reason, duration)
                if (peerId.isNotBlank()) {
                    signaling?.sendSignaling(callId, peerId, "bye", "{\"reason\":\"$reason\"}")
                }
                signaling?.clearIncomingCall(ServiceLocator.sessionManager.getUserId().orEmpty())
            }
        }

        appContext?.let { ctx ->
            VideoCallService.stopService(ctx)
        }

        runOnRtc {
            try {
                peerConnection?.close()
                peerConnection?.dispose()
            } catch (_: Exception) {}
            peerConnection = null

            try {
                surfaceTextureHelper?.stopListening()
            } catch (_: Exception) {}

            try {
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
            } catch (_: Exception) {}
            videoCapturer = null

            try {
                surfaceTextureHelper?.dispose()
            } catch (_: Exception) {}
            surfaceTextureHelper = null

            try {
                videoSource?.dispose()
            } catch (_: Exception) {}
            videoSource = null

            try {
                audioSource?.dispose()
            } catch (_: Exception) {}
            audioSource = null

            localVideoTrack = null
            localAudioTrack = null
            remoteVideoTrack = null
            remoteAudioTrack = null
            isRemoteDescriptionSet = false
            pendingRemoteCandidates.clear()
        }

        // Restore Audio
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.isSpeakerphoneOn = false
        } catch (_: Exception) {}

        releaseWakeLock()

        _uiState.value = _uiState.value.copy(
            status = VideoCallStatus.Ended(reason),
            hasRemoteVideo = false
        )

        managerScope.launch {
            delay(1500)
            _uiState.value = VideoCallUiState()
            isDisposing.set(false)
        }
    }
}
