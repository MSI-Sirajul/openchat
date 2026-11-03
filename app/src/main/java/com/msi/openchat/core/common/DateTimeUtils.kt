package com.msi.openchat.core.common

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeUtils {

    /**
     * Parses ISO-8601 / Nhost / PostgreSQL timestamp strings into java.time.Instant safely.
     * Handles timezone offsets (e.g., +00:00, +06:00, Z), variable millisecond precision,
     * and space-separated timestamps.
     */
    fun parseTimestamp(isoString: String?): Instant {
        if (isoString.isNullOrBlank()) return Instant.now()
        val cleaned = isoString.trim()

        val millis = cleaned.toLongOrNull()
        if (millis != null) {
            return try {
                Instant.ofEpochMilli(millis)
            } catch (_: Exception) {
                Instant.now()
            }
        }

        return try {
            // Standard ISO-8601 with Z or explicit offset
            Instant.parse(cleaned)
        } catch (_: Exception) {
            try {
                // OffsetDateTime (e.g. 2024-03-29T14:32:50+06:00)
                OffsetDateTime.parse(cleaned).toInstant()
            } catch (_: Exception) {
                try {
                    // Replace space with T for PostgreSQL format: 2024-03-29 14:32:50.123+00
                    val normalized = cleaned.replace(' ', 'T')
                    OffsetDateTime.parse(normalized).toInstant()
                } catch (_: Exception) {
                    try {
                        // If no timezone offset is present, interpret as UTC
                        val normalized = cleaned.replace(' ', 'T')
                        val withZ = if (!normalized.contains('+') && !normalized.endsWith('Z')) {
                            normalized + "Z"
                        } else normalized
                        Instant.parse(withZ)
                    } catch (_: Exception) {
                        Instant.now()
                    }
                }
            }
        }
    }

    /**
     * Formats message timestamp respecting the device's system setting (24-hour vs 12-hour AM/PM).
     * 24-hour: "14:30"
     * 12-hour: "2:30 PM"
     */
    fun formatMessageTime(context: Context, isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        val instant = parseTimestamp(isoString)
        val zone = ZoneId.systemDefault()
        val zonedDateTime = instant.atZone(zone)

        val is24Hour = DateFormat.is24HourFormat(context)
        val pattern = if (is24Hour) "HH:mm" else "h:mm a"
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        return zonedDateTime.format(formatter)
    }

    /**
     * Formats date separator for chat messages:
     * - "Today"
     * - "Yesterday"
     * - "MMMM d" (e.g. "October 14") if within the current year
     * - "MMMM d, yyyy" (e.g. "October 14, 2025") if older year
     */
    fun formatDateSeparator(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "Today"
        val instant = parseTimestamp(isoString)
        val zone = ZoneId.systemDefault()
        val messageDate = instant.atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)

        return when {
            messageDate.isEqual(today) -> "Today"
            messageDate.isEqual(today.minusDays(1)) -> "Yesterday"
            messageDate.year == today.year -> {
                val formatter = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
                messageDate.format(formatter)
            }
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())
                messageDate.format(formatter)
            }
        }
    }

    /**
     * Checks if two consecutive ISO timestamps belong to different calendar days in the local timezone.
     */
    fun isDifferentDay(isoString1: String?, isoString2: String?): Boolean {
        if (isoString1 == null || isoString2 == null) return true
        val zone = ZoneId.systemDefault()
        val date1 = parseTimestamp(isoString1).atZone(zone).toLocalDate()
        val date2 = parseTimestamp(isoString2).atZone(zone).toLocalDate()
        return !date1.isEqual(date2)
    }

    /**
     * Formats conversation item timestamp for the conversation list on HomeScreen:
     * - Today: formatted time (e.g. "2:45 PM" or "14:45")
     * - Yesterday: "Yesterday"
     * - This year: "MMM d" (e.g. "Oct 12")
     * - Previous years: "MMM d, yyyy" (e.g. "Oct 12, 2025")
     */
    fun formatConversationTime(context: Context, isoString: String?): String {
        if (isoString.isNullOrBlank()) return ""
        val instant = parseTimestamp(isoString)
        val zone = ZoneId.systemDefault()
        val messageDateTime = instant.atZone(zone)
        val messageDate = messageDateTime.toLocalDate()
        val today = LocalDate.now(zone)

        return when {
            messageDate.isEqual(today) -> {
                val is24Hour = DateFormat.is24HourFormat(context)
                val pattern = if (is24Hour) "HH:mm" else "h:mm a"
                val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
                messageDateTime.format(formatter)
            }
            messageDate.isEqual(today.minusDays(1)) -> "Yesterday"
            messageDate.year == today.year -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
                messageDate.format(formatter)
            }
            else -> {
                val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
                messageDate.format(formatter)
            }
        }
    }

    /**
     * Formats relative timestamp for friend requests or user activities.
     */
    fun formatRelativeTime(isoString: String?): String {
        if (isoString.isNullOrBlank()) return "Recently"
        return try {
            val instant = parseTimestamp(isoString)
            val now = Instant.now()
            val diffSec = java.time.Duration.between(instant, now).seconds
            when {
                diffSec < 60 -> "Just now"
                diffSec < 3600 -> "${diffSec / 60}m ago"
                diffSec < 86400 -> "${diffSec / 3600}h ago"
                diffSec < 172800 -> "Yesterday"
                else -> "${diffSec / 86400}d ago"
            }
        } catch (_: Exception) {
            "Recently"
        }
    }
}
