package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.data.network.NetworkSanitizer
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
    val isLoading: Boolean = true,
    val viewMode: String = "CONSTELLATION",
    val incomingFriendRequests: List<FriendRequestSummary> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val seedInitializer: DatabaseSeedInitializer,
    private val webSocketClient: com.aster.app.data.network.AsterWebSocketClient,
    private val apiClient: AsterApiClient? = null,
    private val authStorage: com.aster.app.data.auth.AsterAuthStorage = webSocketClient.authStorage
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
        val effectiveLocalUser = if (localUser != null) {
            val cleanName = com.aster.app.domain.model.sanitizeDisplayName(localUser.displayName, localUser.id)
                ?: com.aster.app.domain.model.sanitizeDisplayName(webSocketClient.authStorage.getDisplayName(), localUser.id)
                ?: ""
            localUser.copy(displayName = cleanName)
        } else {
            val authId = webSocketClient.authStorage.getAuthenticatedUserId()
            if (!authId.isNullOrBlank()) {
                val cleanName = com.aster.app.domain.model.sanitizeDisplayName(webSocketClient.authStorage.getDisplayName(), authId) ?: ""
                User(id = authId, username = authId.removePrefix("user_"), displayName = cleanName, isLocalUser = true)
            } else null
        }
        UserData(
            localUser = effectiveLocalUser,
            availableUsers = allUsers.filter { it.id != localId && !it.isLocalUser }
        )
    }

    private val dialogFlow = combine(_isNewChatOpen, _errorMessage) { isOpen, error ->
        DialogState(isOpen, error)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        combine(
            conversationRepository.getConversations(),
            usersFlow,
            webSocketClient.connectionState
        ) { conversations, userData, connectionState ->
            Triple(conversations, userData, connectionState)
        },
        dialogFlow,
        authStorage.viewModeFlow,
        userRepository.getIncomingPendingRequests()
    ) { (conversations, userData, connectionState), dialog, viewMode, incomingRequests ->
        HomeUiState(
            conversations = conversations,
            localUser = userData.localUser,
            availableUsers = userData.availableUsers,
            isNewChatOpen = dialog.isOpen,
            errorMessage = dialog.error,
            connectionState = connectionState,
            isLoading = false,
            viewMode = viewMode,
            incomingFriendRequests = incomingRequests
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState(viewMode = authStorage.getViewMode())
    )

    fun respondToFriendRequest(requestId: String, accept: Boolean) {
        viewModelScope.launch {
            userRepository.respondToFriendRequest(requestId, accept)
        }
    }

    fun openNewChat() {
        _errorMessage.value = null
        _isNewChatOpen.value = true
        if (apiClient != null) {
            viewModelScope.launch {
                try {
                    when (val res = apiClient.getFriends()) {
                        is ApiResult.Success -> {
                            res.data.forEach { friend ->
                                userRepository.saveUser(
                                    com.aster.app.domain.model.User(
                                        id = friend.userId,
                                        username = friend.username,
                                        displayName = friend.displayName,
                                        isLocalUser = false,
                                        isOnline = friend.isOnline,
                                        lastSeen = friend.lastSeen,
                                        isDeleted = friend.isDeleted
                                    )
                                )
                            }
                            userRepository.syncPresence(res.data.map { it.userId })
                        }
                        else -> {}
                    }
                } catch (_: Exception) {}
            }
        }
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
                _errorMessage.value = NetworkSanitizer.sanitizeError(e, "Failed to start conversation")
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
                _errorMessage.value = NetworkSanitizer.sanitizeError(e, "Failed to create group")
            }
        }
    }

    fun toggleHighlight(conversationId: String, currentHighlighted: Boolean) {
        viewModelScope.launch {
            conversationRepository.setConstellationHighlight(conversationId, !currentHighlighted)
        }
    }

    fun saveNodePosition(conversationId: String, normX: Float?, normY: Float?) {
        viewModelScope.launch {
            conversationRepository.setConstellationPosition(conversationId, normX, normY)
        }
    }

    fun leaveGroup(groupId: String) {
        viewModelScope.launch {
            try {
                conversationRepository.leaveGroup(groupId)
            } catch (_: Exception) {}
        }
    }

    fun blockUser(userId: String) {
        viewModelScope.launch {
            try {
                userRepository.blockUser(userId)
            } catch (_: Exception) {}
        }
    }

    fun unblockUser(userId: String) {
        viewModelScope.launch {
            try {
                userRepository.unblockUser(userId)
            } catch (_: Exception) {}
        }
    }

    fun removeContact(userId: String) {
        viewModelScope.launch {
            try {
                apiClient?.blockUser(targetUserId = userId)
            } catch (_: Exception) {}
        }
    }
}
