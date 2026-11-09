package com.msi.openchat.core.presence

import android.util.Log
import com.msi.openchat.core.common.NetworkMonitor
import com.msi.openchat.core.security.SessionManager
import com.msi.openchat.data.repository.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PresenceManager manages the client's heartbeat loop, sending periodic presence
 * pings to the backend while active and marking the session as offline on exit/backgrounding.
 */
class PresenceManager(
    private val userRepository: UserRepository,
    private val sessionManager: SessionManager,
    private val networkMonitor: NetworkMonitor
) {
    private val tag = "OpenChat_Presence"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var heartbeatJob: Job? = null
    private var isAppForegrounded = false

    init {
        observeNetwork()
    }

    private fun observeNetwork() {
        scope.launch {
            networkMonitor.status.collect { status ->
                if (status.isConnected && isAppForegrounded && sessionManager.isAuthenticated()) {
                    sendHeartbeat(online = true)
                }
            }
        }
    }

    /**
     * Call when app enters the foreground. Starts the 30-second heartbeat loop.
     */
    fun onAppForegrounded() {
        isAppForegrounded = true
        startHeartbeat()
    }

    /**
     * Call when app enters background or is paused.
     */
    fun onAppBackgrounded() {
        isAppForegrounded = false
        stopHeartbeat()
        scope.launch {
            if (sessionManager.isAuthenticated()) {
                sendHeartbeat(online = false)
            }
        }
    }

    /**
     * Call when user logs out.
     */
    fun onUserLoggedOut() {
        stopHeartbeat()
        scope.launch {
            sendHeartbeat(online = false)
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                if (sessionManager.isAuthenticated()) {
                    sendHeartbeat(online = true)
                }
                delay(30_000L) // 30-second heartbeat interval
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private suspend fun sendHeartbeat(online: Boolean) {
        val status = if (online) "online" else "offline"
        try {
            userRepository.updateOnlineStatus(status)
            Log.d(tag, "Sent presence update: $status at ${java.time.Instant.now()}")
        } catch (e: Exception) {
            Log.w(tag, "Failed to send presence heartbeat: ${e.message}")
        }
    }
}
