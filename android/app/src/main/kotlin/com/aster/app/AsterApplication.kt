package com.aster.app

import android.app.Application
import com.aster.app.ui.notification.AsterNotificationManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Aster Application class.
 *
 * Annotated with @HiltAndroidApp to initialize Hilt's dependency injection
 * component hierarchy. Must be declared in AndroidManifest.xml as android:name.
 */
@HiltAndroidApp
class AsterApplication : Application() {

    @Inject
    lateinit var notificationManager: AsterNotificationManager

    @Inject
    lateinit var messageRepository: com.aster.app.domain.repository.MessageRepository

    @Inject
    lateinit var authStorage: com.aster.app.data.auth.AsterAuthStorage

    @Inject
    lateinit var webSocketClient: com.aster.app.data.network.AsterWebSocketClient

    override fun onCreate() {
        super.onCreate()
        val savedServer = authStorage.getServerUrl()
        com.aster.app.data.network.NetworkConfig.initializeEndpoint(
            savedUrl = savedServer,
            env = com.aster.app.data.network.NetworkConfig.currentEnvironment,
            onStaleEndpointCleared = {
                authStorage.clearServerUrl()
            }
        )
        notificationManager.initChannels()

        if (authStorage.isProvisioned()) {
            webSocketClient.connect()
            com.aster.app.service.AsterSyncService.start(this)
        }
    }
}
