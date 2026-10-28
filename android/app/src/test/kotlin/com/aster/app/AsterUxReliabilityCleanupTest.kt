package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.domain.model.sanitizeDisplayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class AsterUxReliabilityCleanupTest {

    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()

        mockWebServer = MockWebServer()
        okHttpClient = OkHttpClient.Builder()
            .readTimeout(3, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    @After
    fun tearDown() {
        try {
            mockWebServer.shutdown()
        } catch (_: Exception) {}
        database.close()
    }

    // ---------------------------------------------------------
    // 1. Identity & Greeting Tests
    // ---------------------------------------------------------

    @Test
    fun sanitizeDisplayName_preservesGenuineDisplayNames() {
        assertEquals("Saad", sanitizeDisplayName("Saad", "user_12345"))
        assertEquals("Alex Mercer", sanitizeDisplayName("Alex Mercer", "user_67890"))
        assertEquals("Elena Rostova", sanitizeDisplayName("Elena Rostova", "user_e9eefbaob70"))
        assertEquals("Nova_99", sanitizeDisplayName("Nova_99", "user_112233"))
    }

    @Test
    fun sanitizeDisplayName_rejectsInternalIdentifiersAndSubstrings() {
        // Raw internal ID matching userId
        assertNull(sanitizeDisplayName("user_e9eefbaob70", "user_e9eefbaob70"))
        assertNull(sanitizeDisplayName("E9eefbaob70", "user_e9eefbaob70"))
        assertNull(sanitizeDisplayName("e9eefbaob70", "user_e9eefbaob70"))

        // Prefixed with user_
        assertNull(sanitizeDisplayName("user_random_hash", "user_other"))

        // Hex hashes / UUID-like internal IDs
        assertNull(sanitizeDisplayName("e9eefbaob70", null))
        assertNull(sanitizeDisplayName("4f8a29b1c0e3", null))
        assertNull(sanitizeDisplayName("1234567890abcdef", null))

        // Empty / whitespace
        assertNull(sanitizeDisplayName("", null))
        assertNull(sanitizeDisplayName("   ", null))
        assertNull(sanitizeDisplayName(null, null))
    }

    @Test
    fun greetingText_usesDisplayNameWhenAvailable_andFallsBackWithoutInternalId() {
        fun buildGreeting(timeGreeting: String, rawName: String?, userId: String?): String {
            val clean = sanitizeDisplayName(rawName, userId)
            return if (!clean.isNullOrBlank()) "$timeGreeting, $clean" else timeGreeting
        }

        // With valid display name
        assertEquals("Good afternoon, Saad", buildGreeting("Good afternoon", "Saad", "user_e9eefbaob70"))
        assertEquals("Good morning, Alex", buildGreeting("Good morning", "Alex", "user_112233"))

        // With internal ID (MUST NEVER LEAK INTO GREETING)
        assertEquals("Good afternoon", buildGreeting("Good afternoon", "E9eefbaob70", "user_e9eefbaob70"))
        assertEquals("Good evening", buildGreeting("Good evening", "user_e9eefbaob70", "user_e9eefbaob70"))
        assertEquals("Good morning", buildGreeting("Good morning", null, "user_e9eefbaob70"))
    }

    @Test
    fun userRepository_setAuthenticatedUser_persistsSanitizedDisplayName() = runTest {
        val repo = UserRepositoryImpl(database.userDao(), authStorage)

        // 1. Initial authentication with internal ID only -> should not store internal ID as display name
        repo.setAuthenticatedUser("user_e9eefbaob70", null)
        val user1 = repo.getLocalUser().first()
        assertNotNull(user1)
        assertEquals("user_e9eefbaob70", user1?.id)
        assertTrue("Display name should be blank rather than leaking internal ID", user1?.displayName.isNullOrBlank())

        // 2. User sets chosen display name -> Room immediately updates
        authStorage.saveDisplayName("Saad")
        repo.setAuthenticatedUser("user_e9eefbaob70", "Saad")
        val user2 = repo.getLocalUser().first()
        assertNotNull(user2)
        assertEquals("Saad", user2?.displayName)
    }

    // ---------------------------------------------------------
    // 2. Error Sanitization & Privacy Tests
    // ---------------------------------------------------------

    @Test
    fun apiClient_sanitizesNetworkErrorsWithoutExposingIpsPortsOrHostnames() = runTest {
        val apiClient = AsterApiClient(okHttpClient, authStorage)

        // Point to an unavailable port to trigger connection exception
        com.aster.app.data.network.NetworkConfig.setServerUrlForEnvironment(
            "wss://127.0.0.1:59998/ws",
            com.aster.app.data.network.NetworkEnvironment.DEVELOPMENT_TLS
        )

        val result = apiClient.getProfile()
        assertTrue("API call should return Error", result is ApiResult.Error)
        val errorMsg = (result as ApiResult.Error).message

        // Verify technical details are NEVER exposed
        assertFalse("Error message must not expose raw IP", errorMsg.contains("127.0.0.1") || errorMsg.contains("192.168."))
        assertFalse("Error message must not expose port numbers", errorMsg.contains("59998") || errorMsg.contains("8443"))
        assertFalse("Error message must not expose schemes", errorMsg.contains("http://") || errorMsg.contains("wss://"))
        assertFalse("Error message must not expose Cloudflare domain", errorMsg.contains("aster-backend-cloudflare"))
        assertFalse("Error message must not expose raw Exception class name", errorMsg.contains("ConnectException"))

        // Verify clean user-safe message
        assertEquals("Connection unavailable. Aster will keep trying automatically.", errorMsg)
    }

    // ---------------------------------------------------------
    // 3. Background Sync Service & Boot Receiver Tests
    // ---------------------------------------------------------

    @Test
    fun syncNotificationChannel_isCreatedWithLowImportanceForSilentOngoingState() {
        val notifManager = com.aster.app.ui.notification.AsterNotificationManager(context)
        notifManager.initChannels()

        val sysMgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val syncChannel = sysMgr.getNotificationChannel(com.aster.app.ui.notification.AsterNotificationManager.CHANNEL_ID_SYNC)
        assertNotNull(syncChannel)
        assertEquals(android.app.NotificationManager.IMPORTANCE_LOW, syncChannel.importance)
        assertFalse(syncChannel.canShowBadge())
    }

    @Test
    fun buildSyncNotification_createsOngoingLowPriorityForegroundNotification() {
        val notifManager = com.aster.app.ui.notification.AsterNotificationManager(context)
        val notification = notifManager.buildSyncNotification()

        assertNotNull(notification)
        assertEquals(com.aster.app.ui.notification.AsterNotificationManager.CHANNEL_ID_SYNC, notification.channelId)
        assertTrue("Foreground sync notification must be ongoing", (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull("Notification must have content intent to return to Aster", notification.contentIntent)
    }

    @Test
    fun syncService_canBeInstantiatedAndStarted() {
        val intent = android.content.Intent(context, com.aster.app.service.AsterSyncService::class.java)
        assertNotNull(intent)
        assertEquals(com.aster.app.service.AsterSyncService::class.java.name, intent.component?.className)

        // Starting and stopping service helper methods must execute without throwing
        com.aster.app.service.AsterSyncService.start(context)
        com.aster.app.service.AsterSyncService.stop(context)
    }

    @Test
    fun bootReceiver_canBeInstantiatedAndHandlesBootCompleted() {
        val receiver = com.aster.app.service.BootReceiver()
        assertNotNull(receiver)

        val bootIntent = android.content.Intent(android.content.Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, bootIntent)
    }
}
