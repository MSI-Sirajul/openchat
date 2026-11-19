package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.databinding.ActivityMainBinding
import com.google.android.material.color.DynamicColors
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.permission.PermissionManager
import com.msi.openchat.feature.auth.AuthFragment
import com.msi.openchat.feature.calls.ActiveAudioCallActivity
import com.msi.openchat.feature.calls.CallUiState
import com.msi.openchat.feature.calls.IncomingAudioCallActivity
import com.msi.openchat.feature.chat.ChatFragment
import com.msi.openchat.feature.friends.AddFriendFragment
import com.msi.openchat.feature.friends.FriendRequestsFragment
import com.msi.openchat.feature.home.HomeFragment
import com.msi.openchat.feature.permission.PermissionOnboardingFragment
import com.msi.openchat.feature.profile.ProfileFragment
import com.msi.openchat.feature.security.AppLockFragment
import com.msi.openchat.feature.settings.SettingsFragment
import com.msi.openchat.feature.videocall.ActiveVideoCallActivity
import com.msi.openchat.feature.videocall.IncomingVideoCallActivity
import com.msi.openchat.feature.videocall.OutgoingVideoCallActivity
import com.msi.openchat.feature.videocall.VideoCallActivity
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.feature.videocall.VideoCallStatus
import com.msi.openchat.feature.videocall.VideoCallUiState
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.WebRtcCallState
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val fragmentLifecycleCallbacks = object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
            refreshActiveCallBanner()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Ensure saved theme mode is applied before inflating views
        com.msi.openchat.core.theme.ThemeManager.applySavedTheme(this)

        super.onCreate(savedInstanceState)

        // When activity is recreated (e.g. theme change), apply smooth fade transition
        if (savedInstanceState != null) {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_OPEN, R.anim.fade_in, R.anim.fade_out)
            } else {
                @Suppress("DEPRECATION")
                overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
            }
        }

        // Initialize core dependencies
        ServiceLocator.initialize(applicationContext)

        // Material You dynamic colors
        DynamicColors.applyToActivityIfAvailable(this)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.layoutActiveCallBanner.setOnClickListener {
            openActiveCallScreen()
        }
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentLifecycleCallbacks, false)
        supportFragmentManager.addOnBackStackChangedListener {
            refreshActiveCallBanner()
        }

        // Sync status bar color and icon appearance with title bar surface color
        syncStatusBarColor()

        // Relaunch Credential Check: If user credentials exist in secure storage, validate and refresh token
        if (ServiceLocator.sessionManager.hasSecureCredentials()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ServiceLocator.authManager.getValidAccessToken()
                    val uid = ServiceLocator.sessionManager.getUserId()
                    if (!uid.isNullOrBlank()) {
                        ServiceLocator.authRepository.syncAppLockFromRemote(uid)
                    }
                } catch (_: Exception) {}
            }
        }

        // Initialize notification channels for calls and messages
        OpenChatNotificationManager.initChannels(applicationContext)

        // Sync FCM push notification token
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    val uid = ServiceLocator.sessionManager.getUserId()
                    if (!token.isNullOrBlank() && !uid.isNullOrBlank()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                ServiceLocator.userRepository.updateFcmToken(uid, token)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        observeNetwork()
        observeAuthState()
        observeActiveCall()

        if (savedInstanceState == null) {
            setupInitialFragment()
            handleIntent(intent)
        }
    }

    private fun observeAuthState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.authRepository.authState.collect { authState ->
                    when (authState) {
                        is com.msi.openchat.core.security.AuthState.LoggedOut,
                        is com.msi.openchat.core.security.AuthState.Unauthenticated,
                        is com.msi.openchat.core.security.AuthState.SessionExpired -> {
                            navigateToAuth()
                        }
                        is com.msi.openchat.core.security.AuthState.Authenticated -> {
                            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
                            if (currentFragment is AuthFragment) {
                                if (PermissionManager.shouldShowOnboarding(this@MainActivity)) {
                                    navigateToPermissionOnboarding()
                                } else {
                                    navigateToHome()
                                }
                            }
                        }
                        else -> Unit
                    }
                    refreshActiveCallBanner()
                }
            }
        }
    }

    private fun observeNetwork() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ServiceLocator.networkMonitor.status.collect { status ->
                    if (!status.isConnected) {
                        binding.layoutNetworkBanner.visibility = View.VISIBLE
                        binding.textNetworkBanner.text = getString(R.string.home_offline_banner)
                    } else if (status.isUnstable) {
                        binding.layoutNetworkBanner.visibility = View.VISIBLE
                        binding.textNetworkBanner.text = getString(R.string.common_connection_unstable)
                    } else {
                        binding.layoutNetworkBanner.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeActiveCall() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    CallSessionCoordinator.uiState,
                    VideoCallManager.uiState
                ) { audioState, videoState ->
                    audioState to videoState
                }.collect { (audioState, videoState) ->
                    renderActiveCallBanner(audioState, videoState)
                }
            }
        }
    }

    private fun refreshActiveCallBanner() {
        if (!::binding.isInitialized) return
        renderActiveCallBanner(CallSessionCoordinator.uiState.value, VideoCallManager.uiState.value)
    }

    internal fun renderActiveCallBanner(
        audioState: CallUiState = CallSessionCoordinator.uiState.value,
        videoState: VideoCallUiState = VideoCallManager.uiState.value
    ) {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (!ServiceLocator.sessionManager.isAuthenticated() ||
            currentFragment is AuthFragment ||
            currentFragment is AppLockFragment
        ) {
            binding.layoutActiveCallBanner.visibility = View.GONE
            return
        }

        val isAudioActive = audioState.isCallActive &&
            audioState.callId.isNotBlank() &&
            audioState.connectionState != WebRtcCallState.ENDED
        if (isAudioActive) {
            val peerName = audioState.contactName
                .ifBlank { CallSessionCoordinator.contactName.value }
                .ifBlank { getString(R.string.common_contact) }
            val isReconnecting = audioState.connectionState == WebRtcCallState.RECONNECTING ||
                audioState.connectionState == WebRtcCallState.DISCONNECTED
            val durationText = if (audioState.durationSeconds > 0 ||
                audioState.connectionState == WebRtcCallState.CONNECTED
            ) {
                val mins = audioState.durationSeconds / 60
                val secs = audioState.durationSeconds % 60
                getString(R.string.common_duration_mm_ss, mins, secs)
            } else {
                audioState.callStatus.ifBlank { getString(R.string.common_connecting) }
            }

            binding.layoutActiveCallBanner.visibility = View.VISIBLE
            binding.layoutActiveCallBanner.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (isReconnecting) R.color.color_active_call_banner_reconnecting_bg
                    else R.color.color_active_call_banner_bg
                )
            )
            binding.imgActiveCallIcon.setImageResource(R.drawable.ic_call_24)
            binding.textActiveCallTitle.text = getString(R.string.active_call_voice_label, peerName)
            binding.textActiveCallSubtitle.text = getString(R.string.active_call_tap_to_return)
            binding.textActiveCallDuration.text = durationText
            return
        }

        val videoStatus = videoState.status
        val isVideoActive = videoStatus !is VideoCallStatus.Idle && videoStatus !is VideoCallStatus.Ended
        if (isVideoActive) {
            val peerName = videoState.peerName.ifBlank { getString(R.string.common_contact) }
            val isReconnecting = videoStatus is VideoCallStatus.Reconnecting || videoState.isReconnecting
            val durationText = when {
                videoStatus is VideoCallStatus.Connected -> videoState.formattedDuration
                isReconnecting -> getString(R.string.common_reconnecting_ellipsis)
                videoStatus is VideoCallStatus.OutgoingCalling -> getString(R.string.call_calling_ellipsis)
                videoStatus is VideoCallStatus.IncomingRinging -> getString(R.string.call_ringing_ellipsis)
                else -> getString(R.string.common_connecting)
            }

            binding.layoutActiveCallBanner.visibility = View.VISIBLE
            binding.layoutActiveCallBanner.setBackgroundColor(
                ContextCompat.getColor(
                    this,
                    if (isReconnecting) R.color.color_active_call_banner_reconnecting_bg
                    else R.color.color_active_call_banner_bg
                )
            )
            binding.imgActiveCallIcon.setImageResource(R.drawable.ic_video_24)
            binding.textActiveCallTitle.text = getString(R.string.active_call_video_label, peerName)
            binding.textActiveCallSubtitle.text = getString(R.string.active_call_tap_to_return)
            binding.textActiveCallDuration.text = durationText
            return
        }

        binding.layoutActiveCallBanner.visibility = View.GONE
    }

    internal fun openActiveCallScreen(
        audioState: CallUiState = CallSessionCoordinator.uiState.value,
        videoState: VideoCallUiState = VideoCallManager.uiState.value
    ) {
        val isAudioActive = audioState.isCallActive &&
            audioState.callId.isNotBlank() &&
            audioState.connectionState != WebRtcCallState.ENDED
        if (isAudioActive) {
            val peerId = audioState.contactId.ifBlank { CallSessionCoordinator.contactId.value }
            val peerName = audioState.contactName
                .ifBlank { CallSessionCoordinator.contactName.value }
                .ifBlank { getString(R.string.common_contact) }
            val targetIntent = if (audioState.isIncomingWaiting) {
                IncomingAudioCallActivity.createIntent(
                    context = this,
                    callId = audioState.callId,
                    callerId = peerId,
                    callerName = peerName
                )
            } else {
                ActiveAudioCallActivity.createResumeIntent(
                    context = this,
                    callId = audioState.callId,
                    contactId = peerId,
                    contactName = peerName,
                    isIncoming = !audioState.isCaller
                )
            }
            startActivity(targetIntent)
            return
        }

        val videoStatus = videoState.status
        if (videoStatus !is VideoCallStatus.Idle && videoStatus !is VideoCallStatus.Ended) {
            val peerName = videoState.peerName.ifBlank { getString(R.string.common_contact) }
            val targetIntent = when (videoStatus) {
                is VideoCallStatus.IncomingRinging -> IncomingVideoCallActivity.createIntent(
                    context = this,
                    callId = videoState.callId.ifBlank { videoStatus.callId },
                    callerId = videoState.peerId.ifBlank { videoStatus.callerId },
                    callerName = peerName
                )
                is VideoCallStatus.OutgoingCalling -> OutgoingVideoCallActivity.createResumeIntent(
                    context = this,
                    peerId = videoState.peerId.ifBlank { videoStatus.receiverId },
                    peerName = peerName
                )
                else -> if (VideoCallManager.resumeActivityClassName == VideoCallActivity::class.java.name) {
                    VideoCallActivity.createResumeIntent(this)
                } else {
                    ActiveVideoCallActivity.createResumeIntent(this)
                }
            }
            startActivity(targetIntent)
        }
    }

    private var lastBackgroundTimestamp: Long = 0L

    private fun setupInitialFragment() {
        val initialFragment = when {
            !ServiceLocator.sessionManager.isAuthenticated() -> AuthFragment.newInstance()
            ServiceLocator.sessionManager.secureStorage.isAppLockRequired() &&
                    !ServiceLocator.sessionManager.secureStorage.isAppUnlocked() -> AppLockFragment.newInstance()
            PermissionManager.shouldShowOnboarding(this) -> PermissionOnboardingFragment.newInstance()
            else -> HomeFragment.newInstance()
        }

        supportFragmentManager.commit {
            replace(R.id.fragment_container, initialFragment)
        }
    }

    fun navigateToPermissionOnboarding() {
        try {
            while (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate()
            }
        } catch (_: Exception) {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        supportFragmentManager.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            replace(R.id.fragment_container, PermissionOnboardingFragment.newInstance())
        }
    }

    fun navigateToHome() {
        try {
            while (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate()
            }
        } catch (_: Exception) {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        supportFragmentManager.commit {
            setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
            replace(R.id.fragment_container, HomeFragment.newInstance())
        }
    }

    fun navigateToAuth() {
        try {
            while (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate()
            }
        } catch (_: Exception) {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (currentFragment !is AuthFragment) {
            supportFragmentManager.commit(allowStateLoss = true) {
                setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                replace(R.id.fragment_container, AuthFragment.newInstance())
            }
        }
    }

    fun navigateToChat(
        conversationId: String,
        contactName: String,
        contactId: String,
        autoSendHi: Boolean = false
    ) {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(
                R.id.fragment_container,
                ChatFragment.newInstance(conversationId, contactName, contactId, autoSendHi)
            )
            addToBackStack("chat")
        }
    }

    fun navigateToCall(
        contactId: String,
        contactName: String,
        callId: String? = null,
        isIncoming: Boolean = false,
        isAlreadyAnswered: Boolean = false
    ) {
        if (isIncoming && !callId.isNullOrBlank()) {
            if (isAlreadyAnswered) {
                startActivity(
                    com.msi.openchat.feature.calls.ActiveAudioCallActivity.createIntent(
                        context = this,
                        callId = callId,
                        contactId = contactId,
                        contactName = contactName,
                        isIncoming = true
                    )
                )
            } else {
                startActivity(
                    com.msi.openchat.feature.calls.IncomingAudioCallActivity.createIntent(
                        context = this,
                        callId = callId,
                        callerId = contactId,
                        callerName = contactName
                    )
                )
            }
        } else {
            com.msi.openchat.feature.calls.OutgoingAudioCallActivity.start(
                context = this,
                contactId = contactId,
                contactName = contactName
            )
        }
    }

    fun navigateToVideoCall(peerId: String, peerName: String) {
        com.msi.openchat.feature.videocall.OutgoingVideoCallActivity.start(this, peerId, peerName)
    }

    fun navigateToAddFriends() {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, AddFriendFragment.newInstance())
            addToBackStack("add_friends")
        }
    }

    fun navigateToNewChat() {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, com.msi.openchat.feature.chat.NewChatFragment.newInstance())
            addToBackStack("new_chat")
        }
    }

    fun navigateToFriendRequests() {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, FriendRequestsFragment.newInstance())
            addToBackStack("friend_requests")
        }
    }

    fun navigateToProfile(userId: String) {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, ProfileFragment.newInstance(userId))
            addToBackStack("profile")
        }
    }

    fun navigateToSettings() {
        supportFragmentManager.commit {
            setCustomAnimations(
                R.anim.slide_in_right,
                R.anim.slide_out_left,
                R.anim.slide_in_left,
                R.anim.slide_out_right
            )
            replace(R.id.fragment_container, SettingsFragment.newInstance())
            addToBackStack("settings")
        }
    }

    override fun onStart() {
        super.onStart()
        ServiceLocator.presenceManager.onAppForegrounded()
        if (ServiceLocator.sessionManager.isAuthenticated()) {
            com.msi.openchat.service.call.OpenChatKeepAliveService.start(applicationContext)
        }
        if (lastBackgroundTimestamp > 0L &&
            ServiceLocator.sessionManager.isAuthenticated() &&
            ServiceLocator.sessionManager.secureStorage.isAppLockRequired() &&
            System.currentTimeMillis() - lastBackgroundTimestamp > 60_000L) {
            ServiceLocator.sessionManager.secureStorage.setAppUnlocked(false)
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
            if (currentFragment !is AppLockFragment && currentFragment !is AuthFragment) {
                supportFragmentManager.commit {
                    setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                    replace(R.id.fragment_container, AppLockFragment.newInstance())
                }
            }
        }
        lastBackgroundTimestamp = 0L

        val activeAudioCallId = com.msi.openchat.service.call.CallSessionCoordinator.currentCallId.value
        if (com.msi.openchat.service.call.CallSessionCoordinator.isActive() &&
            !activeAudioCallId.isNullOrBlank() &&
            !com.msi.openchat.service.call.CallSessionCoordinator.hasPresentedUiFor(activeAudioCallId)
        ) {
            startActivity(com.msi.openchat.feature.calls.ActiveAudioCallActivity.createResumeIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        syncStatusBarColor()
        refreshActiveCallBanner()
    }

    override fun onDestroy() {
        supportFragmentManager.unregisterFragmentLifecycleCallbacks(fragmentLifecycleCallbacks)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        ServiceLocator.presenceManager.onAppBackgrounded()
        if (ServiceLocator.sessionManager.isAuthenticated() &&
            ServiceLocator.sessionManager.secureStorage.isAppLockRequired()) {
            lastBackgroundTimestamp = System.currentTimeMillis()
        }
    }

    fun syncStatusBarColor() {
        val surfaceColor = ContextCompat.getColor(this, R.color.color_surface)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.statusBarColor = surfaceColor
        window.navigationBarColor = surfaceColor
        val isDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val callType = intent.getStringExtra("call_type") ?: intent.getStringExtra("incoming_call_type")
        val incomingCallId = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CALL_ID)
            ?: intent.getStringExtra("incoming_call_id")

        if (callType == "video" ||
            action?.contains("VIDEO") == true ||
            intent.hasExtra("extra_video_call_id") ||
            com.msi.openchat.service.call.CallSessionGuard.isVideoCall(incomingCallId, callType)
        ) {
            // Strictly ignore video calls in MainActivity - handled exclusively by video activities/receivers
            return
        }

        val incomingCallerId = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CALLER_ID)
            ?: intent.getStringExtra("incoming_caller_id")
        val incomingCallerName = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CALLER_NAME)
            ?: intent.getStringExtra("incoming_caller_name")
            ?: getString(R.string.call_incoming_caller_fallback)

        // Handle decline call action directly from notification
        if (action == OpenChatNotificationManager.ACTION_DECLINE_CALL && !incomingCallId.isNullOrBlank()) {
            OpenChatNotificationManager.cancelIncomingCallNotification(this)
            CoroutineScope(Dispatchers.IO).launch {
                val uid = com.msi.openchat.data.remote.firebase.FirebaseManager.auth.currentUser?.uid
                    ?: ServiceLocator.sessionManager.getUserId()
                if (!uid.isNullOrBlank()) {
                    ServiceLocator.callRepository.clearIncomingAudioCall(uid)
                }
                ServiceLocator.callRepository.updateCallStatus(
                    callId = incomingCallId,
                    status = "declined",
                    endReason = getString(R.string.call_declined_from_notif)
                )
            }
            return
        }

        // Handle active or incoming call
        if (!incomingCallId.isNullOrBlank() && !incomingCallerId.isNullOrBlank()) {
            OpenChatNotificationManager.cancelIncomingCallNotification(this)
            val isAnswered = action == OpenChatNotificationManager.ACTION_ACCEPT_CALL ||
                    intent.getBooleanExtra("is_already_answered", false)

            if (isAnswered) {
                if (action == OpenChatNotificationManager.ACTION_ACCEPT_CALL) {
                    com.msi.openchat.service.call.OpenChatCallService.acceptCallService(
                        context = this,
                        callId = incomingCallId,
                        contactId = incomingCallerId,
                        contactName = incomingCallerName
                    )
                } else {
                    val uid = com.msi.openchat.data.remote.firebase.FirebaseManager.auth.currentUser?.uid
                        ?: ServiceLocator.sessionManager.getUserId()
                    if (!uid.isNullOrBlank()) {
                        ServiceLocator.callRepository.clearIncomingAudioCall(uid)
                    }
                }
                val activeIntent = com.msi.openchat.feature.calls.ActiveAudioCallActivity.createIntent(
                    context = this,
                    callId = incomingCallId,
                    contactId = incomingCallerId,
                    contactName = incomingCallerName,
                    isIncoming = true
                )
                startActivity(activeIntent)
            } else {
                val incomingIntent = com.msi.openchat.feature.calls.IncomingAudioCallActivity.createIntent(
                    context = this,
                    callId = incomingCallId,
                    callerId = incomingCallerId,
                    callerName = incomingCallerName
                )
                startActivity(incomingIntent)
            }
            return
        }

        // Handle conversation navigate
        val conversationId = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CONVERSATION_ID)
            ?: intent.getStringExtra("navigate_conversation_id")
        val contactName = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CONTACT_NAME)
            ?: intent.getStringExtra("contact_name")
            ?: getString(R.string.home_conversation_fallback)
        val contactId = intent.getStringExtra(OpenChatNotificationManager.EXTRA_CONTACT_ID)
            ?: intent.getStringExtra("contact_id")
            ?: ""

        val autoSendHi = intent.getBooleanExtra(OpenChatNotificationManager.EXTRA_AUTO_SEND_HI, false)
        if (!conversationId.isNullOrBlank()) {
            navigateToChat(
                conversationId = conversationId,
                contactName = contactName,
                contactId = contactId,
                autoSendHi = autoSendHi
            )
            return
        }

        // Handle navigation to Friend Requests screen
        val navigateFriendRequests = intent.getBooleanExtra(OpenChatNotificationManager.EXTRA_NAVIGATE_FRIEND_REQUESTS, false)
            || intent.getBooleanExtra("navigate_friend_requests", false)
        if (navigateFriendRequests) {
            navigateToFriendRequests()
            return
        }

        // Handle Deep Link URLs (openchat://... or https://openchat.app/...)
        val data: Uri? = intent.data
        if (data != null) {
            handleDeepLink(data)
        }
    }

    private fun handleDeepLink(uri: Uri) {
        val scheme = uri.scheme
        val host = uri.host

        val isCustomScheme = scheme == "openchat"
        val isWebScheme = scheme == "https" && host == "openchat.app"

        if (!isCustomScheme && !isWebScheme) return

        val path = uri.path.orEmpty()
        val isCall = host == "call" || path.contains("call")
        val isChat = host == "chat" || path.contains("chat")

        if (isCall) {
            val callId = uri.getQueryParameter("callId") ?: uri.getQueryParameter("id").orEmpty()
            val contactId = uri.getQueryParameter("contactId") ?: uri.getQueryParameter("callerId").orEmpty()
            val contactName = uri.getQueryParameter("contactName") ?: uri.getQueryParameter("name") ?: getString(R.string.call_service_notification_title)
            val callType = uri.getQueryParameter("type") ?: uri.getQueryParameter("callType") ?: "audio"

            if (contactId.isNotBlank()) {
                if (callType == "video") {
                    navigateToVideoCall(contactId, contactName)
                } else {
                    navigateToCall(
                        contactId = contactId,
                        contactName = contactName,
                        callId = callId,
                        isIncoming = callId.isNotBlank()
                    )
                }
            }
        } else if (isChat) {
            val convId = uri.getQueryParameter("conversationId") ?: uri.getQueryParameter("id").orEmpty()
            val contactName = uri.getQueryParameter("contactName") ?: uri.getQueryParameter("name") ?: getString(R.string.home_conversation_fallback)
            val contactId = uri.getQueryParameter("contactId").orEmpty()

            if (convId.isNotBlank()) {
                navigateToChat(
                    conversationId = convId,
                    contactName = contactName,
                    contactId = contactId
                )
            }
        }
    }
}
