package com.msi.openchat.core.common

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionType {
    WIFI, CELLULAR, ETHERNET, NONE
}

data class NetworkStatus(
    val isConnected: Boolean = true,
    val connectionType: ConnectionType = ConnectionType.WIFI,
    val isMetered: Boolean = false,
    val isUnstable: Boolean = false
)

class NetworkMonitor(context: Context) {
    private val tag = "NetworkMonitor"

    companion object {
        const val ACTION_NETWORK_STATE_CHANGED = "com.msi.openchat.action.NETWORK_STATE_CHANGED"
        const val EXTRA_IS_CONNECTED = "extra_is_connected"
        const val EXTRA_CONNECTION_TYPE = "extra_connection_type"
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _status = MutableStateFlow(checkCurrentNetwork())
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private fun broadcastNetworkState(newStatus: NetworkStatus) {
        _status.value = newStatus
        try {
            val intent = Intent(ACTION_NETWORK_STATE_CHANGED).apply {
                setPackage(appContext.packageName)
                putExtra(EXTRA_IS_CONNECTED, newStatus.isConnected)
                putExtra(EXTRA_CONNECTION_TYPE, newStatus.connectionType.name)
            }
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(tag, "Failed to send network broadcast: ${e.message}")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            Log.d(tag, "Network available: $network")
            broadcastNetworkState(checkCurrentNetwork())
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Log.w(tag, "Network lost: $network")
            broadcastNetworkState(
                NetworkStatus(
                    isConnected = false,
                    connectionType = ConnectionType.NONE,
                    isUnstable = true
                )
            )
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, capabilities)
            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val isCell = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            val type = when {
                isWifi -> ConnectionType.WIFI
                isCell -> ConnectionType.CELLULAR
                else -> ConnectionType.ETHERNET
            }

            broadcastNetworkState(
                NetworkStatus(
                    isConnected = hasInternet,
                    connectionType = type,
                    isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    isUnstable = !hasInternet
                )
            )
        }
    }

    init {
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager?.registerNetworkCallback(request, networkCallback)
        } catch (e: Exception) {
            Log.e(tag, "Failed to register network callback: ${e.message}")
        }
    }

    private fun checkCurrentNetwork(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus(isConnected = true)
        val activeNetwork = cm.activeNetwork ?: return NetworkStatus(isConnected = false, connectionType = ConnectionType.NONE)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return NetworkStatus(isConnected = false, connectionType = ConnectionType.NONE)

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return NetworkStatus(
            isConnected = hasInternet,
            connectionType = if (isWifi) ConnectionType.WIFI else if (isCell) ConnectionType.CELLULAR else ConnectionType.ETHERNET,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        )
    }
}
