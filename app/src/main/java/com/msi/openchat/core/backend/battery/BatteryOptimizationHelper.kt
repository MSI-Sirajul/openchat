package com.msi.openchat.core.backend.battery

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Helper to prompt users for battery optimization exemption
 * so OpenChat's foreground keep-alive service can maintain real-time connections
 * without aggressive OS termination.
 */
object BatteryOptimizationHelper {
    private const val TAG = "BatteryOptimization"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else {
            true
        }
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isIgnoringBatteryOptimizations(context)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Log.i(TAG, "Requested ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
            } catch (e: Exception) {
                Log.w(TAG, "Direct battery optimization request failed, opening battery settings: ${e.message}")
                try {
                    val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallbackIntent)
                } catch (fallbackEx: Exception) {
                    Log.e(TAG, "Cannot open battery optimization settings: ${fallbackEx.message}")
                }
            }
        }
    }
}
