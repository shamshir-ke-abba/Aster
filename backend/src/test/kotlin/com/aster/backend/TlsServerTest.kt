package com.aster.backend

import com.aster.backend.database.MailboxDatabase
import io.ktor.server.netty.Netty
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector
import io.ktor.server.engine.embeddedServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TlsServerTest {

    private val httpsPort = 18443
    private val httpPort = 18080
    private lateinit var server: io.ktor.server.engine.ApplicationEngine
    private val keyStoreFile = File("../certs/aster_server.p12")
    private val caCertFile = File("../certs/aster_ca.crt")

    private val mailboxDatabase = MailboxDatabase(":memory:")

    @BeforeAll
    fun setup() {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            keyStoreFile.inputStream().use { load(it, "aster_keystore_secret".toCharArray()) }
        }

        val env = applicationEngineEnvironment {
            connector {
                host = "0.0.0.0"
                port = httpPort
            }
            sslConnector(
                keyStore = keyStore,
                keyAlias = "aster-server",
                keyStorePassword = { "aster_keystore_secret".toCharArray() },
                privateKeyPassword = { "aster_keystore_secret".toCharArray() }
            ) {
                host = "0.0.0.0"
                port = httpsPort
                this.keyStorePath = keyStoreFile
            }
            module {
                asterModule(mailboxDatabase = mailboxDatabase)
            }
        }
        server = embeddedServer(Netty, env).start(wait = false)
        // Give server a moment to bind
        Thread.sleep(1000)
    }

    @AfterAll
    fun tearDown() {
        server.stop(1000, 2000)
    }

    private fun createSslContext(caFile: File?): SSLContext {
        val sslContext = SSLContext.getInstance("TLS")
        if (caFile == null) {
            sslContext.init(null, null, null) // Default system trust store
            return sslContext
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            val cert = CertificateFactory.getInstance("X.509").generateCertificate(caFile.inputStream())
            setCertificateEntry("ca", cert)
        }
        tmf.init(trustStore)
        sslContext.init(null, tmf.trustManagers, null)
        return sslContext
    }

    @Test
    fun testTlsConnectionWithValidRootCaSucceeds() {
        val sslContext = createSslContext(caCertFile)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:$httpsPort/health"))
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"status\":\"ok\""))
    }

    @Test
    fun testTlsWebSocketUpgradeOverWssSucceeds() {
        val sslContext = createSslContext(caCertFile)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val token = kotlinx.coroutines.runBlocking { mailboxDatabase.createToken("user_saad") }
        val authReceived = CompletableFuture<String>()
        val wsFuture = client.newWebSocketBuilder()
            .buildAsync(URI.create("wss://localhost:$httpsPort/ws"), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    webSocket.sendText("""{"type":"AUTH","token":"$token"}""", true)
                    webSocket.request(1)
                }

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    authReceived.complete(data.toString())
                    return null
                }
            })

        val ws = wsFuture.get(5, TimeUnit.SECONDS)
        val authResponse = authReceived.get(5, TimeUnit.SECONDS)
        assertTrue(authResponse.contains("\"AUTH_OK\""), "Expected AUTH_OK over WSS: $authResponse")
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "Done")
    }

    @Test
    fun testTlsConnectionWithoutCustomCaFailsWithUntrustedCert() {
        // Default system trust store does not trust our Aster Root CA
        val sslContext = createSslContext(null)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://localhost:$httpsPort/health"))
            .GET()
            .build()

        val ex = assertFailsWith<Exception> {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
        val isHandshakeFailure = generateSequence<Throwable>(ex) { it.cause }
            .any { it is SSLHandshakeException || it.message?.contains("PKIX") == true || it.message?.contains("validator") == true }
        assertTrue(isHandshakeFailure, "Expected SSLHandshakeException for untrusted CA, got: $ex")
    }

    @Test
    fun testTlsConnectionWithWrongHostnameFails() {
        // Uses valid Aster Root CA, but targets 127.0.0.2 which is not in the SAN
        val sslContext = createSslContext(caCertFile)
        val client = HttpClient.newBuilder()
            .sslContext(sslContext)
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://127.0.0.2:$httpsPort/health"))
            .GET()
            .build()

        val ex = assertFailsWith<Exception> {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        }
        val isHostnameFailure = generateSequence<Throwable>(ex) { it.cause }
            .any {
                it is javax.net.ssl.SSLPeerUnverifiedException ||
                it is SSLHandshakeException ||
                it.message?.contains("Certificate for") == true ||
                it.message?.contains("No subject alternative") == true ||
                it.message?.contains("doesn't match") == true ||
                it.message?.contains("No name matching") == true
            }
        assertTrue(isHostnameFailure, "Expected hostname mismatch verification failure, got: $ex")
    }
}
