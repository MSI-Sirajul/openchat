package com.msi.openchat.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Dedicated permission manager for OpenChat.
 * Centralizes permission checks, requests, and status logic for:
 * - Audio recording (voice messages & WebRTC calling)
 * - Push notifications (Android 13+ heads-up & background incoming call alerts)
 */
object PermissionManager {

    val REQUIRED_PERMISSIONS: List<String> by lazy {
        val list = mutableListOf<String>()
        list.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list
    }

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    fun hasMicrophonePermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.RECORD_AUDIO)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { !hasPermission(context, it) }
    }

    fun allPermissionsGranted(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    fun requestPermissions(activity: Activity, requestCode: Int = 1001) {
        val missing = getMissingPermissions(activity)
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, missing.toTypedArray(), requestCode)
        }
    }

    fun checkAndRequest(
        context: Context,
        launcher: ActivityResultLauncher<Array<String>>
    ) {
        val missing = getMissingPermissions(context)
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        }
    }
}
