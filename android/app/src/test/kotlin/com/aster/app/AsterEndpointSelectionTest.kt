package com.aster.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.network.NetworkEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterEndpointSelectionTest {

    private lateinit var context: Context
    private lateinit var authStorage: AsterAuthStorage
    private val originalUrl = NetworkConfig.serverUrl
    private val originalEnv = NetworkConfig.currentEnvironment

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        authStorage = AsterAuthStorage(context)
        authStorage.clearServerUrl()
        authStorage.clearAuthToken()
        NetworkConfig.resetToDefault(NetworkEnvironment.PRODUCTION)
    }

    @After
    fun tearDown() {
        authStorage.clearServerUrl()
        authStorage.clearAuthToken()
        NetworkConfig.setServerUrlForEnvironment(originalUrl, originalEnv)
    }

    // 1. Fresh production startup selects Cloudflare
    @Test
    fun test1_freshProductionStartup_selectsCloudflare() {
        assertNull(authStorage.getServerUrl())

        val resolved = NetworkConfig.initializeEndpoint(
            savedUrl = authStorage.getServerUrl(),
            env = NetworkEnvironment.PRODUCTION,
            onStaleEndpointCleared = { authStorage.clearServerUrl() }
        )

        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, resolved)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, NetworkConfig.serverUrl)
        assertEquals(
            "https://aster-backend-cloudflare.kmsaadbinafzal.workers.dev",
            NetworkConfig.getHttpBaseUrl()
        )
        assertNull(authStorage.getServerUrl())
    }

    // 2. Production startup with a stale saved LAN endpoint still selects Cloudflare
    @Test
    fun test2_productionStartup_withStaleSavedLanEndpoint_selectsCloudflareAndClearsStorage() {
        // Simulates the exact reproduction on Samsung Galaxy M21 with saved physical LAN endpoint
        val staleLan = "wss://192.168.0.114:8443/ws"
        authStorage.saveServerUrl(staleLan)
        assertEquals(staleLan, authStorage.getServerUrl())

        var clearedInvoked = false
        val resolved = NetworkConfig.initializeEndpoint(
            savedUrl = authStorage.getServerUrl(),
            env = NetworkEnvironment.PRODUCTION,
            onStaleEndpointCleared = {
                clearedInvoked = true
                authStorage.clearServerUrl()
            }
        )

        assertTrue("Stale endpoint callback must be invoked", clearedInvoked)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, resolved)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, NetworkConfig.serverUrl)
        assertEquals(
            "https://aster-backend-cloudflare.kmsaadbinafzal.workers.dev",
            NetworkConfig.getHttpBaseUrl()
        )
        assertNull("Stale LAN URL must be removed from AsterAuthStorage", authStorage.getServerUrl())
    }

    // 3. Production startup with a stale Waydroid endpoint still selects Cloudflare
    @Test
    fun test3_productionStartup_withStaleWaydroidEndpoint_selectsCloudflareAndClearsStorage() {
        val staleWaydroid = "wss://192.168.240.1:8443/ws"
        authStorage.saveServerUrl(staleWaydroid)
        assertEquals(staleWaydroid, authStorage.getServerUrl())

        var clearedInvoked = false
        val resolved = NetworkConfig.initializeEndpoint(
            savedUrl = authStorage.getServerUrl(),
            env = NetworkEnvironment.PRODUCTION,
            onStaleEndpointCleared = {
                clearedInvoked = true
                authStorage.clearServerUrl()
            }
        )

        assertTrue(clearedInvoked)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, resolved)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, NetworkConfig.serverUrl)
        assertNull(authStorage.getServerUrl())
    }

    // 4. Production startup with stale localhost and other local endpoints selects Cloudflare
    @Test
    fun test4_productionStartup_withStaleLocalhostAndOtherDevEndpoints_selectsCloudflare() {
        val staleEndpoints = listOf(
            "wss://127.0.0.1:8443/ws",
            "wss://localhost:8443/ws",
            "wss://aster.local:8443/ws",
            "wss://192.168.0.159:8443/ws",
            "wss://10.0.2.2:8443/ws",
            "wss://172.20.10.2:8443/ws"
        )

        for (stale in staleEndpoints) {
            authStorage.saveServerUrl(stale)
            assertEquals(stale, authStorage.getServerUrl())

            val resolved = NetworkConfig.initializeEndpoint(
                savedUrl = authStorage.getServerUrl(),
                env = NetworkEnvironment.PRODUCTION,
                onStaleEndpointCleared = { authStorage.clearServerUrl() }
            )

            assertEquals("Failed for $stale", NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, resolved)
            assertEquals("Failed for $stale", NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, NetworkConfig.serverUrl)
            assertNull("Storage not cleared for $stale", authStorage.getServerUrl())
        }
    }

    // 5. Development TLS behavior remains functional
    @Test
    fun test5_developmentTls_preservesConfiguredAndDefaultEndpoints() {
        // Startup in DEVELOPMENT_TLS with saved LAN endpoint preserves it
        val devLan = "wss://192.168.0.114:8443/ws"
        authStorage.saveServerUrl(devLan)

        var clearedInvoked = false
        val resolvedDev = NetworkConfig.initializeEndpoint(
            savedUrl = authStorage.getServerUrl(),
            env = NetworkEnvironment.DEVELOPMENT_TLS,
            onStaleEndpointCleared = { clearedInvoked = true }
        )

        assertFalse("DEVELOPMENT_TLS must NOT trigger stale endpoint clearance for dev server", clearedInvoked)
        assertEquals(devLan, resolvedDev)
        assertEquals(devLan, NetworkConfig.serverUrl)
        assertEquals("https://192.168.0.114:8443", NetworkConfig.getHttpBaseUrl())
        assertEquals(devLan, authStorage.getServerUrl())

        // Startup in DEVELOPMENT_TLS with saved Waydroid endpoint preserves it
        val devWaydroid = "wss://192.168.240.1:8443/ws"
        authStorage.saveServerUrl(devWaydroid)

        val resolvedWaydroid = NetworkConfig.initializeEndpoint(
            savedUrl = authStorage.getServerUrl(),
            env = NetworkEnvironment.DEVELOPMENT_TLS,
            onStaleEndpointCleared = { fail("Should not clear in DEVELOPMENT_TLS") }
        )

        assertEquals(devWaydroid, resolvedWaydroid)
        assertEquals(devWaydroid, NetworkConfig.serverUrl)

        // Fresh startup in DEVELOPMENT_TLS with no saved URL resolves default
        authStorage.clearServerUrl()
        val defaultDev = NetworkConfig.initializeEndpoint(
            savedUrl = null,
            env = NetworkEnvironment.DEVELOPMENT_TLS
        )
        assertEquals(NetworkConfig.defaultEndpointForDevice(NetworkEnvironment.DEVELOPMENT_TLS), defaultDev)
    }

    // 6. Explicit production configuration handled deliberately and safely
    @Test
    fun test6_explicitProductionConfiguration_handledDeliberatelyAndSafely() {
        // Explicitly setting Cloudflare production endpoint
        NetworkConfig.setServerUrlForEnvironment(
            NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS,
            NetworkEnvironment.PRODUCTION
        )
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, NetworkConfig.serverUrl)

        // Explicitly setting valid custom public production endpoint
        val validPublicProd = "wss://relay.aster.chat:8443/ws"
        NetworkConfig.setServerUrlForEnvironment(validPublicProd, NetworkEnvironment.PRODUCTION)
        assertEquals(validPublicProd, NetworkConfig.serverUrl)
        assertEquals("https://relay.aster.chat:8443", NetworkConfig.getHttpBaseUrl())

        // Insecure plaintext ws:// is rejected in PRODUCTION
        try {
            NetworkConfig.setServerUrlForEnvironment("ws://relay.aster.chat:8080/ws", NetworkEnvironment.PRODUCTION)
            fail("Expected IllegalArgumentException for cleartext ws:// in PRODUCTION")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("requires wss://") == true)
        }

        // Malformed or credential-carrying URIs rejected
        try {
            NetworkConfig.serverUrl = "wss://admin:secret@relay.aster.chat:8443/ws"
            fail("Expected IllegalArgumentException for credentials in URI")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("user credentials") == true)
        }
    }

    // 7. Unit tests for isLocalOrDevelopmentHost & isLocalOrDevelopmentEndpoint
    @Test
    fun test7_isLocalOrDevelopmentHost_accuratelyIdentifiesAllLocalRanges() {
        // Local loopback
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("127.0.0.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("127.0.1.10"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("localhost"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("::1"))

        // RFC 1918 Private ranges
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("10.0.0.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("10.255.255.255"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("172.16.0.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("172.25.1.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("172.31.255.255"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("192.168.0.114"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("192.168.240.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("192.168.0.159"))

        // Link-local
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("169.254.1.1"))

        // Local domains
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("aster.local"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("device.lan"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("server.internal"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("home.home"))

        // Public domains and cellular IPs
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("aster-backend-cloudflare.kmsaadbinafzal.workers.dev"))
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("nat-validation-surely-receipt.trycloudflare.com"))
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("relay.aster.chat"))
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("100.89.74.120")) // Airtel LTE CGNAT IP
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("8.8.8.8"))
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("1.1.1.1"))
    }

    // 8. MainActivity server intent-extra handling protects PRODUCTION from stale LAN endpoints
    @Test
    fun test8_mainActivity_rejectsStaleLanOverrideInProduction() {
        val activityController = Robolectric.buildActivity(MainActivity::class.java)
        val activity = activityController.get()
        activity.authStorage = authStorage

        // Set environment to PRODUCTION
        NetworkConfig.currentEnvironment = NetworkEnvironment.PRODUCTION
        NetworkConfig.serverUrl = NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS

        // 8a. Deep link with stale LAN server in PRODUCTION is ignored
        val staleLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("aster://config?server=wss://192.168.0.114:8443/ws"))
        activity.handleServerOverrideIntent(staleLinkIntent)

        assertEquals(
            "Production serverUrl must NOT be overwritten by LAN server in link",
            NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS,
            NetworkConfig.serverUrl
        )
        assertNull("Stale LAN server must NOT be persisted to AsterAuthStorage", authStorage.getServerUrl())

        // 8b. Intent extra 'aster_server' with stale LAN in PRODUCTION is ignored
        val staleExtraIntent = Intent().apply {
            putExtra("aster_server", "wss://192.168.0.114:8443/ws")
        }
        activity.handleServerOverrideIntent(staleExtraIntent)

        assertEquals(
            "Production serverUrl must NOT be overwritten by aster_server extra",
            NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS,
            NetworkConfig.serverUrl
        )
        assertNull(authStorage.getServerUrl())

        // 8c. In DEVELOPMENT_TLS, link with LAN server is accepted and persisted
        NetworkConfig.currentEnvironment = NetworkEnvironment.DEVELOPMENT_TLS
        val devLinkIntent = Intent(Intent.ACTION_VIEW, Uri.parse("aster://config?server=wss://192.168.0.114:8443/ws"))
        activity.handleServerOverrideIntent(devLinkIntent)

        assertEquals(
            "DEVELOPMENT_TLS must allow configuring LAN server",
            "wss://192.168.0.114:8443/ws",
            NetworkConfig.serverUrl
        )
        assertEquals("wss://192.168.0.114:8443/ws", authStorage.getServerUrl())
    }
}
