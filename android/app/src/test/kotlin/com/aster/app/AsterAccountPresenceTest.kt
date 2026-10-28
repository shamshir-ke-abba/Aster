package com.aster.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.entities.UserEntity
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ServerMessage
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.domain.model.User
import com.aster.app.domain.model.formatPresenceText
import com.aster.app.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AsterAccountPresenceTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var webSocketClient: AsterWebSocketClient
    private lateinit var userRepository: UserRepositoryImpl

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        val directExecutor = java.util.concurrent.Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .setQueryExecutor(directExecutor)
            .setTransactionExecutor(directExecutor)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.saveAuthToken("ast_tok_test_saad")
        authStorage.saveAuthenticatedUserId("user_saad")
        authStorage.saveUsername("saad")
        authStorage.saveDisplayName("Saad")

        webSocketClient = AsterWebSocketClient(OkHttpClient(), authStorage)
        userRepository = UserRepositoryImpl(
            userDao = database.userDao(),
            authStorage = authStorage,
            webSocketClient = webSocketClient,
            ioDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------------------
    // 1. Presence Formatting and Priority Rules (Req 14, 16, 23)
    // -------------------------------------------------------------------------

    @Test
    fun testPresence_deletedUserOverridesPresence() {
        // When user is deleted, presence must always resolve to deleted state
        // regardless of isOnline or lastSeen values.
        val textOnlineDeleted = formatPresenceText(isOnline = true, lastSeen = 1729780000000L, isDeleted = true)
        assertEquals("This user no longer exists", textOnlineDeleted)

        val textOfflineDeleted = formatPresenceText(isOnline = false, lastSeen = 0L, isDeleted = true)
        assertEquals("This user no longer exists", textOfflineDeleted)
    }

    @Test
    fun testPresence_activeState() {
        val textActive = formatPresenceText(isOnline = true, lastSeen = 0L, isDeleted = false)
        assertEquals("Active now", textActive)
    }

    @Test
    fun testPresence_stalePresenceUpdateRejected() = runTest(testDispatcher) {
        val userId = "user_test_ordering"
        database.userDao().insertUser(
            UserEntity(id = userId, username = "ordering", displayName = "Ordering", isLocalUser = false, isOnline = true, lastSeen = 0L)
        )

        // Event 1: user goes offline at T = 2000
        userRepository.updatePresence(userId, false, 2000L, timestamp = 2000L)
        advanceUntilIdle()

        var user = database.userDao().getUserByIdOnce(userId)
        assertNotNull(user)
        assertFalse(user!!.isOnline)
        assertEquals(2000L, user.lastSeen)

        // Event 2: Stale out-of-order event arrived with T = 1000 claiming user is online
        userRepository.updatePresence(userId, true, 0L, timestamp = 1000L)
        advanceUntilIdle()

        // Should be rejected: still offline with T = 2000
        user = database.userDao().getUserByIdOnce(userId)
        assertNotNull(user)
        assertFalse(user!!.isOnline)
        assertEquals(2000L, user.lastSeen)
    }

    @Test
    fun testPresence_saveUserDoesNotClobberOfflinePresence() = runTest(testDispatcher) {
        val userId = "user_test_preserve"
        database.userDao().insertUser(
            UserEntity(id = userId, username = "preserve", displayName = "Preserve", isLocalUser = false, isOnline = false, lastSeen = 1729000000L)
        )

        // Simulating profile fetch/save with default/unspecified presence
        userRepository.saveUser(
            User(id = userId, username = "preserve_updated", displayName = "Preserve Updated", isLocalUser = false, isOnline = false, lastSeen = 0L)
        )
        advanceUntilIdle()

        val user = database.userDao().getUserByIdOnce(userId)
        assertNotNull(user)
        assertEquals("preserve_updated", user!!.username)
        assertEquals("Preserve Updated", user.displayName)
        // Presence was preserved!
        assertFalse(user.isOnline)
        assertEquals(1729000000L, user.lastSeen)
    }

    @Test
    fun testPresence_offlineStateWithoutLastSeen() {
        val textOffline = formatPresenceText(isOnline = false, lastSeen = 0L, isDeleted = false)
        assertEquals("Offline", textOffline)
    }

    @Test
    fun testPresence_offlineStateWithLastSeen() {
        val lastSeenMs = 1729780000000L
        val textLastSeen = formatPresenceText(isOnline = false, lastSeen = lastSeenMs, isDeleted = false)
        assertNotNull(textLastSeen)
        assertTrue(textLastSeen!!.startsWith("Last seen "))
    }

    // -------------------------------------------------------------------------
    // 2. Protocol Parsing for Presence & Deletion (Req 15, 21, 28)
    // -------------------------------------------------------------------------

    @Test
    fun testProtocol_presenceUpdateParsing() {
        val json = """{"type":"PRESENCE_UPDATE","userId":"user_alex","isOnline":true,"lastSeen":0}"""
        val parsed = ServerMessage.fromJson(json)

        assertTrue(parsed is ServerMessage.PresenceUpdate)
        val update = parsed as ServerMessage.PresenceUpdate
        assertEquals("user_alex", update.userId)
        assertTrue(update.isOnline)
        assertEquals(0L, update.lastSeen)
    }

    @Test
    fun testProtocol_presenceUpdateOfflineParsing() {
        val json = """{"type":"PRESENCE_UPDATE","userId":"user_rohan","isOnline":false,"lastSeen":1729781234567}"""
        val parsed = ServerMessage.fromJson(json)

        assertTrue(parsed is ServerMessage.PresenceUpdate)
        val update = parsed as ServerMessage.PresenceUpdate
        assertEquals("user_rohan", update.userId)
        assertFalse(update.isOnline)
        assertEquals(1729781234567L, update.lastSeen)
    }

    @Test
    fun testProtocol_userDeletedParsing() {
        val json = """{"type":"USER_DELETED","userId":"user_priya"}"""
        val parsed = ServerMessage.fromJson(json)

        assertTrue(parsed is ServerMessage.UserDeleted)
        val deleted = parsed as ServerMessage.UserDeleted
        assertEquals("user_priya", deleted.userId)
    }

    // -------------------------------------------------------------------------
    // 3. User Entity & Room Persistence (Req 6, 7, 10, 15)
    // -------------------------------------------------------------------------

    @Test
    fun testRoom_userPresenceAndDeletedColumns() = runTest(testDispatcher) {
        val entity = UserEntity(
            id = "user_test_alex",
            username = "alex",
            displayName = "Alex",
            isLocalUser = false,
            isOnline = true,
            lastSeen = 1729780000000L,
            isDeleted = false
        )
        database.userDao().insertUser(entity)

        val retrieved = database.userDao().getUserByIdOnce("user_test_alex")
        assertNotNull(retrieved)
        assertEquals("user_test_alex", retrieved!!.id)
        assertTrue(retrieved.isOnline)
        assertEquals(1729780000000L, retrieved.lastSeen)
        assertFalse(retrieved.isDeleted)

        val domain = retrieved.toDomain()
        assertEquals("Alex", domain.displayName)
        assertTrue(domain.isOnline)
        assertEquals(1729780000000L, domain.lastSeen)
        assertFalse(domain.isDeleted)
    }

    @Test
    fun testRoom_updatePresence() = runTest(testDispatcher) {
        val entity = UserEntity("user_rohan", "rohan", "Rohan", false, false, 0L, false)
        database.userDao().insertUser(entity)

        database.userDao().updatePresence("user_rohan", isOnline = true, lastSeen = 0L)

        val updated = database.userDao().getUserByIdOnce("user_rohan")
        assertNotNull(updated)
        assertTrue(updated!!.isOnline)
        assertEquals(0L, updated.lastSeen)
    }

    @Test
    fun testRoom_markUserDeleted() = runTest(testDispatcher) {
        val entity = UserEntity("user_target", "target", "Target User", false, true, 1000L, false)
        database.userDao().insertUser(entity)

        database.userDao().markUserDeleted("user_target")

        val deleted = database.userDao().getUserByIdOnce("user_target")
        assertNotNull(deleted)
        assertTrue(deleted!!.isDeleted)
        assertEquals("Deleted User", deleted.displayName)
        assertEquals("deleted", deleted.username)
        assertFalse(deleted.isOnline)
        assertEquals(0L, deleted.lastSeen)
    }

    // -------------------------------------------------------------------------
    // 4. UserRepository Presence & Deleted User Handling (Req 14, 15, 21)
    // -------------------------------------------------------------------------

    @Test
    fun testUserRepository_presenceUpdatesViaWebSocket() = runTest(testDispatcher) {
        advanceUntilIdle()

        val entity = UserEntity("user_priya", "priya", "Priya", false, false, 0L, false)
        database.userDao().insertUser(entity)

        webSocketClient.emitPresenceUpdateForTesting(
            ServerMessage.PresenceUpdate("user_priya", isOnline = true, lastSeen = 0L)
        )
        advanceUntilIdle()

        val updated = database.userDao().getUserByIdOnce("user_priya")
        assertNotNull(updated)
        assertTrue(updated!!.isOnline)
    }

    @Test
    fun testUserRepository_deletedUserIgnoresSubsequentPresenceUpdates() = runTest(testDispatcher) {
        val entity = UserEntity("user_del", "del", "Del User", false, false, 0L, false)
        database.userDao().insertUser(entity)

        // Mark deleted
        userRepository.markUserDeleted("user_del")
        advanceUntilIdle()

        var user = database.userDao().getUserByIdOnce("user_del")
        assertNotNull(user)
        assertTrue(user!!.isDeleted)
        assertEquals("Deleted User", user.displayName)

        // Attempt presence update for deleted user -> must be ignored!
        userRepository.updatePresence("user_del", isOnline = true, lastSeen = 0L)
        advanceUntilIdle()

        user = database.userDao().getUserByIdOnce("user_del")
        assertNotNull(user)
        assertTrue(user!!.isDeleted)
        assertFalse(user.isOnline)
        assertEquals(0L, user.lastSeen)
    }

    // -------------------------------------------------------------------------
    // 5. SettingsViewModel: Deletion Confirmation & Offline Failure (Req 4, 5, 25)
    // -------------------------------------------------------------------------

    @Test
    fun testSettingsViewModel_deleteAccountFailureOfflineDoesNotWipeData() = runTest(testDispatcher) {
        val fakeApiClient = object : AsterApiClient(OkHttpClient(), authStorage) {
            override suspend fun deleteAccount(): ApiResult<Boolean> {
                return ApiResult.Error("Network unreachable")
            }
        }

        database.userDao().insertUser(UserEntity("user_saad", "saad", "Saad", true, true, 0L, false))

        val viewModel = SettingsViewModel(
            apiClient = fakeApiClient,
            authStorage = authStorage,
            webSocketClient = webSocketClient,
            database = database,
            context = context,
            ioDispatcher = testDispatcher
        )

        var completed = false
        viewModel.deleteAccount { completed = true }
        advanceUntilIdle()

        // Deletion must NOT claim success, must NOT wipe token, must NOT wipe DB
        assertFalse(completed)
        assertFalse(viewModel.uiState.value.isDeletingAccount)
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(authStorage.isProvisioned())
        assertNotNull(database.userDao().getUserByIdOnce("user_saad"))
    }

    @Test
    fun testSettingsViewModel_deleteAccountSuccessWipesLocalData() = runTest(testDispatcher) {
        val fakeApiClient = object : AsterApiClient(OkHttpClient(), authStorage) {
            override suspend fun deleteAccount(): ApiResult<Boolean> {
                authStorage.clearAuthToken()
                return ApiResult.Success(true)
            }
        }

        database.userDao().insertUser(UserEntity("user_saad", "saad", "Saad", true, true, 0L, false))

        val viewModel = SettingsViewModel(
            apiClient = fakeApiClient,
            authStorage = authStorage,
            webSocketClient = webSocketClient,
            database = database,
            context = context,
            ioDispatcher = testDispatcher
        )

        var completed = false
        viewModel.deleteAccount { completed = true }
        advanceUntilIdle()

        assertTrue(completed)
        assertFalse(viewModel.uiState.value.isDeletingAccount)
        assertNull(viewModel.uiState.value.errorMessage)
        assertFalse(authStorage.isProvisioned())
    }

    @Test
    fun testSettingsViewModel_logoutKeepsDatabaseIntact() = runTest(testDispatcher) {
        val fakeApiClient = object : AsterApiClient(OkHttpClient(), authStorage) {
            override suspend fun logout(): ApiResult<Boolean> {
                return ApiResult.Success(true)
            }
        }

        database.userDao().insertUser(UserEntity("user_saad", "saad", "Saad", true, true, 0L, false))

        val viewModel = SettingsViewModel(
            apiClient = fakeApiClient,
            authStorage = authStorage,
            webSocketClient = webSocketClient,
            database = database,
            context = context,
            ioDispatcher = testDispatcher
        )

        var completed = false
        viewModel.logout { completed = true }
        advanceUntilIdle()

        assertTrue(completed)
        assertFalse(authStorage.isProvisioned())
        // Database remains intact so user can log back in later without loss of conversation history
        assertNotNull(database.userDao().getUserByIdOnce("user_saad"))
    }
}
