package com.msi.openchat.feature.videocall

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import android.view.View
import androidx.annotation.RequiresApi
import com.example.R
import com.msi.openchat.core.common.Constants

/**
 * Shared Picture-in-Picture configuration helper for video call Activities.
 * Uses a 16:9 aspect ratio, sourceRectHint anchored to remoteVideoView, and
 * isolated RemoteActions (Mute/Unmute, Switch Camera, End Call) routed via VideoCallActionReceiver.
 */
object VideoCallPipHelper {
    private const val TAG = "OpenChat_VideoPip"

    fun buildParams(
        context: Context,
        remoteVideoView: View,
        isMuted: Boolean
    ): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return buildParamsO(context, remoteVideoView, isMuted)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildParamsO(
        context: Context,
        remoteVideoView: View,
        isMuted: Boolean
    ): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        val sourceRect = Rect()
        if (remoteVideoView.isShown && remoteVideoView.getGlobalVisibleRect(sourceRect) && !sourceRect.isEmpty) {
            builder.setSourceRectHint(sourceRect)
        }

        val muteIntent = Intent(context, VideoCallActionReceiver::class.java).apply {
            action = VideoCallActionReceiver.ACTION_TOGGLE_MUTE_VIDEO_CALL
        }
        val mutePendingIntent = PendingIntent.getBroadcast(
            context,
            Constants.REQ_VIDEO_PIP_MUTE,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val muteAction = RemoteAction(
            Icon.createWithResource(context, if (isMuted) R.drawable.ic_mic_off_24 else R.drawable.ic_mic_24),
            context.getString(if (isMuted) R.string.call_unmute else R.string.call_mute),
            context.getString(if (isMuted) R.string.call_unmute_mic_cd else R.string.call_mute_mic_cd),
            mutePendingIntent
        )

        val switchCamIntent = Intent(context, VideoCallActionReceiver::class.java).apply {
            action = VideoCallActionReceiver.ACTION_SWITCH_CAMERA_VIDEO_CALL
        }
        val switchCamPendingIntent = PendingIntent.getBroadcast(
            context,
            Constants.REQ_VIDEO_PIP_SWITCH_CAM,
            switchCamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val switchCamAction = RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_cameraswitch_24),
            context.getString(R.string.call_video_pip_flip_camera),
            context.getString(R.string.call_video_pip_flip_camera_desc),
            switchCamPendingIntent
        )

        val endIntent = Intent(context, VideoCallActionReceiver::class.java).apply {
            action = VideoCallActionReceiver.ACTION_END_VIDEO_CALL
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            Constants.REQ_VIDEO_PIP_END,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val endAction = RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_call_end_24),
            context.getString(R.string.call_end),
            context.getString(R.string.call_video_pip_end_desc),
            endPendingIntent
        )

        builder.setActions(listOf(muteAction, switchCamAction, endAction))
        return builder.build()
    }

    fun enterPip(
        activity: Activity,
        remoteVideoView: View,
        isMuted: Boolean
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return try {
            val params = buildParams(activity, remoteVideoView, isMuted) ?: return false
            activity.enterPictureInPictureMode(params)
        } catch (e: Exception) {
            Log.w(TAG, "enterPip failed: ${e.message}")
            false
        }
    }

    fun updateParamsIfInPip(
        activity: Activity,
        remoteVideoView: View,
        isMuted: Boolean
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!activity.isInPictureInPictureMode) return
        try {
            val params = buildParams(activity, remoteVideoView, isMuted) ?: return
            activity.setPictureInPictureParams(params)
        } catch (e: Exception) {
            Log.w(TAG, "setPictureInPictureParams failed: ${e.message}")
        }
    }
}
