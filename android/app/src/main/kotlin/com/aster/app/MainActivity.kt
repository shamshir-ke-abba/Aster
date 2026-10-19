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

    private val pendingConversationId = MutableStateFlow<String?>(null)
    private val pendingInviteToken = MutableStateFlow<String?>(null)
    private val pendingInviteError = MutableStateFlow<String?>(null)

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
        handleInviteIntent(intent)
        handleNotificationIntent(intent)

        if (authStorage.isProvisioned()) {
            webSocketClient.connect()
        }

        enableEdgeToEdge()
        setContent {
            AsterTheme {
                AsterNavHost(
                    modifier = Modifier.fillMaxSize(),
                    isProvisioned = authStorage.isProvisioned(),
                    pendingConversationId = pendingConversationId,
                    pendingInviteToken = pendingInviteToken,
                    pendingInviteError = pendingInviteError
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        notificationManager.isAppForeground = true
    }

    override fun onStop() {
        super.onStop()
        notificationManager.isAppForeground = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInviteIntent(intent)
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

    private fun handleInviteIntent(intent: Intent?) {
        if (authStorage.isProvisioned()) {
            Log.w("MainActivity", "Ignoring invite link and extras: client is already provisioned with an authenticated account")
            return
        }

        val data = intent?.data
        if (data != null && data.scheme == "aster" && data.host == "join") {
            val server = data.getQueryParameter("server")
            if (!server.isNullOrBlank()) {
                try {
                    com.aster.app.data.network.NetworkConfig.serverUrl = server
                    authStorage.saveServerUrl(server)
                    Log.i("MainActivity", "Configured server endpoint from invite link: $server")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Invalid server endpoint in invite link: $server", e)
                }
            }
            val token = data.getQueryParameter("token")
            if (!token.isNullOrBlank()) {
                pendingInviteToken.value = token
                pendingInviteError.value = null
            } else {
                pendingInviteError.value = "Malformed invite link: missing token parameter"
            }
            return
        }

        intent?.getStringExtra("aster_server")?.let { server ->
            if (server.isNotBlank()) {
                try {
                    com.aster.app.data.network.NetworkConfig.serverUrl = server
                    authStorage.saveServerUrl(server)
                    Log.i("MainActivity", "Configured server endpoint from intent extra: $server")
                } catch (e: Exception) {
                    Log.w("MainActivity", "Invalid server extra: $server", e)
                }
            }
        }

        intent?.getStringExtra("aster_token")?.let { token ->
            if (token.isNotBlank()) {
                pendingInviteToken.value = token
                pendingInviteError.value = null
            }
        }
    }
}
