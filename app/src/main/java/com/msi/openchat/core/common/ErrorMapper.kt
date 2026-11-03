package com.msi.openchat.core.common

import com.msi.openchat.core.di.ServiceLocator
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Maps system and network exceptions into clear, clean user-facing error messages.
 * If the user device data or network is disabled or unreachable, consistently
 * surfaces "No internet connection" instead of generic service error or connection error.
 */
object ErrorMapper {

    fun toUserFacingMessage(throwable: Throwable?, fallback: String = "Something went wrong"): String {
        if (throwable == null) return fallback

        // 1. Direct device network check if available
        try {
            if (!ServiceLocator.networkMonitor.status.value.isConnected) {
                return "No internet connection"
            }
        } catch (_: Exception) {}

        // 2. Network-level socket/DNS exceptions
        if (throwable is UnknownHostException ||
            throwable is ConnectException ||
            throwable is SocketTimeoutException
        ) {
            return "No internet connection"
        }

        val rawMsg = throwable.message?.trim().orEmpty()
        val lower = rawMsg.lowercase()

        // 3. Unreachable host, timeout, or network disconnect strings
        if (lower.contains("no internet") ||
            lower.contains("unable to resolve host") ||
            lower.contains("failed to connect") ||
            lower.contains("connection refused") ||
            lower.contains("network is unreachable") ||
            lower.contains("route to host") ||
            lower.contains("socket closed") ||
            lower.contains("connection error") ||
            lower.contains("service error") ||
            lower.contains("timeout") ||
            lower.contains("connection reset") ||
            lower.contains("broken pipe") ||
            lower.contains("software caused connection abort")
        ) {
            return "No internet connection"
        }

        // 4. Rate limiting (HTTP 429)
        if (lower.contains("429") ||
            lower.contains("too many requests") ||
            lower.contains("too-many-requests") ||
            lower.contains("rate limit")
        ) {
            return "Too many sign-in attempts. Please wait 1 minute before trying again to allow the rate limit to reset."
        }

        // 5. GraphQL schema / query_root errors
        if (lower.contains("not found in type: 'query_root'") || lower.contains("not found query_root")) {
            return "Database table permissions initializing. Please retry in a few moments."
        }

        // 6. Email verification notice
        if (lower.contains("unverified") || lower.contains("not confirmed") || lower.contains("not-verified")) {
            return "Please check your email (including Spam/Junk folder) to confirm your account, or disable 'Require Email Verification' in Nhost Console."
        }

        return rawMsg.ifBlank { fallback }
    }
}
