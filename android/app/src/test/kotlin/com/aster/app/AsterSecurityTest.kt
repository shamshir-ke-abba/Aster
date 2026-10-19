package com.aster.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.network.AsterTlsManager
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.network.NetworkEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AsterSecurityTest {

    private lateinit var context: Context
    private val originalUrl = NetworkConfig.serverUrl
    private val originalEnv = NetworkConfig.currentEnvironment

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        NetworkConfig.currentEnvironment = NetworkEnvironment.PRODUCTION
    }

    @After
    fun tearDown() {
        NetworkConfig.setServerUrlForEnvironment(originalUrl, originalEnv)
    }

    @Test
    fun productionRejectsPlaintextWsUrl() {
        NetworkConfig.currentEnvironment = NetworkEnvironment.PRODUCTION

        try {
            NetworkConfig.serverUrl = "ws://192.168.0.159:8080/ws"
            fail("Expected IllegalArgumentException when configuring ws:// in PRODUCTION")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("requires wss://") == true)
        }
    }

    @Test
    fun productionRejectsPlaintextWsInSetServerUrlForEnvironment() {
        try {
            NetworkConfig.setServerUrlForEnvironment("ws://127.0.0.1:8080/ws", NetworkEnvironment.PRODUCTION)
            fail("Expected IllegalArgumentException when configuring ws:// in PRODUCTION")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("requires wss://") == true)
        }
    }

    @Test
    fun developmentTlsRejectsPlaintextWsUrl() {
        try {
            NetworkConfig.setServerUrlForEnvironment("ws://192.168.240.1:8080/ws", NetworkEnvironment.DEVELOPMENT_TLS)
            fail("Expected IllegalArgumentException when configuring ws:// in DEVELOPMENT_TLS")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("requires wss://") == true)
        }
    }

    @Test
    fun productionAllowsValidWssUrl() {
        val validWss = "wss://192.168.0.159:8443/ws"
        NetworkConfig.setServerUrlForEnvironment(validWss, NetworkEnvironment.PRODUCTION)
        assertEquals(validWss, NetworkConfig.serverUrl)
        assertEquals(NetworkEnvironment.PRODUCTION, NetworkConfig.currentEnvironment)
    }

    @Test
    fun productionRejectsUrlWithUserCredentials() {
        try {
            NetworkConfig.serverUrl = "wss://user:pass@192.168.0.159:8443/ws"
            fail("Expected IllegalArgumentException when configuring URL with user credentials")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("user credentials") == true)
        }
    }

    @Test
    fun productionRejectsUrlWithMissingHost() {
        try {
            NetworkConfig.serverUrl = "wss:///ws"
            fail("Expected IllegalArgumentException when configuring URL without host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-empty host") == true)
        }
    }

    @Test
    fun productionRejectsUrlWithInvalidPort() {
        try {
            NetworkConfig.serverUrl = "wss://192.168.0.159:99999/ws"
            fail("Expected IllegalArgumentException when configuring URL with invalid port")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("port out of range") == true)
        }
    }

    @Test
    fun productionRejectsMalformedUri() {
        try {
            NetworkConfig.serverUrl = "wss://[invalid"
            fail("Expected IllegalArgumentException when configuring malformed URI")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Malformed server URI") == true)
        }
    }

    @Test
    fun developmentUnsecuredExplicitlyAllowsPlaintextWsForTests() {
        val devWs = "ws://127.0.0.1:8080/ws"
        NetworkConfig.setServerUrlForEnvironment(devWs, NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL)
        assertEquals(devWs, NetworkConfig.serverUrl)
        assertEquals(NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL, NetworkConfig.currentEnvironment)
    }

    @Test
    fun asterTlsManagerLoadsRootCaSuccessfully() {
        val caStream = context.resources.openRawResource(R.raw.aster_ca)
        val tlsConfig = AsterTlsManager.createTlsConfiguration(caStream)

        assertNotNull(tlsConfig.sslSocketFactory)
        assertNotNull(tlsConfig.trustManager)
        assertTrue(tlsConfig.trustManager.acceptedIssuers.isNotEmpty())

        val issuer = tlsConfig.trustManager.acceptedIssuers[0]
        assertTrue(issuer.subjectX500Principal.name.contains("Aster Root CA"))
    }

    @Test
    fun dualTrustManagerConfiguredWithAsterAndSystemCAs() {
        val caStream = context.resources.openRawResource(R.raw.aster_ca)
        val tlsConfig = AsterTlsManager.createTlsConfiguration(caStream)

        assertTrue(tlsConfig.trustManager is com.aster.app.data.network.DualTrustManager)
        val dualTm = tlsConfig.trustManager as com.aster.app.data.network.DualTrustManager
        assertTrue(dualTm.customTrustManager.acceptedIssuers.any { it.subjectX500Principal.name.contains("Aster Root CA") })
        assertNotNull(dualTm.systemTrustManager)
    }

    @Test
    fun tlsClientRejectsUntrustedServerCertificate() {
        // Load Aster Root CA config
        val caStream = context.resources.openRawResource(R.raw.aster_ca)
        val tlsConfig = AsterTlsManager.createTlsConfiguration(caStream)

        val secureClient = OkHttpClient.Builder()
            .sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
            .build()

        // MockWebServer without our custom cert (generates a default untrusted self-signed cert)
        val server = MockWebServer()
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(
            okhttp3.tls.HeldCertificate.Builder().build()
        ).build().sslSocketFactory(), false)
        server.start()

        val request = Request.Builder()
            .url("https://localhost:${server.port}/")
            .build()

        try {
            secureClient.newCall(request).execute()
            fail("Expected SSLHandshakeException when connecting to server with untrusted cert")
        } catch (e: Exception) {
            val isHandshake = generateSequence<Throwable>(e) { it.cause }
                .any { it is SSLHandshakeException || it.message?.contains("CertPathValidatorException") == true }
            assertTrue("Expected SSLHandshakeException, got: $e", isHandshake)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun tlsClientRejectsWrongHostname() {
        val caStream = context.resources.openRawResource(R.raw.aster_ca)
        val tlsConfig = AsterTlsManager.createTlsConfiguration(caStream)

        val secureClient = OkHttpClient.Builder()
            .sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)
            .build()

        // Generate cert for "wrong.domain.local" signed by a test CA
        val testCa = okhttp3.tls.HeldCertificate.Builder()
            .certificateAuthority(1)
            .build()

        // Create trust manager for test CA
        val testTrustManager = okhttp3.tls.HandshakeCertificates.Builder()
            .addTrustedCertificate(testCa.certificate)
            .build()

        val serverCert = okhttp3.tls.HeldCertificate.Builder()
            .signedBy(testCa)
            .commonName("wrong.domain.local") // Does NOT match localhost / 127.0.0.1
            .build()

        val server = MockWebServer()
        server.useHttps(okhttp3.tls.HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build().sslSocketFactory(), false)
        server.start()

        val clientWithMatchingCa = OkHttpClient.Builder()
            .sslSocketFactory(testTrustManager.sslSocketFactory(), testTrustManager.trustManager)
            .build()

        val request = Request.Builder()
            .url("https://localhost:${server.port}/") // Host is localhost, but cert is for wrong.domain.local
            .build()

        try {
            clientWithMatchingCa.newCall(request).execute()
            fail("Expected SSLPeerUnverifiedException for hostname mismatch")
        } catch (e: Exception) {
            val isHostnameMismatch = generateSequence<Throwable>(e) { it.cause }
                .any { it is SSLPeerUnverifiedException || it.message?.contains("Hostname localhost not verified") == true }
            assertTrue("Expected SSLPeerUnverifiedException, got: $e", isHostnameMismatch)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun webSocketClientTransitionsToReconnectingWhenTlsEndpointUnavailable() = runTest {
        NetworkConfig.setServerUrlForEnvironment("wss://127.0.0.1:59999/ws", NetworkEnvironment.PRODUCTION)
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val authStorage = com.aster.app.data.auth.AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_saad")
        val client = AsterWebSocketClient(OkHttpClient(), authStorage)

        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
        client.connect("ast_tok_test_saad")

        // State moves to CONNECTING/RECONNECTING without crashing
        val state = client.connectionState.value
        assertTrue(state == ConnectionState.CONNECTING || state == ConnectionState.RECONNECTING)

        client.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, client.connectionState.value)
    }
}
