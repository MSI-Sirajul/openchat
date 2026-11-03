package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.model.CallSession
import com.msi.openchat.data.model.Conversation
import com.msi.openchat.data.model.FriendRequest
import com.msi.openchat.data.model.Message
import com.msi.openchat.data.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenChatUnitTest {

    @Test
    fun testSessionManagerPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = SessionManager(context)

        sessionManager.clearSession()
        assertFalse(sessionManager.isAuthenticated())
        assertNull(sessionManager.getAccessToken())
        assertNull(sessionManager.getUserId())

        sessionManager.saveSession(
            accessToken = "test_access_token_12345",
            refreshToken = "test_refresh_token_67890",
            userId = "uuid-1234-5678",
            email = "user@example.com"
        )

        assertTrue(sessionManager.isAuthenticated())
        assertEquals("test_access_token_12345", sessionManager.getAccessToken())
        assertEquals("test_refresh_token_67890", sessionManager.getRefreshToken())
        assertEquals("uuid-1234-5678", sessionManager.getUserId())
        assertEquals("user@example.com", sessionManager.getUserEmail())

        sessionManager.clearSession()
        assertFalse(sessionManager.isAuthenticated())
    }

    @Test
    fun testProfileDataModel() {
        val profile = Profile(
            id = "user-1",
            username = "johndoe",
            displayName = "John Doe",
            bio = "OpenChat enthusiast",
            onlineStatus = "online"
        )

        assertEquals("user-1", profile.id)
        assertEquals("johndoe", profile.username)
        assertEquals("John Doe", profile.displayName)
        assertEquals("online", profile.onlineStatus)
        assertTrue(profile.showLastSeen)
        assertTrue(profile.showReadReceipts)
    }

    @Test
    fun testMessageDataModel() {
        val textMessage = Message(
            id = "msg-1",
            conversationId = "conv-1",
            senderId = "user-1",
            type = "text",
            content = "Hello OpenChat!",
            status = "delivered"
        )

        assertEquals("text", textMessage.type)
        assertEquals("Hello OpenChat!", textMessage.content)

        val voiceMessage = Message(
            id = "msg-2",
            conversationId = "conv-1",
            senderId = "user-2",
            type = "audio",
            mediaUrl = "user-2/voice_123.m4a",
            mediaDurationSeconds = 7,
            status = "sent"
        )

        assertEquals("audio", voiceMessage.type)
        assertEquals(7, voiceMessage.mediaDurationSeconds)
        assertNotNull(voiceMessage.mediaUrl)
    }

    @Test
    fun testCallSessionDataModel() {
        val call = CallSession(
            id = "call-123",
            callerId = "user-1",
            receiverId = "user-2",
            callType = "audio",
            status = "connected",
            durationSeconds = 45
        )

        assertEquals("call-123", call.id)
        assertEquals("user-1", call.callerId)
        assertEquals("user-2", call.receiverId)
        assertEquals("connected", call.status)
        assertEquals(45, call.durationSeconds)
    }

    @Test
    fun testWebRtcNetworkStatsModel() {
        val stats = com.msi.openchat.service.call.CallNetworkStats(
            rttMs = 45,
            packetsLost = 1,
            jitterMs = 4,
            quality = com.msi.openchat.service.call.NetworkQuality.EXCELLENT
        )

        assertEquals(45, stats.rttMs)
        assertEquals(1, stats.packetsLost)
        assertEquals(4, stats.jitterMs)
        assertEquals(com.msi.openchat.service.call.NetworkQuality.EXCELLENT, stats.quality)
    }

    @Test
    fun testNativeLayoutInflations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inflater = android.view.LayoutInflater.from(context)

        val convBinding = com.example.databinding.ItemConversationBinding.inflate(inflater)
        assertNotNull(convBinding.root)
        assertNotNull(convBinding.textName)
        assertNotNull(convBinding.textLastMessage)

        val callBinding = com.example.databinding.ItemCallHistoryBinding.inflate(inflater)
        assertNotNull(callBinding.root)
        assertNotNull(callBinding.textContactName)
        assertNotNull(callBinding.btnCallback)

        val friendBinding = com.example.databinding.ItemFriendBinding.inflate(inflater)
        assertNotNull(friendBinding.root)
        assertNotNull(friendBinding.textDisplayName)

        val msgBinding = com.example.databinding.ItemChatMessageBinding.inflate(inflater)
        assertNotNull(msgBinding.root)
        assertNotNull(msgBinding.textMessageBody)
    }
}
