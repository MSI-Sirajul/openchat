package com.msi.openchat

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * OpenChatApplication
 * Central Application class with optimized memory footprint, bounded Coil image caching,
 * and lightweight deferred dependency initialization for rapid cold startup.
 */
class OpenChatApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        try {
            com.google.firebase.FirebaseApp.initializeApp(this)
        } catch (_: Exception) {}
        // Initialize lightweight app context reference in ServiceLocator
        ServiceLocator.initialize(this)

        // Schedule reliable periodic background data sync via WorkManager & start keep-alive service
        try {
            com.msi.openchat.service.sync.DataSyncWorker.schedulePeriodicSync(this)
            if (ServiceLocator.sessionManager.isAuthenticated()) {
                com.msi.openchat.service.call.OpenChatKeepAliveService.start(this)
            }
        } catch (_: Exception) {}

        // Migrate legacy local media files to URIs and delete internal storage copies
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                ServiceLocator.messageDao?.let { dao ->
                    com.msi.openchat.core.media.PersistableUriManager.migrateLegacyMediaToUris(this@OpenChatApplication, dao)
                }
            } catch (_: Exception) {}
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Safe 15% RAM ceiling to prevent OOM on budget devices
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("openchat_image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB disk cache for media and avatars
                    .build()
            }
            .respectCacheHeaders(false)
            .allowRgb565(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) // 50% RAM reduction on older/low-end devices
            .crossfade(true)
            .build()
    }
}
