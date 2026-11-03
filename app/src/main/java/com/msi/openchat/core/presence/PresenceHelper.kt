package com.msi.openchat.core.presence

import com.msi.openchat.data.model.Profile
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * PresenceHelper evaluates accurate real-time user presence.
 * A user is never assumed to be online purely by a database flag;
 * they must possess a fresh heartbeat within the last 75 seconds.
 */
object PresenceHelper {
    // Heartbeat threshold: user must have pinged within 75 seconds to be considered active
    const val ONLINE_TIMEOUT_SECONDS = 75L

    /**
     * Determines whether the user is actively connected and online.
     */
    fun isUserOnline(profile: Profile?): Boolean {
        if (profile == null) return false
        if (profile.onlineStatus != "online") return false
        val lastSeenIso = profile.lastSeen ?: return false

        val lastSeenInstant = parseIsoTimestamp(lastSeenIso) ?: return false
        val now = Instant.now()
        val durationSeconds = Duration.between(lastSeenInstant, now).seconds

        // Account for small clock drift (e.g. -5 to +75s)
        return durationSeconds in -10..ONLINE_TIMEOUT_SECONDS
    }

    /**
     * Generates a user-friendly presence label ("Online", "Last seen 5m ago", "Offline").
     */
    fun formatPresenceStatus(profile: Profile?): String {
        if (profile == null) return "Offline"

        if (isUserOnline(profile)) {
            return "Online"
        }

        if (!profile.showLastSeen) {
            return "Offline"
        }

        val lastSeenIso = profile.lastSeen ?: return "Offline"
        val lastSeenInstant = parseIsoTimestamp(lastSeenIso) ?: return "Offline"
        val now = Instant.now()
        val diffSeconds = Duration.between(lastSeenInstant, now).seconds

        return when {
            diffSeconds < 60 -> "Last seen just now"
            diffSeconds < 3600 -> {
                val mins = (diffSeconds / 60).coerceAtLeast(1)
                "Last seen ${mins}m ago"
            }
            diffSeconds < 86400 -> {
                val hours = (diffSeconds / 3600).coerceAtLeast(1)
                "Last seen ${hours}h ago"
            }
            diffSeconds < 172800 -> "Last seen yesterday"
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
                "Last seen ${formatter.format(lastSeenInstant)}"
            }
        }
    }

    private fun parseIsoTimestamp(isoString: String): Instant? {
        val numeric = isoString.toLongOrNull()
        if (numeric != null) {
            return try {
                Instant.ofEpochMilli(numeric)
            } catch (_: Exception) {
                null
            }
        }
        return try {
            Instant.parse(isoString)
        } catch (_: Exception) {
            try {
                // Support variant without Z
                val withZ = if (!isoString.endsWith("Z") && !isoString.contains("+")) "${isoString}Z" else isoString
                Instant.parse(withZ)
            } catch (_: Exception) {
                null
            }
        }
    }
}
