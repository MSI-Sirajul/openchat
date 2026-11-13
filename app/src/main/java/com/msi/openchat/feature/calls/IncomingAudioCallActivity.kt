package com.msi.openchat.feature.calls

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.databinding.ActivityIncomingAudioCallBinding
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.service.call.CallActionReceiver
import com.msi.openchat.service.call.CallSessionGuard
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen edge-to-edge Activity for incoming voice calls.
 * Displays over keyguard/lock screen, rings, and handles Accept/Decline.
 */
class IncomingAudioCallActivity : AppCompatActivity() {
    private val tag = "OpenChat_IncomingAudio"

    private lateinit var binding: ActivityIncomingAudioCallBinding
    private var callId: String = ""
    private var callerId: String = ""
    private var callerName: String = "Incoming Voice Call"

    private var pulseAnimator: ObjectAnimator? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val EXTRA_CALL_ID = "incoming_audio_call_id"
        const val EXTRA_CALLER_ID = "incoming_audio_caller_id"
        const val EXTRA_CALLER_NAME = "incoming_audio_caller_name"

        fun createIntent(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String
        ): Intent {
            return Intent(context, IncomingAudioCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName)
            }
        }
    }

    private var isHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowAndLockScreen()

        binding = ActivityIncomingAudioCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "OpenChat Caller"

        if (callId.isBlank() ||
            CallSessionGuard.isVideoCall(callId, null) ||
            CallSessionGuard.isVideoCallActive() ||
            VideoCallManager.isCallActive()
        ) {
            finish()
            return
        }

        setupEdgeToEdge()
        acquireWakeLock()
        registerDismissReceiver()

        // 1. Ring & vibrate
        AppServiceManager.startCallRinging(this)

        // 2. Pulse animation
        startPulseAnimation()

        // 3. Populate caller details
        binding.textCallerName.text = callerName
        binding.textCallerInitial.text = callerName.take(1).uppercase()

        // 4. Buttons
        binding.btnAccept.setOnClickListener {
            answerCall()
        }

        binding.btnDecline.setOnClickListener {
            declineCall()
        }

        // 5. Observe call session in RTDB
        observeCallSession()
    }

    private fun setupWindowAndLockScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootIncomingAudio) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutTopCapsule.updatePadding(top = systemBars.top)
            binding.layoutActions.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "OpenChat:IncomingAudioWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(40_000L)
            }
        } catch (e: Exception) {
            Log.w(tag, "WakeLock acquire error: ${e.message}")
        }
    }

    private fun registerDismissReceiver() {
        dismissReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == CallActionReceiver.ACTION_DISMISS_INCOMING_UI) {
                    val targetId = intent.getStringExtra(CallActionReceiver.EXTRA_CALL_ID).orEmpty()
                    if (targetId.isBlank() || targetId == callId) {
                        cleanupAndFinish()
                    }
                }
            }
        }
        val filter = IntentFilter(CallActionReceiver.ACTION_DISMISS_INCOMING_UI)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(dismissReceiver, filter)
        }
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0.15f, 0.7f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun observeCallSession() {
        lifecycleScope.launch {
            ServiceLocator.callRepository.observeCallSession(callId).collect { session ->
                if (session.status in listOf("ended", "declined", "missed", "failed")) {
                    cleanupAndFinish()
                }
            }
        }
    }

    private fun answerCall() {
        if (isHandled) return
        isHandled = true
        AppServiceManager.stopCallRinging(this)
        OpenChatNotificationManager.cancelIncomingCallNotification(this)

        val currentUid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
        if (!currentUid.isNullOrBlank()) {
            ServiceLocator.callRepository.clearIncomingAudioCall(currentUid)
        }

        val intent = ActiveAudioCallActivity.createIntent(
            context = this,
            callId = callId,
            contactId = callerId,
            contactName = callerName,
            isIncoming = true
        )
        startActivity(intent)
        cleanupAndFinish()
    }

    private fun declineCall() {
        if (isHandled) return
        isHandled = true
        AppServiceManager.stopCallRinging(this)
        OpenChatNotificationManager.cancelIncomingCallNotification(this)

        val currentUid = FirebaseManager.auth.currentUser?.uid ?: ServiceLocator.sessionManager.getUserId()
        if (!currentUid.isNullOrBlank()) {
            ServiceLocator.callRepository.clearIncomingAudioCall(currentUid)
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.callRepository.updateCallStatus(callId = callId, status = "declined", endReason = "Declined by user")
                ServiceLocator.callRepository.sendSignaling(callId, callerId, "bye", "{\"reason\":\"declined\"}")
            } catch (e: Exception) {
                Log.e(tag, "Error declining audio call: ${e.message}")
            }
        }
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        AppServiceManager.stopCallRinging(this)
        pulseAnimator?.cancel()
        dismissReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            dismissReceiver = null
        }
        wakeLock?.let {
            try { if (it.isHeld) it.release() } catch (_: Exception) {}
            wakeLock = null
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndFinish()
    }
}
