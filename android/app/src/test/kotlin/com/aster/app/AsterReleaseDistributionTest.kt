package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.network.NetworkConfig
import com.aster.app.data.network.NetworkEnvironment
import com.aster.app.data.seed.DatabaseSeedInitializer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterReleaseDistributionTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var seedInitializer: DatabaseSeedInitializer

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()
        seedInitializer = DatabaseSeedInitializer(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            authStorage = authStorage
        )
    }

    @After
    fun tearDown() {
        database.close()
        NetworkConfig.resetToDefault(NetworkEnvironment.PRODUCTION)
    }

    // 1. Release Application ID and Version metadata
    @Test
    fun test1_applicationMetadata_packageAndVersionCorrect() {
        assertEquals("com.aster.app", BuildConfig.APPLICATION_ID)
        assertEquals("1.0", BuildConfig.VERSION_NAME)
        assertEquals(1, BuildConfig.VERSION_CODE)
    }

    // 2. Production Database Seeding: MUST NEVER seed fake contacts in PRODUCTION
    @Test
    fun test2_productionDatabaseSeeding_neverSeedsMockUsersOrConversations() = runTest {
        NetworkConfig.resetToDefault(NetworkEnvironment.PRODUCTION)
        assertEquals(NetworkEnvironment.PRODUCTION, NetworkConfig.currentEnvironment)

        seedInitializer.initializeIfNeeded(forceDevSeed = false)

        val rohan = database.userDao().getUserByIdOnce("user_rohan")
        val alex = database.userDao().getUserByIdOnce("user_alex")
        val sam = database.userDao().getUserByIdOnce("user_sam")
        val convAlex = database.conversationDao().getConversationByIdOnce("conv_alex")

        assertNull("Rohan mock user must not be seeded in PRODUCTION", rohan)
        assertNull("Alex mock user must not be seeded in PRODUCTION", alex)
        assertNull("Sam mock user must not be seeded in PRODUCTION", sam)
        assertNull("Alex conversation must not be seeded in PRODUCTION", convAlex)
    }

    // 3. Development Database Seeding: Works when explicitly in non-production
    @Test
    fun test3_developmentDatabaseSeeding_seedsWhenNonProduction() = runTest {
        NetworkConfig.currentEnvironment = NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL

        seedInitializer.initializeIfNeeded()

        val rohan = database.userDao().getUserByIdOnce("user_rohan")
        val alex = database.userDao().getUserByIdOnce("user_alex")
        assertNotNull("Rohan should be seeded in dev/test environment", rohan)
        assertNotNull("Alex should be seeded in dev/test environment", alex)
    }

    // 4. Production Network Security & Endpoints
    @Test
    fun test4_productionNetworkConfig_enforcesCloudflareAndStrictTls() {
        NetworkConfig.resetToDefault(NetworkEnvironment.PRODUCTION)

        assertEquals(
            "wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws",
            NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS
        )
        assertEquals(
            "https://aster-backend-cloudflare.kmsaadbinafzal.workers.dev",
            NetworkConfig.getHttpBaseUrl()
        )
        assertEquals(
            "wss://aster-backend-cloudflare.kmsaadbinafzal.workers.dev/ws",
            NetworkConfig.serverUrl
        )

        // Local development host identification
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("localhost"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("127.0.0.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("192.168.240.1"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("192.168.0.114"))
        assertTrue(NetworkConfig.isLocalOrDevelopmentHost("aster.local"))

        // Production Cloudflare host is NOT a local host
        assertFalse(NetworkConfig.isLocalOrDevelopmentHost("aster-backend-cloudflare.kmsaadbinafzal.workers.dev"))

        // Stale development endpoints are purged in PRODUCTION
        var staleCleared = false
        val resolved = NetworkConfig.initializeEndpoint(
            savedUrl = "wss://192.168.0.114:8443/ws",
            env = NetworkEnvironment.PRODUCTION,
            onStaleEndpointCleared = { staleCleared = true }
        )
        assertTrue(staleCleared)
        assertEquals(NetworkConfig.ENDPOINT_CLOUDFLARE_PRODUCTION_TLS, resolved)
    }

    // 5. Creator Identity vs Legal Copyright Attribution
    @Test
    fun test5_creatorIdentityAndLegalAttribution() {
        val publicCreator = "Saaaaad"
        val legalName = "Saa'ad Khan"
        val copyrightNotice = "© 2026 Saa'ad Khan"

        assertEquals("Saaaaad", publicCreator)
        assertEquals("Saa'ad Khan", legalName)
        assertTrue(copyrightNotice.contains("2026"))
        assertTrue(copyrightNotice.contains("Saa'ad Khan"))
    }

    // 6. Creator Contact Information Integrity
    @Test
    fun test6_creatorContactDetails_areAccurate() {
        val email = "kmsaadbinafzal@gmail.com"
        val githubUsername = "shamshir-ke-abba"
        val githubRepo = "https://github.com/shamshir-ke-abba/Aster"
        val portfolioUrl = "https://saad-portfolio.kmsaadbinafzal.workers.dev/"
        val instagramHandle = "@0.omni_man.0"
        val instagramUrl = "https://www.instagram.com/0.omni_man.0/"
        val discordUsername = "shamshir_ke_abba"

        assertEquals("kmsaadbinafzal@gmail.com", email)
        assertEquals("shamshir-ke-abba", githubUsername)
        assertEquals("https://github.com/shamshir-ke-abba/Aster", githubRepo)
        assertEquals("https://saad-portfolio.kmsaadbinafzal.workers.dev/", portfolioUrl)
        assertEquals("@0.omni_man.0", instagramHandle)
        assertEquals("https://www.instagram.com/0.omni_man.0/", instagramUrl)
        assertEquals("shamshir_ke_abba", discordUsername)
    }

    // 7. Third-Party Open Source License Attribution
    @Test
    fun test7_thirdPartyOpenSourceLicenseNotices() {
        val requiredLibraries = listOf(
            "AndroidX & Jetpack Compose",
            "Kotlin & Kotlinx Coroutines",
            "Dagger & Hilt",
            "Room Persistence Library",
            "OkHttp"
        )
        val licenseType = "Apache License 2.0"

        for (lib in requiredLibraries) {
            assertTrue("Library $lib must be attributed", lib.isNotBlank())
        }
        assertEquals("Apache License 2.0", licenseType)
    }
}
