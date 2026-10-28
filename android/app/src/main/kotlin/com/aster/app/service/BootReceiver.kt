package com.aster.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aster.app.data.auth.AsterAuthStorage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Automatically starts the background sync service after device boot or reboot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var authStorage: AsterAuthStorage

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "Boot completed; verifying Aster provisioning")
            if (authStorage.isProvisioned()) {
                Log.i("BootReceiver", "Starting AsterSyncService following boot")
                AsterSyncService.start(context)
            }
        }
    }
}
