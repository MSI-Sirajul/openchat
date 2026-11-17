package com.msi.openchat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.msi.openchat.core.common.Constants
import com.msi.openchat.feature.videocall.VideoCallActivity
import com.msi.openchat.feature.videocall.VideoCallService
import com.msi.openchat.service.call.OpenChatCallService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotificationIsolationTest {

    @Test
    fun `audio and video PendingIntent request codes are strictly isolated`() {
        val sampleIds = listOf("aud_123", "vid_123", "same_hash_id", "call_abc_xyz")
        for (id in sampleIds) {
            val codes = listOf(
                Constants.audioAcceptRequestCode(id),
                Constants.audioDeclineRequestCode(id),
                Constants.audioFullScreenRequestCode(id),
                Constants.REQ_AUDIO_ONGOING_CONTENT,
                Constants.REQ_AUDIO_ONGOING_END,
                Constants.REQ_AUDIO_ONGOING_MUTE,
                Constants.REQ_AUDIO_ONGOING_SPEAKER,
                Constants.videoAcceptRequestCode(id),
                Constants.videoDeclineRequestCode(id),
                Constants.videoFullScreenRequestCode(id),
                Constants.REQ_VIDEO_ONGOING_CONTENT,
                Constants.REQ_VIDEO_ONGOING_END,
                Constants.REQ_VIDEO_ONGOING_MUTE,
                Constants.REQ_VIDEO_ONGOING_SWITCH_CAM,
                Constants.REQ_VIDEO_PIP_MUTE,
                Constants.REQ_VIDEO_PIP_SWITCH_CAM,
                Constants.REQ_VIDEO_PIP_END
            )
            assertEquals("Duplicate request codes detected for $id", codes.size, codes.toSet().size)
        }
    }

    @Test
    fun `audio accept intent targets OpenChatCallService directly without Activity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = OpenChatCallService.createAcceptIntent(
            context = context,
            callId = "aud_call_42",
            contactId = "peer_7",
            contactName = "Alice"
        )
        assertEquals(OpenChatCallService::class.java.name, intent.component?.className)
        assertEquals(OpenChatCallService.ACTION_ACCEPT_CALL, intent.action)
        assertEquals("aud_call_42", intent.getStringExtra(OpenChatCallService.EXTRA_CALL_ID))
        assertEquals("peer_7", intent.getStringExtra(OpenChatCallService.EXTRA_CONTACT_ID))
    }

    @Test
    fun `video accept intent launches VideoCallActivity with autoAccept extras`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = VideoCallActivity.createAutoAcceptIntent(
            context = context,
            callId = "vid_call_99",
            peerUserId = "peer_12",
            peerName = "Bob"
        )
        assertEquals(VideoCallActivity::class.java.name, intent.component?.className)
        assertEquals("vid_call_99", intent.getStringExtra("callId"))
        assertEquals("peer_12", intent.getStringExtra("peerUserId"))
        assertTrue(intent.getBooleanExtra("autoAccept", false))
    }

    @Test
    fun `video service bind intent targets VideoCallService`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = VideoCallService.bindIntent(context)
        assertEquals(VideoCallService::class.java.name, intent.component?.className)
    }
}
