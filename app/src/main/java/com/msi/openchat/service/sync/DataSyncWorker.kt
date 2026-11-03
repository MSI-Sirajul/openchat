package com.msi.openchat.service.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.msi.openchat.core.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * DataSyncWorker
 * Standard Android WorkManager worker for deferrable, power-efficient,
 * and reliable background synchronization of conversations, unread messages,
 * and friend requests in full compliance with Android 12, 14, and 15 background restrictions.
 */
class DataSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "DataSyncWorker"
        private const val PERIODIC_WORK_NAME = "openchat_periodic_sync"
        private const val IMMEDIATE_WORK_NAME = "openchat_immediate_sync"

        /**
         * Schedules periodic background sync respecting system battery optimizations.
         */
        fun schedulePeriodicSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val syncRequest = PeriodicWorkRequestBuilder<DataSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                syncRequest
            )
            Log.d(TAG, "Periodic background data sync scheduled (every 15 minutes)")
        }

        /**
         * Enqueues an immediate one-time sync task when active sync is required.
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<DataSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                syncRequest
            )
            Log.d(TAG, "Immediate one-time background sync enqueued")
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uid = ServiceLocator.sessionManager.getUserId()
        if (uid.isNullOrBlank()) {
            Log.d(TAG, "No authenticated session. Skipping background sync.")
            return@withContext Result.success()
        }

        Log.i(TAG, "Starting background sync for user: $uid")
        try {
            // 1. Refresh conversations and cache locally
            ServiceLocator.chatRepository.getConversations()

            // 2. Refresh pending friend requests
            ServiceLocator.friendsRepository.getReceivedRequests()

            // 3. Refresh user profile & presence
            ServiceLocator.userRepository.getCurrentUserProfile()

            Log.i(TAG, "Background sync completed successfully")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background sync encountered error: ${e.message}")
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.success()
            }
        }
    }
}
