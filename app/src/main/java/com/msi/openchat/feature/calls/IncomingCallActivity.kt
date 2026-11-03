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
import com.example.MainActivity
import com.example.databinding.FragmentCallBinding
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.core.service.AppServiceManager
import com.msi.openchat.service.call.CallActionReceiver
import com.msi.openchat.service.notification.OpenChatNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen activity displayed when an incoming call is received.
 * Designed to awaken the screen, show above keyguard/lockscreen, play ringtones,
 * and provide instant, synchronized Answer and Decline capabilities.
 */
class IncomingCallActivity : AppCompatActivity() {
    private val tag = "OpenChat_IncomingCallUI"

    private var callId: String = ""
    private var callerId: String = ""
    private var callerName: String = "Incoming Call"

    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var timeoutJob: Job? = null
    private var dismissReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pulseAnimator: ObjectAnimator? = null

    private lateinit var binding: FragmentCallBinding

    companion object {
        const val EXTRA_CALL_ID = "incoming_call_id"
        const val EXTRA_CALLER_ID = "incoming_caller_id"
        const val EXTRA_CALLER_NAME = "incoming_caller_name"

        fun createIntent(
            context: Context,
            callId: String,
            callerId: String,
            callerName: String
        ): Intent {
            return Intent(context, IncomingCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALLER_ID, callerId)
                putExtra(EXTRA_CALLER_NAME, callerName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        callerId = intent.getStringExtra(EXTRA_CALLER_ID).orEmpty()
        callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: "OpenChat Caller"

        setupWindowAndLockScreen()
        acquireWakeLock()
        registerDismissReceiver()

        // 1. Start ringing & vibration
        AppServiceManager.startCallRinging(this)

        // 2. Notify caller that recipient device is confirmed to be RINGING
        confirmRingingStatus()

        // 3. Start 35s unanswered timeout
        startTimeoutTimer()

        // 4. Observe real-time call status to dismiss if caller cancels
        observeCallCancellation()

        binding = FragmentCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.textCallerName.text = callerName
        binding.textCallerAvatarInitial.text = callerName.take(1).uppercase()
        binding.textCallStatus.text = "Incoming Voice Call..."

        binding.layoutIncomingCallControls.visibility = View.VISIBLE
        binding.layoutActiveCallControls.visibility = View.GONE

        binding.fabAcceptIncoming.setOnClickListener {
            answerCall()
        }

        binding.fabDeclineIncoming.setOnClickListener {
            declineCall()
        }

        startPulseAnimation()
    }

    private fun startPulseAnimation() {
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringCallerPulse,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f, 1f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f, 1f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.7f, 0.2f, 0.7f)
        ).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun setupWindowAndLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            keyguardManager?.requestDismissKeyguard(this, null)
        }

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "OpenChat:IncomingCallWakeLock"
            )?.apply {
                acquire(40_000L) // 40 seconds max
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun registerDismissReceiver() {
        dismissReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == CallActionReceiver.ACTION_DISMISS_INCOMING_UI) {
                    val incomingId = intent.getStringExtra(EXTRA_CALL_ID)
                    if (incomingId == null || incomingId == callId) {
                        Log.i(tag, "Dismiss broadcast received for call: $callId")
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

    private fun confirmRingingStatus() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (callId.isNotBlank()) {
                    ServiceLocator.callRepository.updateCallStatus(callId, "ringing")
                    ServiceLocator.callRepository.sendSignaling(callId, callerId, "ringing", "{}")
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to confirm ringing status: ${e.message}")
            }
        }
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = activityScope.launch {
            delay(35_000L) // 35-second ring timeout
            Log.i(tag, "Incoming call timed out after 35 seconds. Marking as missed.")
            AppServiceManager.stopCallRinging(this@IncomingCallActivity)
            OpenChatNotificationManager.cancelIncomingCallNotification(this@IncomingCallActivity)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    ServiceLocator.callRepository.updateCallStatus(callId, "missed", endReason = "No answer")
                    ServiceLocator.callRepository.sendSignaling(callId, callerId, "bye", "{\"reason\":\"timeout\"}")
                } catch (_: Exception) {}
            }
            cleanupAndFinish()
        }
    }

    private fun observeCallCancellation() {
        activityScope.launch {
            ServiceLocator.callRepository.observeCallSession(callId).collect { session ->
                if (session.status in listOf("ended", "declined", "missed", "failed")) {
                    Log.i(tag, "Call was terminated by caller (${session.status}). Dismissing UI.")
                    cleanupAndFinish()
                }
            }
        }
    }

    private fun answerCall() {
        timeoutJob?.cancel()
        AppServiceManager.stopCallRinging(this)
        OpenChatNotificationManager.cancelIncomingCallNotification(this)

        // Launch MainActivity and navigate to CallScreen
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = OpenChatNotificationManager.ACTION_ACCEPT_CALL
            putExtra(OpenChatNotificationManager.EXTRA_CALL_ID, callId)
            putExtra(OpenChatNotificationManager.EXTRA_CALLER_ID, callerId)
            putExtra(OpenChatNotificationManager.EXTRA_CALLER_NAME, callerName)
            putExtra("is_already_answered", true)
        }
        startActivity(intent)
        cleanupAndFinish()
    }

    private fun declineCall() {
        timeoutJob?.cancel()
        AppServiceManager.stopCallRinging(this)
        OpenChatNotificationManager.cancelIncomingCallNotification(this)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceLocator.callRepository.updateCallStatus(callId, "declined", endReason = "Declined by user")
                ServiceLocator.callRepository.sendSignaling(callId, callerId, "bye", "{\"reason\":\"declined\"}")
            } catch (_: Exception) {}
        }
        cleanupAndFinish()
    }

    private fun cleanupAndFinish() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        AppServiceManager.stopCallRinging(this)
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanupAndFinish()
        try {
            dismissReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
    }
}
