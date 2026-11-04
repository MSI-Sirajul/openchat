package com.msi.openchat.core.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.DrawableRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.msi.openchat.core.di.ServiceLocator

/**
 * Represents a single permission entry for display on the permission onboarding screen.
 */
data class PermissionItemState(
    val permission: String,
    val title: String,
    val description: String,
    @DrawableRes val iconRes: Int,
    val isGranted: Boolean,
    val isHandled: Boolean = isGranted
)

/**
 * Dedicated permission manager for OpenChat.
 * Centralizes permission checks, requests, onboarding items, and status logic for:
 * - Audio recording (voice messages & WebRTC calling)
 * - Push notifications (Android 13+ heads-up & background incoming call alerts)
 * - Camera (taking photos & video calls)
 * - Storage & media access
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

    fun hasCameraPermission(context: Context): Boolean {
        return hasPermission(context, Manifest.permission.CAMERA)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            true
        }
    }

    fun getMediaPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    fun hasMediaPermission(context: Context): Boolean {
        return hasPermission(context, getMediaPermission())
    }

    fun getMissingPermissions(context: Context): List<String> {
        return REQUIRED_PERMISSIONS.filter { !hasPermission(context, it) }
    }

    fun allPermissionsGranted(context: Context): Boolean {
        return getMissingPermissions(context).isEmpty()
    }

    /**
     * Determines if the permission onboarding screen should be shown.
     * Persisted onboarding completion prevents re-showing unless permissions are missing.
     */
    fun shouldShowOnboarding(context: Context): Boolean {
        val sessionManager = ServiceLocator.sessionManager
        val isCompleted = sessionManager.isPermissionOnboardingCompleted()
        if (!isCompleted) return true
        return getMissingPermissions(context).isNotEmpty()
    }

    /**
     * Constructs the list of permissions to present on the onboarding screen.
     */
    fun getOnboardingPermissions(context: Context): List<PermissionItemState> {
        val list = mutableListOf<PermissionItemState>()

        // 1. Push Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = hasNotificationPermission(context)
            list.add(
                PermissionItemState(
                    permission = Manifest.permission.POST_NOTIFICATIONS,
                    title = context.getString(R.string.permissions_notifications_title),
                    description = context.getString(R.string.permissions_notifications_desc),
                    iconRes = R.drawable.ic_chat_24,
                    isGranted = isGranted,
                    isHandled = isGranted
                )
            )
        }

        // 2. Microphone
        val hasMic = hasMicrophonePermission(context)
        list.add(
            PermissionItemState(
                permission = Manifest.permission.RECORD_AUDIO,
                title = context.getString(R.string.permissions_microphone_title),
                description = context.getString(R.string.permissions_microphone_desc),
                iconRes = R.drawable.ic_mic_24,
                isGranted = hasMic,
                isHandled = hasMic
            )
        )

        // 3. Camera
        val hasCam = hasCameraPermission(context)
        list.add(
            PermissionItemState(
                permission = Manifest.permission.CAMERA,
                title = context.getString(R.string.permissions_camera_title),
                description = context.getString(R.string.permissions_camera_desc),
                iconRes = R.drawable.ic_video_24,
                isGranted = hasCam,
                isHandled = hasCam
            )
        )

        // 4. Storage & Media
        val mediaPerm = getMediaPermission()
        val hasMedia = hasPermission(context, mediaPerm)
        list.add(
            PermissionItemState(
                permission = mediaPerm,
                title = context.getString(R.string.permissions_media_title),
                description = context.getString(R.string.permissions_media_desc),
                iconRes = R.drawable.ic_image_24,
                isGranted = hasMedia,
                isHandled = hasMedia
            )
        )

        return list
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
