package com.aster.app

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.repository.ConversationRepositoryImpl
import com.aster.app.data.repository.MessageRepositoryImpl
import com.aster.app.data.repository.UserRepositoryImpl
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.domain.model.MessageStatus
import com.aster.app.ui.ChatViewModel
import com.aster.app.ui.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var database: AsterDatabase
    private lateinit var userRepository: UserRepositoryImpl
    private lateinit var conversationRepository: ConversationRepositoryImpl
    private lateinit var messageRepository: MessageRepositoryImpl
    private lateinit var seedInitializer: DatabaseSeedInitializer
    private lateinit var webSocketClient: com.aster.app.data.network.AsterWebSocketClient

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AsterDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        webSocketClient = com.aster.app.data.network.AsterWebSocketClient(okhttp3.OkHttpClient())

        userRepository = UserRepositoryImpl(database.userDao())
        conversationRepository = ConversationRepositoryImpl(
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao(),
            userDao = database.userDao()
        )
        messageRepository = MessageRepositoryImpl(
            messageDao = database.messageDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            userDao = database.userDao(),
            webSocketClient = webSocketClient
        )
        seedInitializer = DatabaseSeedInitializer(
            userDao = database.userDao(),
            conversationDao = database.conversationDao(),
            conversationMemberDao = database.conversationMemberDao(),
            messageDao = database.messageDao()
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        database.close()
    }

    @Test
    fun homeViewModel_initialStateAndSeededConversations() = runTest(testDispatcher) {
        val viewModel = HomeViewModel(
            conversationRepository = conversationRepository,
            userRepository = userRepository,
            seedInitializer = seedInitializer,
            webSocketClient = webSocketClient
        )

        advanceUntilIdle()

        val state = viewModel.uiState.filter { !it.isLoading && it.conversations.isNotEmpty() && it.localUser != null }.first()
        assertFalse(state.isLoading)
        assertEquals("Saad", state.localUser?.displayName)
        assertTrue(state.conversations.isNotEmpty())
        assertEquals(2, state.conversations.size)
    }

    @Test
    fun chatViewModel_loadMessagesAndSendMessage() = runTest(testDispatcher) {
        // Pre-seed database
        seedInitializer.initializeIfNeeded()

        val savedStateHandle = SavedStateHandle(mapOf("conversationId" to "conv_alex"))
        val viewModel = ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            userRepository = userRepository
        )

        advanceUntilIdle()

        // Verify initial loaded state
        val state = viewModel.uiState.filter { it.messages.isNotEmpty() }.first()
        assertEquals("Alex", state.conversation?.title)
        assertEquals(3, state.messages.size)

        // Type a new message
        viewModel.onInputTextChanged("See you there!")
        advanceUntilIdle()
        assertEquals("See you there!", viewModel.uiState.value.inputText)

        // Send the message
        viewModel.sendMessage()
        advanceUntilIdle()

        // Input text should be cleared in uiState
        val clearedState = viewModel.uiState.filter { it.inputText.isEmpty() && it.messages.size == 4 }.first()
        assertEquals("", clearedState.inputText)

        val lastMsg = clearedState.messages.last()
        assertEquals("See you there!", lastMsg.body)
        assertEquals(MessageStatus.SENDING, lastMsg.status)
        assertEquals("user_saad", lastMsg.senderId)
    }
}
