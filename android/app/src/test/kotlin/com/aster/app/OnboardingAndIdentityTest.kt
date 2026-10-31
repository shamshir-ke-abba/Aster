package com.aster.app

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.AuthResult
import com.aster.app.data.network.RecoveryResponse
import com.aster.app.data.network.RegistrationResponse
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.ui.AuthSubFlow
import com.aster.app.ui.ChatViewModel
import com.aster.app.ui.OnboardingTab
import com.aster.app.ui.OnboardingUiState
import com.aster.app.ui.OnboardingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
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
class OnboardingAndIdentityTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var authStorage: AsterAuthStorage
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var seedInitializer: DatabaseSeedInitializer
    private lateinit var testWebSocketClient: TestAsterWebSocketClient
    private lateinit var testApiClient: TestAsterApiClient

    class TestAsterWebSocketClient(
        client: OkHttpClient,
        authStorage: AsterAuthStorage
    ) : AsterWebSocketClient(client, authStorage) {
        var authResultToReturn: AuthResult = AuthResult.Success("user_test")

        override suspend fun authenticateWithToken(token: String, timeoutMs: Long): AuthResult {
            if (authResultToReturn is AuthResult.Success) {
                authStorage.saveAuthToken(token)
                authStorage.saveAuthenticatedUserId((authResultToReturn as AuthResult.Success).userId)
            } else {
                authStorage.clearAuthToken()
            }
            return authResultToReturn
        }
    }

    class TestAsterApiClient(
        private val storage: AsterAuthStorage
    ) : AsterApiClient(OkHttpClient(), storage) {
        var registerResult: ApiResult<RegistrationResponse>? = null
        var loginResult: ApiResult<RegistrationResponse>? = null
        var recoverResult: ApiResult<RecoveryResponse>? = null
        var migrateResult: ApiResult<RegistrationResponse>? = null

        override suspend fun register(
            username: String,
            displayName: String,
            password: String,
            deviceId: String
        ): ApiResult<RegistrationResponse> {
            val res = registerResult ?: ApiResult.Success(
                RegistrationResponse(
                    userId = "user_$username",
                    asterId = "$username@aster.local",
                    username = username,
                    displayName = displayName.ifBlank { username },
                    token = "tok_$username",
                    recoveryCode = "ABCD-EFGH-IJKL-MNOP"
                )
            )
            if (res is ApiResult.Success) {
                storage.saveAuthToken(res.data.token)
                storage.saveAuthenticatedUserId(res.data.userId)
                storage.saveAsterId(res.data.asterId)
                storage.saveUsername(res.data.username)
                storage.saveDisplayName(res.data.displayName)
            }
            return res
        }

        override suspend fun login(
            username: String,
            password: String,
            deviceId: String
        ): ApiResult<RegistrationResponse> {
            println("TestAsterApiClient: login($username)")
            val res = loginResult ?: ApiResult.Success(
                RegistrationResponse(
                    userId = "user_$username",
                    asterId = "$username@aster.local",
                    username = username,
                    displayName = username.replaceFirstChar { it.uppercase() },
                    token = "tok_$username"
                )
            )
            if (res is ApiResult.Success) {
                storage.saveAuthToken(res.data.token)
                storage.saveAuthenticatedUserId(res.data.userId)
                storage.saveAsterId(res.data.asterId)
                storage.saveUsername(res.data.username)
                storage.saveDisplayName(res.data.displayName)
            }
            return res
        }

        override suspend fun recoverPassword(
            username: String,
            recoveryCode: String,
            newPassword: String,
            deviceId: String
        ): ApiResult<RecoveryResponse> {
            val res = recoverResult ?: ApiResult.Success(
                RecoveryResponse(
                    userId = "user_$username",
                    asterId = "$username@aster.local",
                    username = username,
                    displayName = username.replaceFirstChar { it.uppercase() },
                    token = "tok_recovered_$username",
                    newRecoveryCode = "WXYZ-1234-5678-90AB"
                )
            )
            if (res is ApiResult.Success) {
                storage.saveAuthToken(res.data.token)
                storage.saveAuthenticatedUserId(res.data.userId)
                storage.saveAsterId(res.data.asterId)
                storage.saveUsername(res.data.username)
                storage.saveDisplayName(res.data.displayName)
            }
            return res
        }

        override suspend fun migrateAccount(
            username: String,
            newPassword: String,
            deviceId: String
        ): ApiResult<RegistrationResponse> {
            val res = migrateResult ?: ApiResult.Success(
                RegistrationResponse(
                    userId = "user_$username",
                    asterId = "$username@aster.local",
                    username = username,
                    displayName = username.replaceFirstChar { it.uppercase() },
                    token = "tok_migrated_$username",
                    recoveryCode = "MIGR-ATED-CODE-1234"
                )
            )
            if (res is ApiResult.Success) {
                storage.saveAuthToken(res.data.token)
                storage.saveAuthenticatedUserId(res.data.userId)
                storage.saveAsterId(res.data.asterId)
                storage.saveUsername(res.data.username)
                storage.saveDisplayName(res.data.displayName)
            }
            return res
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        authStorage = AsterAuthStorage(context)
        authStorage.clearAuthToken()

        testWebSocketClient = TestAsterWebSocketClient(OkHttpClient(), authStorage)
        testApiClient = TestAsterApiClient(authStorage)

        userRepository = UserRepositoryImpl(database.userDao())
        conversationRepository = ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao(),
            webSocketClient = testWebSocketClient
        )
        messageRepository = MessageRepositoryImpl(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            userDao = database.userDao(),
            webSocketClient = testWebSocketClient
        )
        seedInitializer = DatabaseSeedInitializer(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            authStorage = authStorage
        )
        com.aster.app.data.network.NetworkConfig.currentEnvironment = com.aster.app.data.network.NetworkEnvironment.DEVELOPMENT_UNSECURED_LOCAL
    }

    @After
    fun tearDown() {
        com.aster.app.data.network.NetworkConfig.resetToDefault(com.aster.app.data.network.NetworkEnvironment.PRODUCTION)
        Dispatchers.resetMain()
        database.close()
        authStorage.clearAuthToken()
    }

    // 1. Fresh install with no token starts on Create Account tab
    @Test
    fun test1_freshInstall_defaultsToCreateAccount_isUnprovisionedAndHasNoLocalUser() = runTest(testDispatcher) {
        assertFalse(authStorage.isProvisioned())
        assertNull(authStorage.getAuthToken())
        assertNull(authStorage.getAuthenticatedUserId())

        seedInitializer.initializeIfNeeded()

        // Room users are seeded, but NONE are marked as local user
        val localUser = database.userDao().getLocalUserOnce()
        assertNull("Fresh install must not have a local user assigned", localUser)

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        assertEquals(OnboardingUiState.Idle, viewModel.uiState.value)
        assertEquals(OnboardingTab.CREATE_ACCOUNT, viewModel.currentTab.value)
        assertEquals(AuthSubFlow.NORMAL, viewModel.subFlow.value)
    }

    // 2. Successful Create Account flow presents recovery code and provisions account upon acknowledgment
    @Test
    fun test2_createAccount_showsRecoveryCode_andEstablishesSessionUponAcknowledgment() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        viewModel.onUsernameInputChanged("charlie")
        viewModel.onDisplayNameInputChanged("Charlie")
        viewModel.onPasswordInputChanged("securePassword123")
        viewModel.submitCreateAccount()

        // Verify recovery code is shown first
        val recoveryState = viewModel.uiState.filter { it is OnboardingUiState.ShowRecoveryCode }.first()
        assertTrue("State must be ShowRecoveryCode", recoveryState is OnboardingUiState.ShowRecoveryCode)
        val codeState = recoveryState as OnboardingUiState.ShowRecoveryCode
        assertEquals("ABCD-EFGH-IJKL-MNOP", codeState.recoveryCode)
        assertEquals("user_charlie", codeState.userId)
        assertFalse(codeState.isReset)

        // User acknowledges saving the recovery code
        viewModel.acknowledgeRecoveryCode()

        val successState = viewModel.uiState.filter { it is OnboardingUiState.Success }.first()
        assertEquals("user_charlie", (successState as OnboardingUiState.Success).userId)

        // Verify Room local user was created and set
        val localUser = userRepository.getLocalUser().filter { it != null }.first()
        assertNotNull(localUser)
        assertEquals("user_charlie", localUser?.id)
        assertTrue(localUser?.isLocalUser == true)
        assertTrue(authStorage.isProvisioned())
        assertEquals("tok_charlie", authStorage.getAuthToken())
    }

    // 3. Create Account client-side validation errors
    @Test
    fun test3_createAccount_validationErrors() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)

        // Empty username (< 3 chars)
        viewModel.onUsernameInputChanged("")
        viewModel.onPasswordInputChanged("password123")
        viewModel.submitCreateAccount()
        assertTrue(viewModel.uiState.value is OnboardingUiState.Error)
        assertEquals("Username must be 3-24 alphanumeric or underscore characters", (viewModel.uiState.value as OnboardingUiState.Error).message)

        // Password too short (< 8 chars)
        viewModel.onUsernameInputChanged("valid_user")
        viewModel.onPasswordInputChanged("short")
        viewModel.submitCreateAccount()
        assertTrue(viewModel.uiState.value is OnboardingUiState.Error)
        assertEquals("Password must be at least 8 characters", (viewModel.uiState.value as OnboardingUiState.Error).message)

        // Invalid characters in username
        viewModel.onUsernameInputChanged("invalid user!")
        viewModel.onPasswordInputChanged("validPassword123")
        viewModel.submitCreateAccount()
        assertTrue(viewModel.uiState.value is OnboardingUiState.Error)
        assertEquals(
            "Username must be 3-24 alphanumeric or underscore characters",
            (viewModel.uiState.value as OnboardingUiState.Error).message
        )
    }

    // 4. Successful Sign In flow
    @Test
    fun test4_signIn_success_authenticatesAndProvisionsUser() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        viewModel.selectTab(OnboardingTab.SIGN_IN)
        assertEquals(OnboardingTab.SIGN_IN, viewModel.currentTab.value)

        viewModel.onUsernameInputChanged("saad")
        viewModel.onPasswordInputChanged("validPassword123")
        viewModel.submitSignIn()

        val state = viewModel.uiState.filter { it is OnboardingUiState.Success }.first()
        assertEquals("user_saad", (state as OnboardingUiState.Success).userId)

        val localUser = userRepository.getLocalUser().filter { it != null }.first()
        assertEquals("user_saad", localUser?.id)
        assertTrue(authStorage.isProvisioned())
    }

    // 5. Sign In invalid credentials returns generic failure
    @Test
    fun test5_signIn_invalidCredentials_showsGenericErrorWithoutProvisioning() = runTest(testDispatcher) {
        testApiClient.loginResult = ApiResult.Error("Invalid username or password", 401)

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        viewModel.selectTab(OnboardingTab.SIGN_IN)
        viewModel.onUsernameInputChanged("saad")
        viewModel.onPasswordInputChanged("wrongPassword")
        viewModel.submitSignIn()

        val state = viewModel.uiState.filter { it is OnboardingUiState.Error }.first()
        assertEquals("Invalid username or password", (state as OnboardingUiState.Error).message)
        assertFalse(authStorage.isProvisioned())
    }

    // 6. Sign In legacy account requires migration flow
    @Test
    fun test6_signIn_migrationRequired_transitionsToMigrationFlowAndSucceeds() = runTest(testDispatcher) {
        testApiClient.loginResult = ApiResult.Error("Account requires password setup to migrate.", 403, requiresMigration = true)

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        viewModel.selectTab(OnboardingTab.SIGN_IN)
        viewModel.onUsernameInputChanged("legacy_user")
        viewModel.onPasswordInputChanged("anyPassword")
        viewModel.submitSignIn()

        val errorState = viewModel.uiState.filter { it is OnboardingUiState.Error }.first() as OnboardingUiState.Error
        assertTrue(errorState.requiresMigration)

        // Initiate migration
        viewModel.setSubFlow(AuthSubFlow.MIGRATE_ACCOUNT)
        assertEquals(AuthSubFlow.MIGRATE_ACCOUNT, viewModel.subFlow.value)
        assertEquals("legacy_user", viewModel.usernameInput.value)

        // Submit migration with new password
        viewModel.onNewPasswordInputChanged("brandNewPassword123")
        viewModel.submitMigration()

        // Should receive recovery code for newly secured migrated account
        val recoveryState = viewModel.uiState.filter { it is OnboardingUiState.ShowRecoveryCode }.first() as OnboardingUiState.ShowRecoveryCode
        assertEquals("MIGR-ATED-CODE-1234", recoveryState.recoveryCode)

        viewModel.acknowledgeRecoveryCode()
        val successState = viewModel.uiState.filter { it is OnboardingUiState.Success }.first()
        assertTrue(successState is OnboardingUiState.Success)
        assertTrue(authStorage.isProvisioned())
    }

    // 7. Password recovery with recovery code
    @Test
    fun test7_passwordRecovery_success_showsNewRecoveryCode() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository, testApiClient)
        viewModel.selectTab(OnboardingTab.SIGN_IN)
        viewModel.setSubFlow(AuthSubFlow.FORGOT_PASSWORD)
        assertEquals(AuthSubFlow.FORGOT_PASSWORD, viewModel.subFlow.value)

        viewModel.onUsernameInputChanged("saad")
        viewModel.onRecoveryCodeInputChanged("abcd-efgh-ijkl-mnop")
        viewModel.onNewPasswordInputChanged("superNewPassword456")
        viewModel.submitPasswordRecovery()

        val recoveryState = viewModel.uiState.filter { it is OnboardingUiState.ShowRecoveryCode }.first() as OnboardingUiState.ShowRecoveryCode
        assertEquals("WXYZ-1234-5678-90AB", recoveryState.recoveryCode)
        assertTrue("Must be marked as reset", recoveryState.isReset)

        viewModel.acknowledgeRecoveryCode()
        val successState = viewModel.uiState.filter { it is OnboardingUiState.Success }.first()
        assertEquals("user_saad", (successState as OnboardingUiState.Success).userId)
        assertTrue(authStorage.isProvisioned())
    }

    // 8. Valid stored session relaunch
    @Test
    fun test8_validStoredSessionRelaunch_preservesStoredIdentity() = runTest(testDispatcher) {
        authStorage.saveAuthToken("ast_tok_persisted")
        authStorage.saveAuthenticatedUserId("user_saad")
        authStorage.saveServerUrl("wss://192.168.0.114:8443/ws")
        assertTrue(authStorage.isProvisioned())
        assertEquals("wss://192.168.0.114:8443/ws", authStorage.getServerUrl())

        seedInitializer.initializeIfNeeded()

        val localUser = database.userDao().getLocalUserOnce()
        assertNotNull(localUser)
        assertEquals("user_saad", localUser?.id)
        assertTrue(localUser?.isLocalUser == true)
    }

    // 9. Correct greeting and Room identity for two separate accounts
    @Test
    fun test9_twoSeparateAccounts_identityAndConversationIsolation() = runTest(testDispatcher) {
        seedInitializer.initializeIfNeeded()

        // Account A: user_saad
        userRepository.setAuthenticatedUser("user_saad")
        var localUser = userRepository.getLocalUser().first()
        assertEquals("user_saad", localUser?.id)
        assertEquals("Saad", localUser?.displayName)

        var conversations = conversationRepository.getConversations().first()
        assertTrue("user_saad is a member of conv_alex", conversations.any { it.id == "conv_alex" })

        // Account B: user_charlie
        userRepository.setAuthenticatedUser("user_charlie")
        localUser = userRepository.getLocalUser().first()
        assertEquals("user_charlie", localUser?.id)
        assertEquals("Charlie", localUser?.displayName)

        conversations = conversationRepository.getConversations().first()
        assertFalse("user_charlie is NOT a member of conv_alex and must not see it", conversations.any { it.id == "conv_alex" })
    }

    // 10. No message sending when unauthenticated
    @Test
    fun test10_unauthenticatedState_blocksMessageSending() = runTest(testDispatcher) {
        seedInitializer.initializeIfNeeded()
        database.userDao().clearLocalUser()

        val savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv_alex"))
        val chatViewModel = ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            userRepository = userRepository
        )
        advanceUntilIdle()

        chatViewModel.onInputTextChanged("Unauthorized message")
        chatViewModel.sendMessage()
        advanceUntilIdle()

        val messages = database.messageDao().getMessagesForConversation("conv_alex").first()
        assertFalse("Unauthorized message must not be inserted", messages.any { it.body == "Unauthorized message" })
    }
}
