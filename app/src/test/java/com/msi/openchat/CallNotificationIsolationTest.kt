package com.msi.openchat

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.example.MainActivity
import com.example.R
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.msi.openchat.core.common.Constants
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.feature.calls.ActiveAudioCallActivity
import com.msi.openchat.feature.calls.CallUiState
import com.msi.openchat.feature.videocall.ActiveVideoCallActivity
import com.msi.openchat.feature.videocall.OutgoingVideoCallActivity
import com.msi.openchat.feature.videocall.VideoCallActivity
import com.msi.openchat.feature.videocall.VideoCallManager
import com.msi.openchat.feature.videocall.VideoCallService
import com.msi.openchat.feature.videocall.VideoCallStatus
import com.msi.openchat.feature.videocall.VideoCallUiState
import com.msi.openchat.service.call.CallSessionCoordinator
import com.msi.openchat.service.call.CallSessionGuard
import com.msi.openchat.service.call.OpenChatCallService
import com.msi.openchat.service.call.WebRtcCallState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CallNotificationIsolationTest {

    @After
    fun tearDown() {
        CallSessionCoordinator.unregisterActiveCall(null)
        CallSessionGuard.unregisterCall(null)
        try {
            ServiceLocator.sessionManager.clearSession()
        } catch (_: Exception) {}
    }

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
    fun `CallSessionGuard accurately isolates audio_call and aud prefixes from video prefixes`() {
        assertTrue(CallSessionGuard.isAudioCallId("audio_call_123456"))
        assertTrue(CallSessionGuard.isAudioCallId("aud_abcdef"))
        assertFalse(CallSessionGuard.isAudioCallId("vid_123456"))
        assertFalse(CallSessionGuard.isAudioCallId("video_call_123456"))
        assertFalse(CallSessionGuard.isAudioCallId(""))
        assertFalse(CallSessionGuard.isAudioCallId(null))

        assertTrue(CallSessionGuard.isVideoCall("vid_123", null))
        assertTrue(CallSessionGuard.isVideoCall("video_call_123", null))
        assertFalse(CallSessionGuard.isVideoCall("audio_call_123", "audio"))
    }

    @Test
    fun `CallSessionCoordinator registerService preserves CONNECTED state on re-attach`() {
        val service = OpenChatCallService()
        val callId = "audio_call_reattach_1"
        CallSessionCoordinator.registerService(service, callId, "peer_1", "Alice")
        CallSessionCoordinator.updateUiState(
            CallUiState(
                callId = callId,
                contactId = "peer_1",
                contactName = "Alice",
                isCaller = true,
                connectionState = WebRtcCallState.CONNECTED,
                callStatus = "Connected",
                durationSeconds = 45,
                isCallActive = true
            )
        )
        assertTrue(CallSessionCoordinator.isActive())
        assertEquals(WebRtcCallState.CONNECTED, CallSessionCoordinator.callState.value)

        CallSessionCoordinator.registerService(service, callId, "peer_1", "Alice")
        assertEquals(WebRtcCallState.CONNECTED, CallSessionCoordinator.callState.value)
        assertTrue(CallSessionCoordinator.isActive())
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
    fun `resume intents include reorder flag and target expected call activities`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioResume = ActiveAudioCallActivity.createResumeIntent(
            context = context,
            callId = "audio_call_1",
            contactId = "peer_1",
            contactName = "Alice",
            isIncoming = true
        )
        assertEquals(ActiveAudioCallActivity::class.java.name, audioResume.component?.className)
        assertTrue((audioResume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0)
        assertTrue(audioResume.getBooleanExtra(ActiveAudioCallActivity.EXTRA_IS_INCOMING, false))

        val outgoingVideoResume = OutgoingVideoCallActivity.createResumeIntent(context, "peer_2", "Bob")
        assertEquals(OutgoingVideoCallActivity::class.java.name, outgoingVideoResume.component?.className)
        assertTrue((outgoingVideoResume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0)
        assertEquals("peer_2", outgoingVideoResume.getStringExtra(OutgoingVideoCallActivity.EXTRA_PEER_ID))

        val activeVideoResume = ActiveVideoCallActivity.createResumeIntent(context)
        assertEquals(ActiveVideoCallActivity::class.java.name, activeVideoResume.component?.className)
        assertTrue((activeVideoResume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0)

        val videoResume = VideoCallActivity.createResumeIntent(context)
        assertEquals(VideoCallActivity::class.java.name, videoResume.component?.className)
        assertTrue((videoResume.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0)
    }

    @Test
    fun `video service bind intent targets VideoCallService`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = VideoCallService.bindIntent(context)
        assertEquals(VideoCallService::class.java.name, intent.component?.className)
    }

    @Test
    fun `MainActivity active call banner displays live audio and video calls and redirects on click`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:abcdef123456")
                    .setApiKey("AIzaSyTestKeyForRobolectricUnitTestsOnly")
                    .setProjectId("openchat-test-project")
                    .setDatabaseUrl("https://openchat-test-project-default-rtdb.firebaseio.com")
                    .build()
            )
        }
        ServiceLocator.initialize(context)
        ServiceLocator.sessionManager.saveSession(
            accessToken = "test_token",
            refreshToken = "test_refresh",
            userId = "user_me",
            email = "me@openchat.test"
        )
        ServiceLocator.sessionManager.setPermissionOnboardingCompleted(true)

        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()

        val banner = activity.findViewById<LinearLayout>(R.id.layout_active_call_banner)
        val titleView = activity.findViewById<TextView>(R.id.text_active_call_title)
        val durationView = activity.findViewById<TextView>(R.id.text_active_call_duration)

        // Initially hidden when no active call
        assertEquals(View.GONE, banner.visibility)

        // 1. Active audio call renders banner & redirects to ActiveAudioCallActivity on click
        val activeAudioState = CallUiState(
            callId = "audio_call_abc",
            contactId = "peer_alice",
            contactName = "Alice",
            isCaller = false,
            isIncomingWaiting = false,
            connectionState = WebRtcCallState.CONNECTED,
            callStatus = "Connected",
            durationSeconds = 65,
            isCallActive = true
        )
        CallSessionCoordinator.updateUiState(activeAudioState)
        activity.renderActiveCallBanner(activeAudioState, VideoCallUiState())

        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals("Voice Call • Alice", titleView.text.toString())
        assertEquals("01:05", durationView.text.toString())

        banner.performClick()
        val startedAudio = shadowOf(activity).nextStartedActivity
        assertNotNull(startedAudio)
        assertEquals(ActiveAudioCallActivity::class.java.name, startedAudio.component?.className)
        assertEquals("audio_call_abc", startedAudio.getStringExtra(ActiveAudioCallActivity.EXTRA_CALL_ID))

        // 2. Active video call renders banner & redirects to ActiveVideoCallActivity on click
        CallSessionCoordinator.unregisterActiveCall(null)
        VideoCallManager.resumeActivityClassName = ActiveVideoCallActivity::class.java.name
        val activeVideoState = VideoCallUiState(
            status = VideoCallStatus.Connected("vid_xyz", "Bob", durationSeconds = 125),
            callId = "vid_xyz",
            peerId = "peer_bob",
            peerName = "Bob",
            durationSeconds = 125,
            formattedDuration = "02:05"
        )
        activity.renderActiveCallBanner(CallUiState(isCallActive = false), activeVideoState)

        assertEquals(View.VISIBLE, banner.visibility)
        assertEquals("Video Call • Bob", titleView.text.toString())
        assertEquals("02:05", durationView.text.toString())

        activity.openActiveCallScreen(CallUiState(isCallActive = false), activeVideoState)
        val startedVideo = shadowOf(activity).nextStartedActivity
        assertNotNull(startedVideo)
        assertEquals(ActiveVideoCallActivity::class.java.name, startedVideo.component?.className)

        // 3. Ended call hides banner again
        activity.renderActiveCallBanner(
            CallUiState(isCallActive = false, connectionState = WebRtcCallState.ENDED),
            VideoCallUiState(status = VideoCallStatus.Ended("Call ended"))
        )
        assertEquals(View.GONE, banner.visibility)
    }
}
