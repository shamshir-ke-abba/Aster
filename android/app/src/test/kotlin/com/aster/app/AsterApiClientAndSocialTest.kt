package com.aster.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.domain.repository.UserRepository
import com.aster.app.ui.OnboardingUiState
import com.aster.app.ui.OnboardingViewModel
import com.aster.app.ui.SettingsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
@OptIn(ExperimentalCoroutinesApi::class)
class AsterApiClientAndSocialTest {

    private lateinit var context: Context
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var apiClient: TestAsterApiClient

    class TestAsterApiClient(
        okHttpClient: OkHttpClient,
        authStorage: AsterAuthStorage,
        private val mockBaseUrl: String
    ) : AsterApiClient(okHttpClient, authStorage) {
        override fun baseUrl(): String = mockBaseUrl
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()

        mockWebServer = MockWebServer()
        mockWebServer.start()

        okHttpClient = OkHttpClient.Builder().build()
        apiClient = TestAsterApiClient(
            okHttpClient = okHttpClient,
            authStorage = authStorage,
            mockBaseUrl = mockWebServer.url("").toString().removeSuffix("/")
        )
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        authStorage.clearAuthToken()
    }

    @Test
    fun testRegisterApiSuccessAndPersistence() = runTest {
        val jsonResponse = """
            {
                "status": "ok",
                "userId": "user_test_maya",
                "asterId": "AST-M4Y424",
                "username": "maya",
                "displayName": "Maya Lin",
                "token": "ast_tok_valid_test_token"
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(jsonResponse))

        val result = apiClient.register("maya", "Maya Lin")
        assertTrue(result is ApiResult.Success)
        val data = (result as ApiResult.Success).data
        assertEquals("user_test_maya", data.userId)
        assertEquals("AST-M4Y424", data.asterId)
        assertEquals("maya", data.username)
        assertEquals("Maya Lin", data.displayName)
        assertEquals("ast_tok_valid_test_token", data.token)

        // Verify saved to AsterAuthStorage
        assertEquals("ast_tok_valid_test_token", authStorage.getAuthToken())
        assertEquals("AST-M4Y424", authStorage.getAsterId())
        assertEquals("maya", authStorage.getUsername())
        assertEquals("Maya Lin", authStorage.getDisplayName())
    }

    @Test
    fun testRegisterApiDuplicateUsernameConflict() = runTest {
        val errorJson = """{"error":"Username 'maya' is already taken"}"""
        mockWebServer.enqueue(MockResponse().setResponseCode(409).setBody(errorJson))

        val result = apiClient.register("maya", "Maya Lin")
        assertTrue(result is ApiResult.Error)
        val err = result as ApiResult.Error
        assertEquals(409, err.statusCode)
        assertTrue(err.message.contains("already taken"))
    }

    @Test
    fun testUserProfileAndSearch() = runTest {
        authStorage.saveAuthToken("auth_sample_token")

        // 1. Profile retrieval
        val profileJson = """
            {
                "status": "ok",
                "userId": "user_rachel",
                "asterId": "AST-R4CH3L",
                "username": "rachel",
                "displayName": "Rachel Carson",
                "createdAt": 1000,
                "updatedAt": 1000
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(profileJson))

        val profileRes = apiClient.getProfile()
        assertTrue(profileRes is ApiResult.Success)
        val profile = (profileRes as ApiResult.Success).data
        assertEquals("AST-R4CH3L", profile.asterId)
        assertEquals("rachel", profile.username)
        assertEquals("Rachel Carson", profile.displayName)

        // 2. User search
        val searchJson = """
            {
                "status": "ok",
                "users": [
                    {
                        "userId": "user_david",
                        "asterId": "AST-D4V1D1",
                        "username": "david",
                        "displayName": "David Attenborough"
                    }
                ]
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody(searchJson))

        val searchRes = apiClient.searchUsers("david")
        assertTrue(searchRes is ApiResult.Success)
        val users = (searchRes as ApiResult.Success).data
        assertEquals(1, users.size)
        assertEquals("AST-D4V1D1", users[0].asterId)
        assertEquals("David Attenborough", users[0].displayName)
    }

    @Test
    fun testFriendRequestsAndBlockingFlow() = runTest {
        authStorage.saveAuthToken("auth_sample_token")

        // 1. Send friend request
        val reqJson = """
            {
                "id": "freq_100",
                "senderUserId": "user_me",
                "recipientUserId": "user_them",
                "status": "PENDING",
                "createdAt": 1000
            }
        """.trimIndent()
        mockWebServer.enqueue(MockResponse().setResponseCode(201).setBody(reqJson))

        val sendRes = apiClient.sendFriendRequest(targetUserId = "user_them")
        assertTrue(sendRes is ApiResult.Success)
        assertEquals("freq_100", (sendRes as ApiResult.Success).data.id)

        // 2. Respond to friend request (accept)
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val respondRes = apiClient.respondFriendRequest("freq_100", true)
        assertTrue(respondRes is ApiResult.Success)
        assertTrue((respondRes as ApiResult.Success).data)

        // 3. Block user
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val blockRes = apiClient.blockUser(targetUserId = "user_them")
        assertTrue(blockRes is ApiResult.Success)

        // 4. Unblock user
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))
        val unblockRes = apiClient.unblockUser(targetUserId = "user_them")
        assertTrue(unblockRes is ApiResult.Success)
    }

    @Test
    fun testLogoutClearsAuthStorage() = runTest {
        authStorage.saveAuthToken("token_to_revoke")
        authStorage.saveAsterId("AST-REV001")
        authStorage.saveUsername("tempuser")
        authStorage.saveDisplayName("Temp User")

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"ok"}"""))

        val logoutRes = apiClient.logout()
        assertTrue(logoutRes is ApiResult.Success)

        // Verify storage is sanitized
        assertNull(authStorage.getAuthToken())
        assertNull(authStorage.getAsterId())
        assertNull(authStorage.getUsername())
        assertNull(authStorage.getDisplayName())
    }

    @Test
    fun testOnboardingViewModelValidation() = runTest {
        val fakeRepo = object : UserRepository {
            override fun getLocalUser() = kotlinx.coroutines.flow.flowOf(null)
            override suspend fun getLocalUserOnce(): com.aster.app.domain.model.User? = null
            override fun getUser(userId: String) = kotlinx.coroutines.flow.flowOf(null)
            override fun getUsers() = kotlinx.coroutines.flow.flowOf(emptyList<com.aster.app.domain.model.User>())
            override suspend fun saveUser(user: com.aster.app.domain.model.User) {}
            override suspend fun setAuthenticatedUser(userId: String) {}
            override suspend fun clearAuthenticatedUser() {}
        }
        val fakeWsClient = object : AsterWebSocketClient(okHttpClient, authStorage) {}

        val vm = OnboardingViewModel(authStorage, fakeWsClient, fakeRepo, apiClient)

        // 1. Invalid username (too short)
        vm.onUsernameInputChanged("ab")
        vm.onDisplayNameInputChanged("Valid Name")
        vm.submitRegistration()
        assertTrue(vm.uiState.value is OnboardingUiState.Error)
        assertTrue((vm.uiState.value as OnboardingUiState.Error).message.contains("3-24"))

        // 2. Invalid display name (blank)
        vm.onUsernameInputChanged("valid_user")
        vm.onDisplayNameInputChanged("   ")
        vm.submitRegistration()
        assertTrue(vm.uiState.value is OnboardingUiState.Error)
        assertTrue((vm.uiState.value as OnboardingUiState.Error).message.contains("Display name"))
    }

    @Test
    fun testSettingsViewModelViewModePersistence() = runTest {
        val fakeWsClient = object : AsterWebSocketClient(okHttpClient, authStorage) {}
        val vm = SettingsViewModel(apiClient, authStorage, fakeWsClient)

        // Default view mode is CONSTELLATION
        assertEquals("CONSTELLATION", authStorage.getViewMode())

        // Switch to LIST
        vm.setViewMode("LIST")
        assertEquals("LIST", authStorage.getViewMode())
        assertEquals("LIST", vm.uiState.value.viewMode)

        // Switch back to CONSTELLATION
        vm.setViewMode("CONSTELLATION")
        assertEquals("CONSTELLATION", authStorage.getViewMode())
        assertEquals("CONSTELLATION", vm.uiState.value.viewMode)
    }
}
