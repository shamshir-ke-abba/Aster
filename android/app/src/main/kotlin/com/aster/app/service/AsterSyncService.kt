package com.aster.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.ui.notification.AsterNotificationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import javax.inject.Inject

/**
 * Foreground synchronization service for Aster.
 * Maintains a reliable, persistent authenticated WebSocket connection and receives
 * real-time messages when the application is backgrounded or when the activity task is removed.
 */
@AndroidEntryPoint
class AsterSyncService : Service() {

    companion object {
        private const val TAG = "AsterSyncService"

        fun start(context: Context) {
            try {
                val intent = Intent(context, AsterSyncService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not start AsterSyncService: ${e.message}")
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, AsterSyncService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop AsterSyncService: ${e.message}")
            }
        }
    }

    @Inject
    lateinit var authStorage: AsterAuthStorage

    @Inject
    lateinit var webSocketClient: AsterWebSocketClient

    @Inject
    lateinit var messageRepository: MessageRepository

    @Inject
    lateinit var notificationManager: AsterNotificationManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "AsterSyncService created; promoting to foreground service")
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "AsterSyncService onStartCommand (startId=$startId)")
        promoteToForeground()
        if (authStorage.isProvisioned()) {
            webSocketClient.connect()
        }
        return START_STICKY
    }

    /**
     * Promotes this service to an Android Foreground Service using a low-priority,
     * non-intrusive status notification and dataSync service type on API 29+.
     */
    private fun promoteToForeground() {
        try {
            val notification = notificationManager.buildSyncNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    AsterNotificationManager.SYNC_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(AsterNotificationManager.SYNC_NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "AsterSyncService successfully running in foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Error promoting AsterSyncService to foreground: ${e.message}", e)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed; maintaining active WebSocket foreground connection")
        if (authStorage.isProvisioned()) {
            // Re-assert WebSocket connection
            webSocketClient.connect()

            // Defensive keep-alive via PendingIntent.getForegroundService in case process is reaped
            try {
                val restartIntent = Intent(applicationContext, AsterSyncService::class.java).also {
                    it.setPackage(packageName)
                }
                val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PendingIntent.getForegroundService(
                        applicationContext,
                        1001,
                        restartIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } else {
                    PendingIntent.getService(
                        applicationContext,
                        1001,
                        restartIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule defensive keep-alive alarm: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        Log.i(TAG, "AsterSyncService destroyed")
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
