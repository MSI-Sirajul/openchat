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

    @JvmStatic
    external fun nativeGetAuthUrl(): String

    @JvmStatic
    external fun nativeGetGraphqlUrl(): String

    @JvmStatic
    external fun nativeGetGraphqlWsUrl(): String

    @JvmStatic
    external fun nativeGetStorageUrl(): String

    @JvmStatic
    external fun nativeGetFunctionsUrl(): String

    @JvmStatic
    external fun nativeGetHasuraUrl(): String

    // Resilient public getters with runtime fallback (all cleared to empty placeholders)
    val subdomain: String
        get() = safeNativeCall({ nativeGetSubdomain() }, FALLBACK_SUBDOMAIN)

    val region: String
        get() = safeNativeCall({ nativeGetRegion() }, FALLBACK_REGION)

    val authUrl: String
        get() = safeNativeCall({ nativeGetAuthUrl() }, FALLBACK_AUTH_URL)

    val graphqlUrl: String
        get() = safeNativeCall({ nativeGetGraphqlUrl() }, FALLBACK_GRAPHQL_URL)

    val graphqlWsUrl: String
        get() = safeNativeCall({ nativeGetGraphqlWsUrl() }, FALLBACK_GRAPHQL_WS_URL)

    val storageUrl: String
        get() = safeNativeCall({ nativeGetStorageUrl() }, FALLBACK_STORAGE_URL)

    val functionsUrl: String
        get() = safeNativeCall({ nativeGetFunctionsUrl() }, FALLBACK_FUNCTIONS_URL)

    val hasuraUrl: String
        get() = safeNativeCall({ nativeGetHasuraUrl() }, FALLBACK_HASURA_URL)

    val isConfigured: Boolean
        get() = subdomain.isNotBlank() && authUrl.isNotBlank() && authUrl.startsWith("http")

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

    // Active Nhost project credentials
    private const val FALLBACK_SUBDOMAIN = "stvvoprjibbedgcdlbib"
    private const val FALLBACK_REGION = "ap-south-1"
    private const val FALLBACK_AUTH_URL = "https://stvvoprjibbedgcdlbib.auth.ap-south-1.nhost.run/v1"
    private const val FALLBACK_GRAPHQL_URL = "https://stvvoprjibbedgcdlbib.graphql.ap-south-1.nhost.run/v1"
    private const val FALLBACK_GRAPHQL_WS_URL = "wss://stvvoprjibbedgcdlbib.graphql.ap-south-1.nhost.run/v1"
    private const val FALLBACK_STORAGE_URL = "https://stvvoprjibbedgcdlbib.storage.ap-south-1.nhost.run/v1"
    private const val FALLBACK_FUNCTIONS_URL = "https://stvvoprjibbedgcdlbib.functions.ap-south-1.nhost.run/v1"
    private const val FALLBACK_HASURA_URL = "https://stvvoprjibbedgcdlbib.hasura.ap-south-1.nhost.run/console"
}
