package com.aster.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.ui.navigation.AsterNavHost
import com.aster.app.ui.notification.AsterNotificationManager
import com.aster.app.ui.theme.AsterTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authStorage: AsterAuthStorage

    @Inject
    lateinit var webSocketClient: AsterWebSocketClient

    @Inject
    lateinit var notificationManager: AsterNotificationManager

    @Inject
    lateinit var messageRepository: com.aster.app.domain.repository.MessageRepository

    internal val pendingConversationId = MutableStateFlow<String?>(null)

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("MainActivity", "POST_NOTIFICATIONS permission granted by user")
        } else {
            Log.w("MainActivity", "POST_NOTIFICATIONS permission denied; falling back to in-app unread indicators")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationManager.initChannels()
        checkNotificationPermission()
        handleServerOverrideIntent(intent)
        handleNotificationIntent(intent)

        if (authStorage.isProvisioned()) {
            webSocketClient.connect()
            com.aster.app.service.AsterSyncService.start(this)
        }

        enableEdgeToEdge()
        setContent {
            AsterTheme {
                AsterNavHost(
                    modifier = Modifier.fillMaxSize(),
                    isProvisioned = authStorage.isProvisioned(),
                    pendingConversationId = pendingConversationId
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notificationManager.isAppForeground = true
    }

    override fun onResume() {
        super.onResume()
        notificationManager.isAppForeground = true
        notificationManager.getActiveConversation()?.let { activeId ->
            notificationManager.dismissNotification(activeId)
        }
        if (authStorage.isProvisioned() &&
            webSocketClient.connectionState.value == com.aster.app.data.network.ConnectionState.DISCONNECTED &&
            webSocketClient.isNetworkAvailable()
        ) {
            Log.i("MainActivity", "[AsterLifecycle] Resumed with network available; re-asserting active connection")
            webSocketClient.connect()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d("MainActivity", "onWindowFocusChanged: hasFocus=$hasFocus")
        if (hasFocus) {
            notificationManager.isAppForeground = true
            notificationManager.getActiveConversation()?.let { activeId ->
                notificationManager.dismissNotification(activeId)
            }
        } else {
            notificationManager.isAppForeground = false
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        Log.d("MainActivity", "onTopResumedActivityChanged: isTopResumedActivity=$isTopResumedActivity")
        if (!isTopResumedActivity) {
            notificationManager.isAppForeground = false
        }
    }

    override fun onPause() {
        super.onPause()
        notificationManager.isAppForeground = false
    }

    override fun onStop() {
        super.onStop()
        notificationManager.isAppForeground = false
    }

    override fun onDestroy() {
        notificationManager.isAppForeground = false
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleServerOverrideIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val currentPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (currentPermission != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun handleNotificationIntent(intent: Intent?) {
        val convId = intent?.getStringExtra(AsterNotificationManager.EXTRA_CONVERSATION_ID)
        if (!convId.isNullOrBlank()) {
            Log.i("MainActivity", "Navigating from notification tap to conversation: $convId")
            pendingConversationId.value = convId
        }
    }

    internal fun handleServerOverrideIntent(intent: Intent?) {
        if (authStorage.isProvisioned()) {
            return
        }

        val data = intent?.data
        val serverFromData = if (data != null && data.scheme == "aster") {
            data.getQueryParameter("server")
        } else null

        val server = serverFromData ?: intent?.getStringExtra("aster_server")
        if (!server.isNullOrBlank()) {
            if (com.aster.app.data.network.NetworkConfig.currentEnvironment == com.aster.app.data.network.NetworkEnvironment.PRODUCTION &&
                com.aster.app.data.network.NetworkConfig.isLocalOrDevelopmentEndpoint(server)
            ) {
                Log.w("MainActivity", "Ignoring stale/development server in PRODUCTION: $server")
            } else {
                try {
                    com.aster.app.data.network.NetworkConfig.serverUrl = server
                    authStorage.saveServerUrl(server)
                    Log.i("MainActivity", "Configured server endpoint from intent: $server")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Invalid server endpoint in intent: $server", e)
                }
            }
        }
    }
}
