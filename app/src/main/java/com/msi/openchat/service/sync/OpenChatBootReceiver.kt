package com.msi.openchat.service.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.msi.openchat.core.di.ServiceLocator
import com.msi.openchat.service.call.OpenChatKeepAliveService

/**
 * BroadcastReceiver triggered after device reboot, package update, or system boot.
 * Keeps OpenChat always alive and reschedules background sync to ensure incoming notifications are delivered.
 */
class OpenChatBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        Log.i("OpenChatBootReceiver", "Received broadcast action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            try {
                ServiceLocator.initialize(context.applicationContext)
                DataSyncWorker.schedulePeriodicSync(context.applicationContext)

                if (ServiceLocator.sessionManager.isAuthenticated()) {
                    com.msi.openchat.core.backend.BackendManager.initialize(context.applicationContext)
                    OpenChatKeepAliveService.start(context.applicationContext)
                    Log.i("OpenChatBootReceiver", "Boot sync & keep-alive service started successfully")
                }
            } catch (e: Exception) {
                Log.w("OpenChatBootReceiver", "Error during boot handling: ${e.message}")
            }
        }
    }
}
