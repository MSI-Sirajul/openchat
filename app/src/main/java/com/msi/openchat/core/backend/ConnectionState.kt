package com.msi.openchat.core.backend

/**
 * Single source of truth for unified backend connectivity (Firebase + NHost + Network).
 */
sealed class ConnectionState {
    object Connected : ConnectionState()
    data class Reconnecting(val reason: String = "Connecting...") : ConnectionState()
    object Offline : ConnectionState()

    val isConnected: Boolean get() = this is Connected
    val isOffline: Boolean get() = this is Offline
    val isReconnecting: Boolean get() = this is Reconnecting
}
