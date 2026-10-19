package com.aster.app

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.auth.AsterAuthStorage
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.AuthResult
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.ui.ChatViewModel
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
        authStorage.clearAuthToken()
    }

    // 1. Fresh install with no token
    @Test
    fun test1_freshInstall_withNoToken_isUnprovisionedAndHasNoLocalUser() = runTest(testDispatcher) {
        assertFalse(authStorage.isProvisioned())
        assertNull(authStorage.getAuthToken())
        assertNull(authStorage.getAuthenticatedUserId())

        seedInitializer.initializeIfNeeded()

        // Room users are seeded, but NONE are marked as local user
        val localUser = database.userDao().getLocalUserOnce()
        assertNull("Fresh install must not have a local user assigned", localUser)

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository)
        assertEquals(OnboardingUiState.Idle, viewModel.uiState.value)
    }

    // 2. Successful invite-token authentication
    @Test
    fun test2_onboarding_successfulAuthentication_persistsAuthAndUpdatesRoomLocalUser() = runTest(testDispatcher) {
        testWebSocketClient.authResultToReturn = AuthResult.Success("user_charlie")

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository)
        viewModel.onTokenInputChanged("ast_tok_charlie_test")
        viewModel.submitToken()

        val state = viewModel.uiState.filter { it is OnboardingUiState.Success }.first()
        assertEquals(OnboardingUiState.Success("user_charlie"), state)
        assertEquals("user_charlie", (state as OnboardingUiState.Success).userId)

        // Verify Room local user was created and set
        val localUser = userRepository.getLocalUser().filter { it != null }.first()
        assertNotNull(localUser)
        assertEquals("user_charlie", localUser?.id)
        assertTrue(localUser?.isLocalUser == true)
        assertTrue(authStorage.isProvisioned())
    }

    // 3. Invalid and revoked tokens
    @Test
    fun test3_onboarding_invalidAndRevokedTokens() = runTest(testDispatcher) {
        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository)

        // Invalid token
        testWebSocketClient.authResultToReturn = AuthResult.InvalidToken("Invalid token")
        viewModel.submitToken("ast_tok_invalid")
        advanceUntilIdle()
        val error1 = viewModel.uiState.value as OnboardingUiState.Error
        assertFalse(error1.isRevoked)
        assertFalse(error1.isServerUnavailable)
        assertFalse(authStorage.isProvisioned())

        // Revoked token
        testWebSocketClient.authResultToReturn = AuthResult.InvalidToken("Token has been revoked")
        viewModel.submitToken("ast_tok_revoked")
        advanceUntilIdle()
        val error2 = viewModel.uiState.value as OnboardingUiState.Error
        assertTrue(error2.isRevoked)
        assertFalse(authStorage.isProvisioned())
    }

    // 4. Server unavailable during onboarding
    @Test
    fun test4_onboarding_serverUnavailable_emitsNetworkErrorState() = runTest(testDispatcher) {
        testWebSocketClient.authResultToReturn = AuthResult.ServerUnavailable("Connection timed out")

        val viewModel = OnboardingViewModel(authStorage, testWebSocketClient, userRepository)
        viewModel.submitToken("ast_tok_server_down")
        advanceUntilIdle()

        val error = viewModel.uiState.value as OnboardingUiState.Error
        assertTrue(error.isServerUnavailable)
        assertFalse(authStorage.isProvisioned())
    }

    // 5. Valid stored session relaunch
    @Test
    fun test5_validStoredSessionRelaunch_preservesStoredIdentity() = runTest(testDispatcher) {
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

    // 6. Invite deep-link parsing and malformed links
    @Test
    fun test6_inviteDeepLink_parsingValidAndMalformedLinks() {
        val validUri = Uri.parse("aster://join?token=ast_tok_welcome123&server=wss://192.168.0.114:8443/ws")
        assertEquals("aster", validUri.scheme)
        assertEquals("join", validUri.host)
        assertEquals("ast_tok_welcome123", validUri.getQueryParameter("token"))
        assertEquals("wss://192.168.0.114:8443/ws", validUri.getQueryParameter("server"))

        val missingTokenUri = Uri.parse("aster://join")
        assertNull(missingTokenUri.getQueryParameter("token"))

        val emptyTokenUri = Uri.parse("aster://join?token=")
        assertTrue(emptyTokenUri.getQueryParameter("token").isNullOrEmpty())

        val invalidSchemeUri = Uri.parse("https://aster.chat/join?token=ast_tok_123")
        assertFalse(invalidSchemeUri.scheme == "aster")
    }

    // 7. Correct greeting and Room identity for two separate accounts
    @Test
    fun test7_twoSeparateAccounts_identityAndConversationIsolation() = runTest(testDispatcher) {
        // Seed contacts and conversation conv_alex (members: user_saad, user_alex)
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

    // 8. No message sending when unauthenticated
    @Test
    fun test8_unauthenticatedState_blocksMessageSending() = runTest(testDispatcher) {
        seedInitializer.initializeIfNeeded()
        // Ensure unauthenticated (no local user)
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

        // Verify message was not saved or left stuck in SENDING
        val messages = database.messageDao().getMessagesForConversation("conv_alex").first()
        assertFalse("Unauthorized message must not be inserted", messages.any { it.body == "Unauthorized message" })
    }
}
