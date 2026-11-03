package com.msi.openchat

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.msi.openchat.core.di.ServiceLocator

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

        // Schedule reliable periodic background data sync via WorkManager
        try {
            com.msi.openchat.service.sync.DataSyncWorker.schedulePeriodicSync(this)
        } catch (_: Exception) {}
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
