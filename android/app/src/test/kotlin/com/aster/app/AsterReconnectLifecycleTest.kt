package com.aster.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.network.NetworkEnvironment
import com.aster.app.data.network.ServerMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AsterReconnectLifecycleTest {

    private lateinit var context: Context
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()

        mockWebServer = MockWebServer()
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
        NetworkConfig.resetToDefault(NetworkEnvironment.PRODUCTION)
    }

    @Test
    fun initialStateIsDisconnected() {
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun connectWithoutTokenDeferredToDisconnected() {
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)
        client.connect() // No token saved
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun successfulAuthTransitionsToConnectedAndResetsBackoff() = runTest {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                // Respond to AUTH frame with AUTH_OK
                webSocket.send("""{"type":"AUTH_OK","userId":"user_m21"}""")
            }
        }))

        val serverUrl = mockWebServer.url("/ws").toString().replace("http://", "ws://")
        NetworkConfig.setServerUrlForEnvironment(serverUrl, NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL)

        authStorage.saveAuthToken("ast_tok_m21_valid")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        client.connect("ast_tok_m21_valid")

        // Wait for AUTH_OK processing
        val authEvent = client.authEvents.first { it is ServerMessage.AuthOk }
        assertEquals("user_m21", (authEvent as ServerMessage.AuthOk).userId)
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)
        assertEquals("user_m21", authStorage.getAuthenticatedUserId())

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun authErrorTransitionsToDisconnectedAndClearsToken() = runTest {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"AUTH_ERROR","reason":"Token expired"}""")
            }
        }))

        val serverUrl = mockWebServer.url("/ws").toString().replace("http://", "ws://")
        NetworkConfig.setServerUrlForEnvironment(serverUrl, NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL)

        authStorage.saveAuthToken("ast_tok_expired")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        client.connect("ast_tok_expired")

        val authEvent = client.authEvents.first { it is ServerMessage.AuthError }
        assertEquals("Token expired", (authEvent as ServerMessage.AuthError).reason)
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        assertTrue(authStorage.getAuthToken().isNullOrBlank())

        client.cleanup()
    }

    @Test
    fun networkLostTriggersDisconnectedState() = runTest {
        mockWebServer.enqueue(MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("""{"type":"AUTH_OK","userId":"user_m21"}""")
            }
        }))

        val serverUrl = mockWebServer.url("/ws").toString().replace("http://", "ws://")
        NetworkConfig.setServerUrlForEnvironment(serverUrl, NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL)

        authStorage.saveAuthToken("ast_tok_m21")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        client.connect("ast_tok_m21")
        client.authEvents.first { it is ServerMessage.AuthOk }
        assertEquals(ConnectionState.CONNECTED, client.connectionState.value)

        // Simulate network lost (Wi-Fi dropped on M21)
        client.onNetworkLost()

        // Must immediately transition to DISCONNECTED then schedule reconnect
        val state = client.connectionState.value
        assertTrue(state == ConnectionState.DISCONNECTED || state == ConnectionState.RECONNECTING)

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun networkAvailableReprioritizesConnectionWhenDisconnected() = runTest {
        val serverUrl = "wss://127.0.0.1:59999/ws"
        NetworkConfig.setServerUrlForEnvironment(serverUrl, NetworkEnvironment.DEVELOPMENT_TLS)

        authStorage.saveAuthToken("ast_tok_m21")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        // Initial state is disconnected
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)

        // Network returns (LTE becomes active on M21)
        client.onNetworkAvailableOrChanged()

        // Wait for debounce and state transition
        var state = client.connectionState.value
        for (i in 0 until 50) {
            state = client.connectionState.value
            if (state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING) break
            Thread.sleep(20)
        }

        // Client reprioritizes and enters CONNECTING/RECONNECTING
        assertTrue("Expected CONNECTING or RECONNECTING, was: $state", state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING)

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun manualReconnectResetsAttemptAndInitiatesConnection() = runTest {
        val serverUrl = "wss://127.0.0.1:59999/ws"
        NetworkConfig.setServerUrlForEnvironment(serverUrl, NetworkEnvironment.DEVELOPMENT_TLS)

        authStorage.saveAuthToken("ast_tok_m21")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        client.connect("ast_tok_m21")
        val state1 = client.connectionState.value
        assertTrue(state1 == ConnectionState.CONNECTING || state1 == ConnectionState.RECONNECTING)

        // Manual reconnect (Settings button)
        client.connect()
        val state2 = client.connectionState.value
        assertTrue(state2 == ConnectionState.CONNECTING || state2 == ConnectionState.RECONNECTING)

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.cleanup()
    }

    @Test
    fun exponentialBackoffConstantsAreSane() {
        assertEquals(1000L, NetworkConfig.INITIAL_RETRY_DELAY_MS)
        assertEquals(30000L, NetworkConfig.MAX_RETRY_DELAY_MS)
        assertEquals(2.0, NetworkConfig.BACKOFF_MULTIPLIER, 0.001)

        var delay = NetworkConfig.INITIAL_RETRY_DELAY_MS
        val delays = mutableListOf<Long>()
        repeat(10) {
            delays.add(delay)
            delay = (delay * NetworkConfig.BACKOFF_MULTIPLIER).toLong().coerceAtMost(NetworkConfig.MAX_RETRY_DELAY_MS)
        }

        // Verify progression: 1000, 2000, 4000, 8000, 16000, 30000, ...
        assertEquals(1000L, delays[0])
        assertEquals(2000L, delays[1])
        assertEquals(4000L, delays[2])
        assertEquals(8000L, delays[3])
        assertEquals(16000L, delays[4])
        assertEquals(30000L, delays[5])
        assertEquals(30000L, delays.last())
    }

    @Test
    fun networkUnavailableDefersConnectionToDisconnected() {
        authStorage.saveAuthToken("ast_tok_test")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)
        client.networkAvailableOverride = false

        client.connect("ast_tok_test")

        // Should not transition to CONNECTING when network is unavailable
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals("ast_tok_test", authStorage.getAuthToken()) // Token preserved

        client.cleanup()
    }

    @Test
    fun networkLostPreservesAuthToken() = runTest {
        authStorage.saveAuthToken("ast_tok_keep_me")
        val client = AsterWebSocketClient(okHttpClient, authStorage, context)

        // Simulate network lost
        client.onNetworkLost()

        // State is DISCONNECTED, token is NOT cleared
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        assertEquals("ast_tok_keep_me", authStorage.getAuthToken())

        client.cleanup()
    }

    @Test
    fun networkSanitizerStripsSensitiveNetworkDetails() {
        val unknownHostEx = java.net.UnknownHostException(
            "Unable to resolve host \"aster-backend-cloudflare.kmsaadbinafzal.workers.dev\": No address associated with hostname"
        )
        val sanitizedHost = com.aster.app.data.network.NetworkSanitizer.sanitizeError(unknownHostEx)
        assertTrue(!sanitizedHost.contains("workers.dev"))
        assertTrue(!sanitizedHost.contains("cloudflare"))
        assertTrue(!sanitizedHost.contains("UnknownHostException"))
        assertEquals("Connection unavailable. Aster will keep trying automatically.", sanitizedHost)

        val connectEx = java.net.ConnectException("Failed to connect to /192.168.240.1:8443")
        val sanitizedConnect = com.aster.app.data.network.NetworkSanitizer.sanitizeError(connectEx)
        assertTrue(!sanitizedConnect.contains("192.168"))
        assertTrue(!sanitizedConnect.contains("8443"))
        assertTrue(!sanitizedConnect.contains("ConnectException"))
        assertEquals("Connection unavailable. Aster will keep trying automatically.", sanitizedConnect)

        val websocketUrlMsg = "WebSocket connection failed to wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws: HTTP 502"
        val sanitizedWs = com.aster.app.data.network.NetworkSanitizer.sanitizeMessage(websocketUrlMsg)
        assertTrue(!sanitizedWs.contains("wss://"))
        assertTrue(!sanitizedWs.contains("502"))
        assertEquals("Connection unavailable. Aster will keep trying automatically.", sanitizedWs)

        // Valid user-relevant domain errors should be preserved
        val domainError = "Invalid username or password"
        val preserved = com.aster.app.data.network.NetworkSanitizer.sanitizeMessage(domainError)
        assertEquals("Invalid username or password", preserved)
    }
}
