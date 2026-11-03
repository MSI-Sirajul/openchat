package com.msi.openchat.service.call

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport

/**
 * Dedicated timer-based loop that queries peerConnection.getStats() every 1.5 seconds,
 * calculates RTT, jitter, and packet loss, and publishes them to a StateFlow.
 */
class WebRtcStatsCollector(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + Job())
) {
    private val tag = "OpenChatWebRtc_Stats"

    private val _networkStats = MutableStateFlow(CallNetworkStats())
    val networkStats: StateFlow<CallNetworkStats> = _networkStats.asStateFlow()

    private var loopJob: Job? = null

    fun start(peerConnectionProvider: () -> PeerConnection?, intervalMs: Long = 1500L) {
        stop()
        Log.d(tag, "Starting timer-based WebRTC stats monitoring loop (interval: ${intervalMs}ms)")
        loopJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                try {
                    val pc = peerConnectionProvider()
                    if (pc == null || pc.signalingState() == PeerConnection.SignalingState.CLOSED) {
                        break
                    }

                    pc.getStats(object : RTCStatsCollectorCallback {
                        override fun onStatsDelivered(report: RTCStatsReport?) {
                            try {
                                if (report != null) {
                                    parseStatsReport(report)
                                }
                            } catch (t: Throwable) {
                                Log.e(tag, "Exception in RTCStatsCollectorCallback: ${t.message}", t)
                            }
                        }
                    })
                } catch (t: Throwable) {
                    Log.w(tag, "Exception during getStats execution: ${t.message}")
                }
            }
        }
    }

    private fun parseStatsReport(report: RTCStatsReport) {
        try {
            var rttMs = 0
            var packetsLost = 0
            var jitterMs = 0
            var packetsReceived = 0

            for (stats in report.statsMap.values) {
                when (stats.type) {
                    "candidate-pair" -> {
                        val state = stats.members["state"] as? String
                        // Look for the active nominated candidate pair
                        if (state == "succeeded" || state == "in-progress") {
                            val currentRtt = stats.members["currentRoundTripTime"] 
                                ?: stats.members["roundTripTime"]
                            if (currentRtt is Number) {
                                rttMs = (currentRtt.toDouble() * 1000).toInt()
                            }
                        }
                    }
                    "inbound-rtp" -> {
                        val mediaType = stats.members["mediaType"] as? String
                        if (mediaType == null || mediaType == "audio") {
                            val lost = stats.members["packetsLost"]
                            if (lost is Number) {
                                packetsLost = lost.toInt()
                            }
                            val jitter = stats.members["jitter"]
                            if (jitter is Number) {
                                jitterMs = (jitter.toDouble() * 1000).toInt()
                            }
                            val received = stats.members["packetsReceived"]
                            if (received is Number) {
                                packetsReceived = received.toInt()
                            }
                        }
                    }
                }
            }

            val quality = when {
                rttMs == 0 && packetsLost == 0 -> NetworkQuality.GOOD
                rttMs in 1..120 && packetsLost < 5 -> NetworkQuality.EXCELLENT
                rttMs in 1..250 && packetsLost < 20 -> NetworkQuality.GOOD
                rttMs in 1..450 -> NetworkQuality.FAIR
                else -> NetworkQuality.POOR
            }

            val newStats = CallNetworkStats(
                rttMs = rttMs,
                packetsLost = packetsLost,
                jitterMs = jitterMs,
                packetsReceived = packetsReceived,
                quality = quality
            )

            _networkStats.value = newStats
            Log.d(tag, "Surfaced WebRTC Metrics -> RTT: ${rttMs}ms, Jitter: ${jitterMs}ms, Lost: $packetsLost, Recv: $packetsReceived, Quality: $quality")
        } catch (e: Exception) {
            Log.e(tag, "Error parsing RTCStatsReport: ${e.message}", e)
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        Log.d(tag, "Stopped WebRTC stats loop")
    }

    fun reset() {
        stop()
        _networkStats.value = CallNetworkStats()
    }
}
