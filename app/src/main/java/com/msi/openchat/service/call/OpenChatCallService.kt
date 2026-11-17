package com.msi.openchat.service.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.common.Resource
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.feature.calls.ActiveAudioCallActivity
import com.msi.openchat.feature.calls.CallUiState
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service that owns the entire Audio Call WebRTC lifecycle:
 * - WebRtcManager (PeerConnection, AudioSource/AudioTrack, AudioManager MODE_IN_COMMUNICATION & focus)
 * - Firebase RTDB signaling (offer/answer/ICE) & call session observers
 * - Proximity sensor + PROXIMITY_SCREEN_OFF_WAKE_LOCK & PARTIAL_WAKE_LOCK
 * - Ongoing foreground notification (Connected + MM:SS timer, Mute, Speaker, End Call)
 * - LocalBinder so Activities/Fragments act strictly as thin views
 */
class OpenChatCallService : Service(), SensorEventListener {
    private val tag = "OpenChatCallService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun getService(): OpenChatCallService = this@OpenChatCallService
    }

    private val binder = LocalBinder()

    private var observerJob: Job? = null
    private var signalingJob: Job? = null
    private var callSessionJob: Job? = null
    private var durationTimerJob: Job? = null
    private var callTimeoutJob: Job? = null

    private var webRtcManager: WebRtcManager? = null
    private val processedSignalingIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var hasProcessedOffer = false
    @Volatile private var hasProcessedAnswer = false
    @Volatile private var hasEnded = false

    private var currentCallId: String = ""
    private var currentContactId: String = ""
    private var currentContactName: String = "OpenChat Call"
    private var currentIsIncoming: Boolean = false
    private var currentIsWaitingAnswer: Boolean = false

    // Proximity & CPU wake locks owned by service
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var isProximityRegistered = false

    private val _callUiState = MutableStateFlow(CallUiState(isCallActive = false))
    val callUiState: StateFlow<CallUiState> = _callUiState.asStateFlow()

    companion object {
        const val ACTION_START_CALL = "com.msi.openchat.action.START_CALL"
        const val ACTION_ACCEPT_CALL = "com.msi.openchat.action.SERVICE_ACCEPT_CALL"
        const val ACTION_START_OUTGOING = "com.msi.openchat.action.START_OUTGOING_CALL"
        const val ACTION_STOP_CALL = "com.msi.openchat.action.STOP_CALL"
        const val ACTION_END_CALL = "com.msi.openchat.action.SERVICE_END_CALL"
        const val ACTION_TOGGLE_MUTE = "com.msi.openchat.action.SERVICE_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.msi.openchat.action.SERVICE_TOGGLE_SPEAKER"

        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        const val EXTRA_CONTACT_NAME = "extra_contact_name"
        const val EXTRA_IS_INCOMING = "extra_is_incoming"
        const val EXTRA_IS_ALREADY_ANSWERED = "extra_is_already_answered"

        fun createAcceptIntent(
            context: Context,
            callId: String,
            contactId: String,
            contactName: String
        ): Intent {
            return Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_ACCEPT_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra("callId", callId)
                putExtra("peerUserId", contactId)
            }
        }

        fun acceptCallService(
            context: Context,
            callId: String,
            contactId: String,
            contactName: String
        ) {
            val intent = createAcceptIntent(context, callId, contactId, contactName)
            dispatchForegroundService(context, intent)
        }

        fun startOutgoingCallService(
            context: Context,
            callId: String,
            contactId: String,
            contactName: String
        ) {
            val intent = Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_START_OUTGOING
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
            }
            dispatchForegroundService(context, intent)
        }

        fun startCallService(
            context: Context,
            callId: String,
            contactName: String,
            contactId: String = "",
            isIncoming: Boolean = false,
            isAlreadyAnswered: Boolean = true
        ) {
            val intent = Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_START_CALL
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CONTACT_ID, contactId)
                putExtra(EXTRA_CONTACT_NAME, contactName)
                putExtra(EXTRA_IS_INCOMING, isIncoming)
                putExtra(EXTRA_IS_ALREADY_ANSWERED, isAlreadyAnswered)
            }
            dispatchForegroundService(context, intent)
        }

        fun stopCallService(context: Context) {
            val intent = Intent(context, OpenChatCallService::class.java).apply {
                action = ACTION_STOP_CALL
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        private fun dispatchForegroundService(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w("OpenChatCallService", "Unable to start call service: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ServiceLocator.initialize(applicationContext)
        createNotificationChannel()
        setupSensorsAndWakeLocks()
    }

    private fun setupSensorsAndWakeLocks() {
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
            ) {
                proximityWakeLock = powerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "OpenChat:ServiceAudioProximityLock"
                ).apply { setReferenceCounted(false) }
            }
            cpuWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OpenChat:ServiceAudioCpuLock"
            ).apply { setReferenceCounted(false) }
        } catch (e: Exception) {
            Log.w(tag, "Sensor/WakeLock init warning: ${e.message}")
        }
    }

    private fun promoteCallForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    Constants.NOTIFICATION_ID_ACTIVE_CALL,
                    notification,
                    getCallForegroundServiceType()
                )
            } else {
                startForeground(
                    Constants.NOTIFICATION_ID_ACTIVE_CALL,
                    notification
                )
            }
            true
        } catch (se: SecurityException) {
            Log.w(tag, "startForeground permission fallback: ${se.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startForeground(
                        Constants.NOTIFICATION_ID_ACTIVE_CALL,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(Constants.NOTIFICATION_ID_ACTIVE_CALL, notification)
                }
                true
            } catch (fallback: Exception) {
                Log.e(tag, "startForeground fallback failed: ${fallback.message}", fallback)
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to promote call service to foreground: ${e.message}", e)
            false
        }
    }

    private fun getCallForegroundServiceType(): Int {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasMic) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        } else {
            0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null && intent == null) {
            val activeId = CallSessionCoordinator.currentCallId.value.orEmpty()
            val activeName = CallSessionCoordinator.contactName.value
            if (activeId.isNotBlank() && CallSessionCoordinator.isActive()) {
                currentCallId = activeId
                currentContactName = activeName.ifBlank { "OpenChat Call" }
                val recoveryNotification = buildOngoingCallNotification(
                    contactName = currentContactName,
                    statusLabel = "Connecting...",
                    durationSeconds = CallSessionCoordinator.durationSeconds.value,
                    isConnected = CallSessionCoordinator.callState.value == WebRtcCallState.CONNECTED,
                    isMuted = CallSessionCoordinator.isMuted.value,
                    isSpeakerOn = CallSessionCoordinator.isSpeakerOn.value
                )
                if (!promoteCallForeground(recoveryNotification)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startNotificationObserver()
                return START_STICKY
            } else {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        when (action) {
            ACTION_ACCEPT_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                    ?.ifBlank { null }
                    ?: intent.getStringExtra("callId").orEmpty()
                val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
                    ?.ifBlank { null }
                    ?: intent.getStringExtra("peerUserId").orEmpty()
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME)
                    ?.ifBlank { null }
                    ?: "OpenChat Caller"

                if (callId.isBlank() ||
                    CallSessionGuard.isVideoCall(callId, null) ||
                    CallSessionGuard.isVideoCallActive() ||
                    VideoCallManager.isCallActive()
                ) {
                    Log.w(tag, "Blocked ACTION_ACCEPT_CALL for invalid/video callId=$callId")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Stop incoming ringing & dismiss incoming UI cleanly without launching any Activity
                AppServiceManager.stopCallRinging(this)
                OpenChatNotificationManager.cancelIncomingCallNotification(this)
                sendBroadcast(Intent(CallActionReceiver.ACTION_DISMISS_INCOMING_UI).apply {
                    setPackage(packageName)
                    putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
                })

                val initialNotification = buildOngoingCallNotification(
                    contactName = contactName,
                    statusLabel = "Connecting...",
                    durationSeconds = 0,
                    isConnected = false,
                    isMuted = false,
                    isSpeakerOn = false
                )
                if (!promoteCallForeground(initialNotification)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startOrAttachCallSession(
                    callId = callId,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = true,
                    isAlreadyAnswered = true,
                    markAcceptedInDb = true
                )
                startNotificationObserver()
            }

            ACTION_START_OUTGOING -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                val contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "OpenChat Call"

                if (callId.isBlank() || CallSessionGuard.isVideoCall(callId, null)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val initialNotification = buildOngoingCallNotification(
                    contactName = contactName,
                    statusLabel = "Calling...",
                    durationSeconds = 0,
                    isConnected = false,
                    isMuted = false,
                    isSpeakerOn = false
                )
                if (!promoteCallForeground(initialNotification)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startOrAttachCallSession(
                    callId = callId,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = false,
                    isAlreadyAnswered = false,
                    markAcceptedInDb = false
                )
                startNotificationObserver()
            }

            ACTION_START_CALL -> {
                val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
                val contactId = intent.getStringExtra(EXTRA_CONTACT_ID).orEmpty()
                val contactName = intent.getStringExtra(EXTRA_CONTACT_NAME) ?: "OpenChat Call"
                val isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
                val isAlreadyAnswered = intent.getBooleanExtra(EXTRA_IS_ALREADY_ANSWERED, true)

                if (callId.isBlank() || CallSessionGuard.isVideoCall(callId, null)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                val initialNotification = buildOngoingCallNotification(
                    contactName = contactName,
                    statusLabel = if (_callUiState.value.connectionState == WebRtcCallState.CONNECTED) "Connected" else "Connecting...",
                    durationSeconds = _callUiState.value.durationSeconds,
                    isConnected = _callUiState.value.connectionState == WebRtcCallState.CONNECTED,
                    isMuted = _callUiState.value.isMuted,
                    isSpeakerOn = _callUiState.value.isSpeakerOn
                )
                if (!promoteCallForeground(initialNotification)) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                startOrAttachCallSession(
                    callId = callId,
                    contactId = contactId,
                    contactName = contactName,
                    isIncoming = isIncoming,
                    isAlreadyAnswered = isAlreadyAnswered,
                    markAcceptedInDb = false
                )
                startNotificationObserver()
            }

            ACTION_TOGGLE_MUTE -> {
                toggleMute()
            }

            ACTION_TOGGLE_SPEAKER -> {
                toggleSpeaker()
            }

            ACTION_END_CALL -> {
                endCall()
                return START_NOT_STICKY
            }

            ACTION_STOP_CALL -> {
                endCallInternal("Call ended", updateRemote = false)
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Idempotently attaches to an already-active callId (ZERO reconnect),
     * or initializes a brand-new WebRTC + signaling session inside OpenChatCallService.
     */
    fun startOrAttachCallSession(
        callId: String,
        contactId: String,
        contactName: String,
        isIncoming: Boolean,
        isAlreadyAnswered: Boolean,
        markAcceptedInDb: Boolean = false
    ) {
        if (callId.isBlank() || CallSessionGuard.isVideoCall(callId, null)) return

        // Idempotent guard: if this exact callId is already live in this service, DO NOT reconnect!
        if (currentCallId == callId && !hasEnded && _callUiState.value.isCallActive && webRtcManager != null) {
            Log.i(tag, "Re-attaching UI to already-active audio callId=$callId (no reconnect)")
            if (contactName.isNotBlank() && currentContactName != contactName) {
                currentContactName = contactName
                publishState(_callUiState.value.copy(contactName = contactName))
            }
            CallSessionCoordinator.registerService(this, callId, currentContactId, currentContactName)
            return
        }

        // Clean up any stale prior manager if switching calls
        if (webRtcManager != null && currentCallId != callId) {
            cleanupResourcesOnly()
        }

        hasEnded = false
        hasProcessedOffer = false
        hasProcessedAnswer = false
        processedSignalingIds.clear()

        currentCallId = callId
        currentContactId = contactId
        currentContactName = contactName.ifBlank { "OpenChat Call" }
        currentIsIncoming = isIncoming
        currentIsWaitingAnswer = isIncoming && !isAlreadyAnswered

        val initialStatus = when {
            isIncoming && !isAlreadyAnswered -> "Incoming call..."
            isIncoming -> "Connecting..."
            else -> "Calling..."
        }

        publishState(
            CallUiState(
                callId = currentCallId,
                contactId = currentContactId,
                contactName = currentContactName,
                isCaller = !isIncoming,
                isIncomingWaiting = currentIsWaitingAnswer,
                connectionState = WebRtcCallState.CONNECTING,
                callStatus = initialStatus,
                durationSeconds = 0,
                isMuted = false,
                isSpeakerOn = false,
                isCallActive = true
            )
        )

        CallSessionCoordinator.registerService(this, currentCallId, currentContactId, currentContactName)
        acquireServiceWakeLockAndProximity()
        initWebRtc()

        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        startObservingSignaling(currentCallId)

        if (isIncoming) {
            if (isAlreadyAnswered) {
                clearIncomingNode()
                serviceScope.launch {
                    try {
                        ServiceLocator.callRepository.updateAudioCallStatus(
                            callId = currentCallId,
                            status = if (markAcceptedInDb) "accepted" else "connecting"
                        )
                    } catch (e: Exception) {
                        Log.w(tag, "Failed updating call status on accept: ${e.message}")
                    }
                }
                if (hasMic) {
                    startWebRtcCall(isCaller = false)
                }
            } else {
                serviceScope.launch {
                    try {
                        ServiceLocator.callRepository.updateAudioCallStatus(currentCallId, "ringing")
                        ServiceLocator.callRepository.sendSignaling(currentCallId, currentContactId, "ringing", "{}")
                    } catch (_: Exception) {}
                }
            }
        } else {
            if (hasMic) {
                startWebRtcCall(isCaller = true)
            }
            startOutgoingCallTimeout()
        }
    }

    fun onPermissionGranted() {
        if (currentCallId.isBlank() || hasEnded) return
        if (currentIsIncoming && !currentIsWaitingAnswer) {
            startWebRtcCall(isCaller = false)
        } else if (!currentIsIncoming) {
            startWebRtcCall(isCaller = true)
        }
        // Re-promote foreground type to include MICROPHONE now that permission is granted
        val s = _callUiState.value
        val notif = buildOngoingCallNotification(
            contactName = currentContactName,
            statusLabel = s.callStatus,
            durationSeconds = s.durationSeconds,
            isConnected = s.connectionState == WebRtcCallState.CONNECTED,
            isMuted = s.isMuted,
            isSpeakerOn = s.isSpeakerOn
        )
        promoteCallForeground(notif)
    }

    fun acceptWaitingIncomingCall() {
        if (currentCallId.isBlank() || !currentIsWaitingAnswer) return
        currentIsWaitingAnswer = false
        publishState(_callUiState.value.copy(isIncomingWaiting = false, callStatus = "Connecting..."))
        clearIncomingNode()
        serviceScope.launch {
            try {
                ServiceLocator.callRepository.updateAudioCallStatus(currentCallId, "accepted")
            } catch (_: Exception) {}
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWebRtcCall(isCaller = false)
        }
    }

    fun declineCall() {
        val cid = currentCallId
        val peer = currentContactId
        clearIncomingNode()
        serviceScope.launch {
            if (cid.isNotBlank()) {
                try {
                    ServiceLocator.callRepository.updateCallStatus(cid, "declined", endReason = "Declined by user")
                    if (peer.isNotBlank()) {
                        ServiceLocator.callRepository.sendSignaling(cid, peer, "bye", "{\"reason\":\"declined\"}")
                    }
                } catch (_: Exception) {}
            }
            endCallInternal("Call Declined", updateRemote = false)
        }
    }

    fun toggleMute(): Boolean {
        val newMuted = webRtcManager?.toggleMute() ?: !_callUiState.value.isMuted
        publishState(_callUiState.value.copy(isMuted = newMuted))
        refreshOngoingNotification()
        return newMuted
    }

    fun toggleSpeaker(): Boolean {
        val newSpeaker = webRtcManager?.toggleSpeaker() ?: !_callUiState.value.isSpeakerOn
        if (newSpeaker) {
            releaseProximityWakeLock()
        }
        publishState(_callUiState.value.copy(isSpeakerOn = newSpeaker))
        refreshOngoingNotification()
        return newSpeaker
    }

    fun endCall() {
        val cid = currentCallId
        val peer = currentContactId
        val duration = _callUiState.value.durationSeconds
        serviceScope.launch {
            if (cid.isNotBlank()) {
                try {
                    if (peer.isNotBlank()) {
                        ServiceLocator.callRepository.sendSignaling(cid, peer, "bye", "{\"reason\":\"ended\"}")
                    }
                    ServiceLocator.callRepository.updateCallStatus(
                        callId = cid,
                        status = "ended",
                        durationSeconds = duration,
                        endReason = "Normal hangup"
                    )
                } catch (_: Exception) {}
            }
            endCallInternal("Call ended", updateRemote = false)
        }
    }

    private fun clearIncomingNode() {
        val uid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
        if (!uid.isNullOrBlank()) {
            ServiceLocator.callRepository.clearIncomingAudioCall(uid)
        }
    }

    private fun initWebRtc() {
        val manager = WebRtcManager(
            context = applicationContext,
            onIceCandidate = { candidate ->
                sendIceCandidate(candidate)
            },
            onCallStateChanged = { state ->
                handleWebRtcStateChange(state)
            }
        )
        webRtcManager = manager

        serviceScope.launch {
            manager.networkStats.collect { stats ->
                if (!hasEnded) {
                    publishState(_callUiState.value.copy(networkStats = stats))
                }
            }
        }
    }

    private fun startWebRtcCall(isCaller: Boolean) {
        val currentRtcState = webRtcManager?.callState?.value
        if (currentRtcState == WebRtcCallState.CONNECTING ||
            currentRtcState == WebRtcCallState.CONNECTED
        ) {
            return
        }
        serviceScope.launch {
            val iceServers = ServiceLocator.callRepository.getIceServers()
            webRtcManager?.startCall(
                iceServers = iceServers,
                isCaller = isCaller,
                onOfferCreated = { offerDesc ->
                    sendOffer(offerDesc)
                }
            )
        }
    }

    private fun startOutgoingCallTimeout() {
        callTimeoutJob?.cancel()
        callTimeoutJob = serviceScope.launch {
            delay(35_000L)
            if (isActive && _callUiState.value.isCallActive && _callUiState.value.connectionState != WebRtcCallState.CONNECTED) {
                Log.i(tag, "Outgoing audio call timed out with no answer")
                val cid = currentCallId
                if (cid.isNotBlank()) {
                    try {
                        ServiceLocator.callRepository.updateCallStatus(cid, "missed", endReason = "No answer")
                        if (currentContactId.isNotBlank()) {
                            ServiceLocator.callRepository.sendSignaling(cid, currentContactId, "bye", "{\"reason\":\"timeout\"}")
                        }
                    } catch (_: Exception) {}
                }
                endCallInternal("No Answer", updateRemote = false)
            }
        }
    }

    private fun sendOffer(desc: SessionDescription) {
        val cid = currentCallId
        if (cid.isBlank()) return
        serviceScope.launch {
            val payload = JSONObject().apply {
                put("sdp", desc.description)
                put("type", desc.type.canonicalForm())
            }
            ServiceLocator.callRepository.sendSignaling(cid, currentContactId, "offer", payload.toString())
        }
    }

    private fun sendAnswer(desc: SessionDescription) {
        val cid = currentCallId
        if (cid.isBlank()) return
        serviceScope.launch {
            val payload = JSONObject().apply {
                put("sdp", desc.description)
                put("type", desc.type.canonicalForm())
            }
            ServiceLocator.callRepository.sendSignaling(cid, currentContactId, "answer", payload.toString())
        }
    }

    private fun sendIceCandidate(candidate: IceCandidate?) {
        val cid = currentCallId
        if (candidate == null || cid.isBlank() || candidate.sdp.isNullOrBlank()) return
        serviceScope.launch {
            try {
                val payload = JSONObject().apply {
                    put("sdp", candidate.sdp)
                    if (candidate.sdpMid != null) put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }
                ServiceLocator.callRepository.sendSignaling(cid, currentContactId, "ice_candidate", payload.toString())
            } catch (t: Throwable) {
                Log.e(tag, "Failed to send ICE candidate: ${t.message}", t)
            }
        }
    }

    private fun startObservingSignaling(targetCallId: String) {
        if (targetCallId.isBlank()) return

        signalingJob?.cancel()
        signalingJob = serviceScope.launch {
            val myUid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
            ServiceLocator.callRepository.observeSignalingMessages(targetCallId).collect { msg ->
                if (hasEnded || !_callUiState.value.isCallActive) return@collect
                val isFromRemotePeer = (currentContactId.isNotBlank() && msg.senderId == currentContactId) ||
                    (myUid != null && msg.senderId.isNotBlank() && msg.senderId != myUid)
                if (isFromRemotePeer) {
                    val id = msg.id
                    if (id == null || processedSignalingIds.add(id)) {
                        handleIncomingSignalingMessage(msg.type, msg.payload)
                    }
                }
            }
        }

        callSessionJob?.cancel()
        callSessionJob = serviceScope.launch {
            ServiceLocator.callRepository.observeCallSession(targetCallId).collect { session ->
                if (hasEnded || !_callUiState.value.isCallActive) return@collect
                when (session.status) {
                    "ringing" -> {
                        if (_callUiState.value.isCaller && _callUiState.value.callStatus == "Calling...") {
                            publishState(_callUiState.value.copy(callStatus = "Ringing..."))
                        }
                    }
                    "accepted", "connecting" -> {
                        callTimeoutJob?.cancel()
                        if (_callUiState.value.callStatus in listOf("Calling...", "Ringing...")) {
                            publishState(_callUiState.value.copy(callStatus = "Connecting..."))
                        }
                    }
                    "connected" -> {
                        callTimeoutJob?.cancel()
                        if (webRtcManager?.callState?.value == WebRtcCallState.CONNECTED &&
                            _callUiState.value.callStatus != "Connected"
                        ) {
                            publishState(_callUiState.value.copy(callStatus = "Connected"))
                            startDurationTimer()
                        }
                    }
                    "declined" -> endCallInternal("Call Declined", updateRemote = false)
                    "busy" -> endCallInternal("User Busy", updateRemote = false)
                    "missed" -> endCallInternal("No Answer", updateRemote = false)
                    "ended" -> endCallInternal("Call Ended", updateRemote = false)
                    "failed" -> endCallInternal("Call Failed", updateRemote = false)
                }
            }
        }
    }

    private fun handleIncomingSignalingMessage(type: String, payloadStr: String) {
        try {
            val payload = JSONObject(payloadStr)
            when (type) {
                "ringing" -> {
                    if (_callUiState.value.isCaller && _callUiState.value.callStatus == "Calling...") {
                        publishState(_callUiState.value.copy(callStatus = "Ringing..."))
                    }
                }
                "offer" -> {
                    if (hasProcessedOffer) return
                    hasProcessedOffer = true
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
                    if (hasProcessedAnswer) return
                    hasProcessedAnswer = true
                    callTimeoutJob?.cancel()
                    val sdp = payload.optString("sdp")
                    if (sdp.isNotBlank()) {
                        val desc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
                        webRtcManager?.handleRemoteAnswer(desc)
                    }
                }
                "ice_candidate" -> {
                    val sdp = payload.optString("sdp")
                    val sdpMid = payload.optString("sdpMid").ifBlank { "0" }
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
                    endCallInternal(displayStatus, updateRemote = false)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Error parsing signaling message ($type): ${t.message}", t)
        }
    }

    private fun handleWebRtcStateChange(state: WebRtcCallState) {
        if (hasEnded) return
        val nextStatus = when (state) {
            WebRtcCallState.CONNECTING -> {
                if (_callUiState.value.callStatus in listOf("Calling...", "Ringing...")) {
                    _callUiState.value.callStatus
                } else {
                    "Connecting..."
                }
            }
            WebRtcCallState.CONNECTED -> "Connected"
            WebRtcCallState.RECONNECTING,
            WebRtcCallState.DISCONNECTED -> "Reconnecting..."
            WebRtcCallState.FAILED -> "Connection Failed"
            WebRtcCallState.ENDED -> "Call Ended"
            WebRtcCallState.IDLE -> _callUiState.value.callStatus
        }

        publishState(
            _callUiState.value.copy(
                connectionState = state,
                callStatus = nextStatus
            )
        )

        when (state) {
            WebRtcCallState.CONNECTED -> {
                startDurationTimer()
                refreshOngoingNotification()
                val cid = currentCallId
                if (cid.isNotBlank()) {
                    serviceScope.launch {
                        try {
                            ServiceLocator.callRepository.updateCallStatus(cid, "connected")
                        } catch (_: Exception) {}
                    }
                }
            }
            WebRtcCallState.FAILED -> endCallInternal("Call Failed", updateRemote = false)
            WebRtcCallState.ENDED -> endCallInternal("Call Ended", updateRemote = false)
            else -> Unit
        }
    }

    private fun startDurationTimer() {
        callTimeoutJob?.cancel()
        if (durationTimerJob?.isActive == true) return
        durationTimerJob = serviceScope.launch {
            while (isActive && _callUiState.value.isCallActive && !hasEnded) {
                delay(1000)
                val nextDuration = _callUiState.value.durationSeconds + 1
                publishState(_callUiState.value.copy(durationSeconds = nextDuration))
            }
        }
    }

    private fun publishState(state: CallUiState) {
        _callUiState.value = state
        CallSessionCoordinator.updateUiState(state)
    }

    private fun startNotificationObserver() {
        observerJob?.cancel()
        observerJob = serviceScope.launch {
            combine(
                _callUiState,
                CallSessionCoordinator.durationSeconds,
                CallSessionCoordinator.isSpeakerOn,
                CallSessionCoordinator.isMuted
            ) { state, duration, isSpeakerOn, isMuted ->
                NotificationRenderKey(
                    contactName = currentContactName,
                    statusLabel = state.callStatus,
                    duration = duration,
                    isConnected = state.connectionState == WebRtcCallState.CONNECTED,
                    isMuted = isMuted,
                    isSpeakerOn = isSpeakerOn,
                    isActive = state.isCallActive
                )
            }.collect { key ->
                if (!key.isActive || hasEnded) return@collect
                val notification = buildOngoingCallNotification(
                    contactName = key.contactName,
                    statusLabel = key.statusLabel,
                    durationSeconds = key.duration,
                    isConnected = key.isConnected,
                    isMuted = key.isMuted,
                    isSpeakerOn = key.isSpeakerOn
                )
                val manager = getSystemService(NotificationManager::class.java)
                manager?.notify(Constants.NOTIFICATION_ID_ACTIVE_CALL, notification)
            }
        }
    }

    private data class NotificationRenderKey(
        val contactName: String,
        val statusLabel: String,
        val duration: Int,
        val isConnected: Boolean,
        val isMuted: Boolean,
        val isSpeakerOn: Boolean,
        val isActive: Boolean
    )

    private fun refreshOngoingNotification() {
        if (hasEnded || !_callUiState.value.isCallActive) return
        val s = _callUiState.value
        val notification = buildOngoingCallNotification(
            contactName = currentContactName,
            statusLabel = s.callStatus,
            durationSeconds = s.durationSeconds,
            isConnected = s.connectionState == WebRtcCallState.CONNECTED,
            isMuted = s.isMuted,
            isSpeakerOn = s.isSpeakerOn
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(Constants.NOTIFICATION_ID_ACTIVE_CALL, notification)
    }

    private fun buildOngoingCallNotification(
        contactName: String,
        statusLabel: String,
        durationSeconds: Int,
        isConnected: Boolean,
        isMuted: Boolean,
        isSpeakerOn: Boolean
    ): Notification {
        val minutes = durationSeconds / 60
        val seconds = durationSeconds % 60
        val durationFormatted = String.format("%02d:%02d", minutes, seconds)

        val contentText = if (isConnected || durationSeconds > 0) {
            "Connected • $durationFormatted"
        } else {
            statusLabel.ifBlank { "Connecting..." }
        }

        // Open/resume ActiveAudioCallActivity bound to this live service (no reconnect)
        val resumeIntent = ActiveAudioCallActivity.createResumeIntent(
            context = this,
            callId = currentCallId,
            contactId = currentContactId,
            contactName = contactName,
            isIncoming = currentIsIncoming
        )
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            Constants.REQ_AUDIO_ONGOING_CONTENT,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Mute/Unmute action
        val muteIntent = Intent(this, OpenChatCallService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_AUDIO_ONGOING_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Speaker/Earpiece action
        val speakerIntent = Intent(this, OpenChatCallService::class.java).apply {
            action = ACTION_TOGGLE_SPEAKER
        }
        val speakerPendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_AUDIO_ONGOING_SPEAKER,
            speakerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // End Call action
        val endCallIntent = Intent(this, OpenChatCallService::class.java).apply {
            action = ACTION_END_CALL
        }
        val endPendingIntent = PendingIntent.getService(
            this,
            Constants.REQ_AUDIO_ONGOING_END,
            endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteTitle = if (isMuted) "Unmute" else "Mute"
        val muteIcon = if (isMuted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_24
        val speakerTitle = if (isSpeakerOn) "Earpiece" else "Speaker"
        val speakerIcon = if (isSpeakerOn) R.drawable.ic_volume_up_24 else R.drawable.ic_volume_off_24

        return NotificationCompat.Builder(this, Constants.CHANNEL_ACTIVE_CALLS)
            .setContentTitle("Voice Call • $contactName")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(muteIcon, muteTitle, mutePendingIntent)
            .addAction(speakerIcon, speakerTitle, speakerPendingIntent)
            .addAction(R.drawable.ic_call_end_24, "End", endPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .build()
    }

    // ---- Proximity & CPU WakeLocks ----

    private fun acquireServiceWakeLockAndProximity() {
        try {
            if (cpuWakeLock?.isHeld == false) {
                cpuWakeLock?.acquire(60 * 60 * 1000L)
            }
            if (!isProximityRegistered) {
                proximitySensor?.let {
                    sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                    isProximityRegistered = true
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed acquiring service wake locks: ${e.message}")
        }
    }

    private fun releaseServiceWakeLocksAndProximity() {
        try {
            if (isProximityRegistered) {
                sensorManager?.unregisterListener(this)
                isProximityRegistered = false
            }
        } catch (_: Exception) {}
        releaseProximityWakeLock()
        try {
            if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun releaseProximityWakeLock() {
        try {
            if (proximityWakeLock?.isHeld == true) proximityWakeLock?.release()
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_PROXIMITY) return
        if (hasEnded || _callUiState.value.isSpeakerOn) {
            releaseProximityWakeLock()
            return
        }
        val distance = event.values[0]
        val maxRange = proximitySensor?.maximumRange ?: 5f
        if (distance < maxRange) {
            try {
                if (proximityWakeLock?.isHeld == false) {
                    proximityWakeLock?.acquire(30 * 60 * 1000L)
                }
            } catch (_: Exception) {}
        } else {
            releaseProximityWakeLock()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ---- Teardown ----

    private fun cleanupResourcesOnly() {
        callTimeoutJob?.cancel()
        durationTimerJob?.cancel()
        signalingJob?.cancel()
        callSessionJob?.cancel()
        hasProcessedOffer = false
        hasProcessedAnswer = false
        processedSignalingIds.clear()
        webRtcManager?.endCall()
        webRtcManager = null
    }

    private fun endCallInternal(reason: String, updateRemote: Boolean) {
        if (hasEnded) return
        hasEnded = true

        val cid = currentCallId
        val duration = _callUiState.value.durationSeconds
        observerJob?.cancel()
        cleanupResourcesOnly()
        releaseServiceWakeLocksAndProximity()

        if (updateRemote && cid.isNotBlank()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ServiceLocator.callRepository.updateCallStatus(
                        callId = cid,
                        status = "ended",
                        durationSeconds = duration,
                        endReason = reason
                    )
                } catch (_: Exception) {}
            }
        }

        publishState(
            _callUiState.value.copy(
                connectionState = WebRtcCallState.ENDED,
                callStatus = reason,
                isCallActive = false
            )
        )
        CallSessionCoordinator.unregisterActiveCall(cid)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager?.cancel(Constants.NOTIFICATION_ID_ACTIVE_CALL)
        currentCallId = ""
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                Constants.CHANNEL_ACTIVE_CALLS,
                "Active Call Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing active voice call duration and audio controls"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!hasEnded && currentCallId.isNotBlank()) {
            endCallInternal("Call ended", updateRemote = false)
        } else {
            observerJob?.cancel()
            cleanupResourcesOnly()
            releaseServiceWakeLocksAndProximity()
            val manager = getSystemService(NotificationManager::class.java)
            manager?.cancel(Constants.NOTIFICATION_ID_ACTIVE_CALL)
        }
        serviceScope.cancel()
        Log.i(tag, "OpenChatCallService destroyed.")
    }
}

