package com.msi.openchat.core.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log

/**
 * AppServiceManager coordinates background execution, battery optimization exemption,
 * persistent service lifecycle, and synchronized call ringtone/vibration management.
 */
object AppServiceManager {
    private const val TAG = "OpenChat_ServiceManager"

    private var currentRingtone: Ringtone? = null
    private var isVibrating = false

    /**
     * Checks if the app is currently exempt from Android battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        }
        return true
    }

    /**
     * Safe no-op: respects Android's default system battery optimization policies without prompts.
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        // Respect system background execution limits; no exemption prompt
    }

    /**
     * Starts call ringtone and looping vibration for incoming calls.
     */
    @Synchronized
    fun startCallRinging(context: Context) {
        stopCallRinging(context)

        try {
            // 1. Play Default Ringtone
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val ringtone = RingtoneManager.getRingtone(context.applicationContext, ringtoneUri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ringtone?.audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            }
            ringtone?.play()
            currentRingtone = ringtone

            // 2. Start Looping Vibration
            val vibrationPattern = longArrayOf(0, 1000, 1000, 1000, 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(vibrationPattern, 1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(vibrationPattern, 1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(vibrationPattern, 1)
                }
            }
            isVibrating = true
            Log.d(TAG, "Started call ringing and vibration")
        } catch (e: Exception) {
            Log.e(TAG, "Error playing call ringtone/vibration: ${e.message}")
        }
    }

    /**
     * Safely stops incoming call ringtone and cancels vibration immediately.
     */
    @Synchronized
    fun stopCallRinging(context: Context) {
        try {
            currentRingtone?.let {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            currentRingtone = null

            if (isVibrating) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator?.cancel()
                } else {
                    @Suppress("DEPRECATION")
                    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    vibrator?.cancel()
                }
                isVibrating = false
            }
            Log.d(TAG, "Stopped call ringing and vibration")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping ringtone/vibration: ${e.message}")
        }
    }
}
