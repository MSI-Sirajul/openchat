package com.msi.openchat.core.security

import android.util.Log

/**
 * JNI Bridge to the native C++ CredentialsManager.
 * When a new database/backend is configured, dynamically extracts project configuration.
 * Fully decoupled from previous databases with empty string defaults.
 */
object NativeCredentialsManager {
    private const val TAG = "NativeCreds"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("credentials_manager")
            isNativeLoaded = true
        } catch (_: Throwable) {
            Log.d(TAG, "Native library 'credentials_manager' not loaded in runtime; using fallback getters.")
            isNativeLoaded = false
        }
    }

    // External JNI declarations matching C++ exports
    @JvmStatic
    external fun nativeGetSubdomain(): String

    @JvmStatic
    external fun nativeGetRegion(): String

    // Resilient public getters with runtime fallback
    val subdomain: String
        get() = safeNativeCall({ nativeGetSubdomain() }, "")

    val region: String
        get() = safeNativeCall({ nativeGetRegion() }, "")

    val isConfigured: Boolean
        get() = subdomain.isNotBlank()

    private inline fun safeNativeCall(nativeCall: () -> String, fallback: String): String {
        return if (isNativeLoaded) {
            try {
                val res = nativeCall()
                if (res.isNotBlank()) res else fallback
            } catch (_: Throwable) {
                fallback
            }
        } else {
            fallback
        }
    }
}
