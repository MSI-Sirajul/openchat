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
@Config(sdk = [34])
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

    @Test
    fun testPermissionOnboardingFlow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.msi.openchat.core.di.ServiceLocator.initialize(context)

        val sessionManager = com.msi.openchat.core.di.ServiceLocator.sessionManager
        sessionManager.setPermissionOnboardingCompleted(false)
        assertFalse(sessionManager.isPermissionOnboardingCompleted())

        // Should show onboarding because not yet completed
        assertTrue(com.msi.openchat.core.permission.PermissionManager.shouldShowOnboarding(context))

        val permissions = com.msi.openchat.core.permission.PermissionManager.getOnboardingPermissions(context)
        assertTrue(permissions.isNotEmpty())
        assertTrue(permissions.any { it.permission == android.Manifest.permission.RECORD_AUDIO })

        // Test ViewModel
        val viewModel = com.msi.openchat.feature.permission.PermissionOnboardingViewModel()
        viewModel.loadPermissions(context)

        val initialState = viewModel.uiState.value
        assertEquals(permissions.size, initialState.items.size)
        assertFalse(initialState.isCompleted)

        // Tapping a checkbox updates the permission
        viewModel.onPermissionResult(android.Manifest.permission.RECORD_AUDIO, true)
        val updatedState = viewModel.uiState.value
        assertTrue(updatedState.isDoneEnabled)
        assertTrue(updatedState.hasInteracted)
        val micItem = updatedState.items.find { it.permission == android.Manifest.permission.RECORD_AUDIO }
        assertNotNull(micItem)
        assertTrue(micItem!!.isGranted)

        // Done finishes onboarding and marks persisted
        viewModel.onDoneClicked()
        assertTrue(viewModel.uiState.value.isCompleted)
        assertTrue(sessionManager.isPermissionOnboardingCompleted())
    }

    @Test
    fun testPermissionItemInflation() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(baseContext, com.example.R.style.Theme_MyApplication)
        val inflater = android.view.LayoutInflater.from(themedContext)

        val itemBinding = com.example.databinding.ItemPermissionOnboardingBinding.inflate(inflater)
        assertNotNull(itemBinding.root)
        assertNotNull(itemBinding.textPermissionTitle)
        assertNotNull(itemBinding.textPermissionDesc)
        assertNotNull(itemBinding.cbPermission)
        assertNotNull(itemBinding.layoutPermissionRow)

        val fragmentBinding = com.example.databinding.FragmentPermissionOnboardingBinding.inflate(inflater)
        assertNotNull(fragmentBinding.root)
        assertNotNull(fragmentBinding.recyclerPermissions)
        assertNotNull(fragmentBinding.btnDonePermissions)
        assertNotNull(fragmentBinding.btnSkipPermissions)
    }

    @Test
    fun testPermissionOnboardingSkip() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.msi.openchat.core.di.ServiceLocator.initialize(context)

        val sessionManager = com.msi.openchat.core.di.ServiceLocator.sessionManager
        sessionManager.setPermissionOnboardingCompleted(false)

        val viewModel = com.msi.openchat.feature.permission.PermissionOnboardingViewModel()
        viewModel.loadPermissions(context)

        // Skip immediately proceeds without granting
        viewModel.onSkipClicked()
        assertTrue(viewModel.uiState.value.isCompleted)
        assertTrue(sessionManager.isPermissionOnboardingCompleted())
    }

    @Test
    fun testLogoutClearsSessionAndState() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.msi.openchat.core.di.ServiceLocator.initialize(context)
        val sessionManager = com.msi.openchat.core.di.ServiceLocator.sessionManager

        sessionManager.saveSession("token_123", "refresh_456", "user_789", "test@openchat.app")
        sessionManager.saveFullCredentials("test@openchat.app", "pass123", "test@openchat.app", "user_789")
        sessionManager.secureStorage.setRememberMeEnabled(true)
        assertTrue(sessionManager.isAuthenticated())
        assertTrue(sessionManager.hasSecureCredentials())
        assertTrue(sessionManager.secureStorage.isRememberMeEnabled())

        com.msi.openchat.core.di.ServiceLocator.authRepository.logout()

        assertFalse(sessionManager.isAuthenticated())
        assertFalse(sessionManager.hasSecureCredentials())
        assertFalse(sessionManager.secureStorage.isRememberMeEnabled())
        assertNull(sessionManager.getAccessToken())
        assertNull(sessionManager.getUserId())
        assertEquals(
            com.msi.openchat.core.security.AuthState.LoggedOut,
            com.msi.openchat.core.di.ServiceLocator.authRepository.authState.value
        )
    }

    @Test
    fun testRelationshipStatusAndSearchItemModel() {
        val userItemNone = com.msi.openchat.core.ui.adapter.UserSearchItem(
            profile = Profile(id = "user-1", username = "alice", displayName = "Alice"),
            status = com.msi.openchat.core.ui.adapter.RelationshipStatus.NONE
        )
        assertFalse(userItemNone.isAlreadyFriend)
        assertFalse(userItemNone.isPendingRequest)
        assertEquals("user-1", userItemNone.profile.id)

        val userItemRequested = com.msi.openchat.core.ui.adapter.UserSearchItem(
            profile = Profile(id = "user-2", username = "bob", displayName = "Bob"),
            status = com.msi.openchat.core.ui.adapter.RelationshipStatus.REQUESTED
        )
        assertFalse(userItemRequested.isAlreadyFriend)
        assertTrue(userItemRequested.isPendingRequest)

        val userItemFriends = com.msi.openchat.core.ui.adapter.UserSearchItem(
            profile = Profile(id = "user-3", username = "charlie", displayName = "Charlie"),
            status = com.msi.openchat.core.ui.adapter.RelationshipStatus.FRIENDS
        )
        assertTrue(userItemFriends.isAlreadyFriend)
        assertFalse(userItemFriends.isPendingRequest)
    }

    @Test
    fun testFriendSearchItemInflationAndButtonStates() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        val themedContext = androidx.appcompat.view.ContextThemeWrapper(baseContext, com.example.R.style.Theme_MyApplication)
        val inflater = android.view.LayoutInflater.from(themedContext)

        val itemBinding = com.example.databinding.ItemUserSearchBinding.inflate(inflater)
        assertNotNull(itemBinding.root)
        assertNotNull(itemBinding.textUserName)
        assertNotNull(itemBinding.textUserHandle)
        assertNotNull(itemBinding.btnActionFriend)
    }

    @Test
    fun testFriendNotificationChannelsInitialization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.msi.openchat.service.notification.OpenChatNotificationManager.initChannels(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channelA = manager.getNotificationChannel(com.msi.openchat.core.common.Constants.CHANNEL_FRIEND_REQUESTS)
            assertNotNull(channelA)
            assertEquals("Friend Requests", channelA.name)
            assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channelA.importance)

            val channelB = manager.getNotificationChannel(com.msi.openchat.core.common.Constants.CHANNEL_FRIEND_ACCEPTED)
            assertNotNull(channelB)
            assertEquals("Friend Accepted", channelB.name)
            assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH, channelB.importance)
        }
    }

    @Test
    fun testFriendActionReceiverIntents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val receiver = com.msi.openchat.service.notification.FriendActionReceiver()

        // Cancel friend request intent
        val cancelIntent = android.content.Intent(com.msi.openchat.service.notification.FriendActionReceiver.ACTION_CANCEL_FRIEND_REQUEST).apply {
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_REQUEST_ID, "req-123")
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_SENDER_ID, "sender-456")
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_NOTIFICATION_ID, 3050)
        }
        receiver.onReceive(context, cancelIntent)

        // Accept friend request intent
        val acceptIntent = android.content.Intent(com.msi.openchat.service.notification.FriendActionReceiver.ACTION_ACCEPT_FRIEND_REQUEST).apply {
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_REQUEST_ID, "req-123")
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_SENDER_ID, "sender-456")
            putExtra(com.msi.openchat.service.notification.FriendActionReceiver.EXTRA_NOTIFICATION_ID, 3050)
        }
        receiver.onReceive(context, acceptIntent)
    }

    @Test
    fun testUserScopedDatabaseIsolation() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbNameA = com.msi.openchat.data.local.OpenChatDatabase.getDatabaseNameForUser("user_alpha")
        val dbNameB = com.msi.openchat.data.local.OpenChatDatabase.getDatabaseNameForUser("user_beta")

        assertTrue(dbNameA.contains("user_alpha"))
        assertTrue(dbNameB.contains("user_beta"))
        org.junit.Assert.assertNotEquals(dbNameA, dbNameB)

        val dbA = com.msi.openchat.data.local.OpenChatDatabase.getInstance(context, "user_alpha")
        val dbB = com.msi.openchat.data.local.OpenChatDatabase.getInstance(context, "user_beta")

        val convA = com.msi.openchat.data.local.entity.ConversationEntity(
            id = "conv-alpha-1",
            type = "direct",
            userId = "user_alpha",
            participant1Id = "user_alpha",
            participant2Id = "user_target",
            otherUserId = "user_target",
            otherUserName = "Target User",
            lastMessageText = "Hello from alpha",
            lastMessageAt = "2026-11-07T12:00:00Z"
        )
        dbA.conversationDao().insertConversation(convA)

        val retrievedInA = dbA.conversationDao().getConversationById("conv-alpha-1")
        assertNotNull(retrievedInA)
        assertEquals("user_alpha", retrievedInA?.userId)

        // Must NOT appear in dbB
        val retrievedInB = dbB.conversationDao().getConversationById("conv-alpha-1")
        assertNull(retrievedInB)

        com.msi.openchat.data.local.OpenChatDatabase.closeAllDatabases()
    }

    @Test
    fun testUserScopedMediaDirectoryAndCleanup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mediaDirA = com.msi.openchat.data.local.OfflineMediaManager.getUserMediaDirectory(context, "user_123")
        assertTrue(mediaDirA.exists())
        assertTrue(mediaDirA.path.contains("user_123"))

        val testFile = java.io.File(mediaDirA, "test_audio.m4a")
        testFile.writeText("sample audio content")
        assertTrue(testFile.exists())

        com.msi.openchat.data.local.OfflineMediaManager.deleteUserMedia(context, "user_123")
        assertFalse(testFile.exists())
    }

    @Test
    fun testUserScopedSharedPreferences() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionManager = SessionManager(context)

        val prefsA = sessionManager.getUserScopedPrefs("user_a")
        prefsA.edit().putString("draft_message", "Hello from A").commit()

        val prefsB = sessionManager.getUserScopedPrefs("user_b")
        val draftB = prefsB.getString("draft_message", null)
        assertNull(draftB)

        sessionManager.clearUserScopedPrefs("user_a")
        assertNull(prefsA.getString("draft_message", null))
    }

    @Test
    fun testServiceLocatorUserLifecycle() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        com.msi.openchat.core.di.ServiceLocator.initialize(context)

        com.msi.openchat.core.di.ServiceLocator.onUserLoggedIn("user_test_99")
        val db99 = com.msi.openchat.core.di.ServiceLocator.database
        assertNotNull(db99)

        // Cache a profile
        val profile = Profile(id = "user_test_99", username = "tester99", displayName = "Tester 99")
        com.msi.openchat.core.di.ServiceLocator.userRepository.cacheProfile(profile)
        assertNotNull(com.msi.openchat.core.di.ServiceLocator.userRepository.getCachedProfile("user_test_99"))

        // Logging out should wipe in-memory caches and close the database
        com.msi.openchat.core.di.ServiceLocator.onUserLoggedOut()
        assertNull(com.msi.openchat.core.di.ServiceLocator.userRepository.getCachedProfile("user_test_99"))
    }
}
