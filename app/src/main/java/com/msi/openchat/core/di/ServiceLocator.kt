package com.msi.openchat.core.di

import android.content.Context
import com.msi.openchat.core.security.FirebaseAuthManager
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.remote.firebase.FirebaseManager
import com.msi.openchat.data.repository.AuthRepository
import com.msi.openchat.data.repository.CallRepository
import com.msi.openchat.data.repository.ChatRepository
import com.msi.openchat.data.repository.FriendRepository
import com.msi.openchat.data.repository.FriendsRepository
import com.msi.openchat.data.repository.UserRepository
import com.msi.openchat.feature.voice.AudioPlayerHelper
import com.msi.openchat.feature.voice.AudioRecorderHelper

object ServiceLocator {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        com.msi.openchat.service.notification.OpenChatNotificationManager.initChannels(context.applicationContext)
    }

    val context: Context
        get() = appContext ?: throw IllegalStateException("ServiceLocator not initialized")

    val networkMonitor: com.msi.openchat.core.common.NetworkMonitor by lazy {
        com.msi.openchat.core.common.NetworkMonitor(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val themeManager: com.msi.openchat.core.theme.ThemeManager by lazy {
        com.msi.openchat.core.theme.ThemeManager(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val authManager: FirebaseAuthManager by lazy {
        FirebaseAuthManager(
            context = appContext ?: throw IllegalStateException("ServiceLocator not initialized"),
            sessionManager = sessionManager
        )
    }

    val firebaseManager: FirebaseManager by lazy {
        FirebaseManager.getInstance()
    }

    val userRepository: UserRepository by lazy {
        UserRepository(sessionManager)
    }

    val presenceManager: com.msi.openchat.core.presence.PresenceManager by lazy {
        com.msi.openchat.core.presence.PresenceManager(
            userRepository = userRepository,
            sessionManager = sessionManager,
            networkMonitor = networkMonitor
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(sessionManager, authManager, userRepository)
    }

    val friendRepository: FriendRepository by lazy {
        friendsRepository
    }

    val friendsRepository: FriendsRepository by lazy {
        FriendsRepository(sessionManager, userRepository)
    }

    val database: com.msi.openchat.data.local.OpenChatDatabase by lazy {
        com.msi.openchat.data.local.OpenChatDatabase.getInstance(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val messageDao: com.msi.openchat.data.local.dao.MessageDao by lazy {
        database.messageDao()
    }

    val conversationDao: com.msi.openchat.data.local.dao.ConversationDao by lazy {
        database.conversationDao()
    }

    val nhostStorageClient: com.msi.openchat.data.remote.storage.NhostStorageClient by lazy {
        val secret = try {
            val field = com.example.BuildConfig::class.java.getField("NHOST_ADMIN_SECRET")
            (field.get(null) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
        com.msi.openchat.data.remote.storage.NhostStorageClient(
            baseUrl = com.msi.openchat.core.common.Constants.NHOST_STORAGE_URL.ifBlank { com.msi.openchat.data.remote.storage.NhostStorageClient.NHOST_STORAGE_URL },
            adminSecret = secret
        )
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            sessionManager = sessionManager,
            userRepository = userRepository,
            messageDao = messageDao,
            conversationDao = conversationDao,
            storageClient = nhostStorageClient
        )
    }

    val callRepository: CallRepository by lazy {
        CallRepository(sessionManager, userRepository)
    }

    val audioRecorderHelper: AudioRecorderHelper by lazy {
        AudioRecorderHelper(appContext ?: throw IllegalStateException("ServiceLocator not initialized"))
    }

    val audioPlayerHelper: AudioPlayerHelper by lazy {
        AudioPlayerHelper()
    }
}
