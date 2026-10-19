package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aster.app.data.network.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val conversations: List<Conversation> = emptyList(),
    val localUser: User? = null,
    val availableUsers: List<User> = emptyList(),
    val isNewChatOpen: Boolean = false,
    val errorMessage: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val seedInitializer: DatabaseSeedInitializer,
    private val webSocketClient: com.aster.app.data.network.AsterWebSocketClient
) : ViewModel() {

    private val _isNewChatOpen = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            seedInitializer.initializeIfNeeded()
            val token = webSocketClient.authStorage.getAuthToken()
            if (!token.isNullOrBlank()) {
                webSocketClient.connect(token)
            }
        }
    }

    private data class UserData(
        val localUser: User?,
        val availableUsers: List<User>
    )

    private data class DialogState(
        val isOpen: Boolean,
        val error: String?
    )

    private val usersFlow = combine(
        userRepository.getLocalUser(),
        userRepository.getUsers()
    ) { localUser, allUsers ->
        val localId = localUser?.id.orEmpty()
        UserData(
            localUser = localUser,
            availableUsers = allUsers.filter { it.id != localId && !it.isLocalUser }
        )
    }

    private val dialogFlow = combine(_isNewChatOpen, _errorMessage) { isOpen, error ->
        DialogState(isOpen, error)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        conversationRepository.getConversations(),
        usersFlow,
        webSocketClient.connectionState,
        dialogFlow
    ) { conversations, userData, connectionState, dialog ->
        HomeUiState(
            conversations = conversations,
            localUser = userData.localUser,
            availableUsers = userData.availableUsers,
            isNewChatOpen = dialog.isOpen,
            errorMessage = dialog.error,
            connectionState = connectionState,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    fun openNewChat() {
        _errorMessage.value = null
        _isNewChatOpen.value = true
    }

    fun closeNewChat() {
        _isNewChatOpen.value = false
        _errorMessage.value = null
    }

    fun startConversation(userId: String, onNavigate: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _errorMessage.value = null
                val conversation = conversationRepository.getOrCreateDirectConversation(userId)
                _isNewChatOpen.value = false
                onNavigate(conversation.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to start conversation"
            }
        }
    }

    fun createGroup(name: String, memberIds: List<String>, onNavigate: (String) -> Unit) {
        viewModelScope.launch {
            try {
                if (name.isBlank()) {
                    _errorMessage.value = "Group name cannot be blank"
                    return@launch
                }
                if (memberIds.isEmpty()) {
                    _errorMessage.value = "Select at least one member"
                    return@launch
                }
                _errorMessage.value = null
                val conversation = conversationRepository.createGroup(name.trim(), memberIds)
                _isNewChatOpen.value = false
                onNavigate(conversation.id)
            } catch (e: Exception) {
                _errorMessage.value = e.message ?: "Failed to create group"
            }
        }
    }
}
