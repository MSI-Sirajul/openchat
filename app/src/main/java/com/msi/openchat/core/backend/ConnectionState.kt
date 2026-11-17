package com.msi.openchat.core.backend

import com.example.R
import com.msi.openchat.core.di.ServiceLocator

/**
 * Single source of truth for unified backend connectivity (Firebase + Network).
 */
sealed class ConnectionState {
    object Connected : ConnectionState()
    data class Reconnecting(val reason: String = ServiceLocator.getString(R.string.common_connecting)) : ConnectionState()
    object Offline : ConnectionState()

    val isConnected: Boolean get() = this is Connected
    val isOffline: Boolean get() = this is Offline
    val isReconnecting: Boolean get() = this is Reconnecting
}
