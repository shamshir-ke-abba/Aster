package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.network.ApiResult
import com.aster.app.data.network.AsterApiClient
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.data.network.FriendRequestSummary
import com.aster.app.data.network.NetworkSanitizer
import com.aster.app.data.network.UserSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectionsUiState(
    val searchQuery: String = "",
    val searchResults: List<UserSummary> = emptyList(),
    val friends: List<UserSummary> = emptyList(),
    val incomingRequests: List<FriendRequestSummary> = emptyList(),
    val outgoingRequests: List<FriendRequestSummary> = emptyList(),
    val blockedUsers: List<UserSummary> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.CONNECTED,
    val isLoading: Boolean = false,
    val isSearching: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null
)

@HiltViewModel
class ConnectionsViewModel @Inject constructor(
    private val apiClient: AsterApiClient,
    private val userRepository: com.aster.app.domain.repository.UserRepository? = null,
    private val conversationRepository: com.aster.app.domain.repository.ConversationRepository? = null,
    private val webSocketClient: AsterWebSocketClient? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        loadData()
        webSocketClient?.let { ws ->
            viewModelScope.launch {
                ws.connectionState.collect { connState ->
                    _uiState.value = _uiState.value.copy(connectionState = connState)
                }
            }
        }
        userRepository?.let { repo ->
            viewModelScope.launch {
                repo.getIncomingPendingRequests().collect { incoming ->
                    _uiState.value = _uiState.value.copy(incomingRequests = incoming)
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val friendsRes = apiClient.getFriends()
            val requestsRes = apiClient.getFriendRequests()
            val blockedRes = apiClient.getBlockedUsers()

            val friends = if (friendsRes is ApiResult.Success) friendsRes.data else emptyList()
            val incoming = if (requestsRes is ApiResult.Success) requestsRes.data.incoming else emptyList()
            val outgoing = if (requestsRes is ApiResult.Success) requestsRes.data.outgoing else emptyList()
            val blocked = if (blockedRes is ApiResult.Success) blockedRes.data else emptyList()

            // Sync confirmed friends into local Room database for seamless messaging and constellation views
            if (userRepository != null && friends.isNotEmpty()) {
                friends.forEach { friend ->
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
                userRepository.syncPresence(friends.map { it.userId })
            }

            _uiState.value = _uiState.value.copy(
                friends = friends,
                incomingRequests = incoming,
                outgoingRequests = outgoing,
                blockedUsers = blocked,
                isLoading = false
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        if (query.trim().length >= 2) {
            performSearch(query.trim())
        } else {
            _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSearching = true)
            when (val res = apiClient.searchUsers(query)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(searchResults = res.data, isSearching = false)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(searchResults = emptyList(), isSearching = false)
                }
            }
        }
    }

    fun sendFriendRequest(targetUserId: String? = null, targetAsterId: String? = null) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = apiClient.sendFriendRequest(targetUserId, targetAsterId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "Friend request sent", isLoading = false)
                    loadData()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message, isLoading = false)
                }
            }
        }
    }

    fun respondToRequest(requestId: String, accept: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val success = if (userRepository != null) {
                userRepository.respondToFriendRequest(requestId, accept)
            } else {
                apiClient.respondFriendRequest(requestId, accept) is ApiResult.Success
            }
            if (success) {
                _uiState.value = _uiState.value.copy(
                    message = if (accept) "Friend request accepted" else "Friend request declined",
                    isLoading = false
                )
                loadData()
            } else {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to respond to request",
                    isLoading = false
                )
            }
        }
    }

    fun cancelRequest(requestId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = apiClient.cancelFriendRequest(requestId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "Request cancelled", isLoading = false)
                    loadData()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message, isLoading = false)
                }
            }
        }
    }

    fun blockUser(targetUserId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            userRepository?.blockUser(targetUserId)
            when (val res = apiClient.blockUser(targetUserId = targetUserId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "User blocked", isLoading = false)
                    loadData()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message, isLoading = false)
                }
            }
        }
    }

    fun unblockUser(targetUserId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            userRepository?.unblockUser(targetUserId)
            when (val res = apiClient.unblockUser(targetUserId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "User unblocked", isLoading = false)
                    loadData()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message, isLoading = false)
                }
            }
        }
    }

    fun startChat(friend: UserSummary, onNavigateToChat: (String) -> Unit) {
        viewModelScope.launch {
            try {
                _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
                userRepository?.saveUser(
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
                if (conversationRepository != null) {
                    val conversation = conversationRepository.getOrCreateDirectConversation(friend.userId)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onNavigateToChat(conversation.id)
                } else {
                    val localUserId = userRepository?.getLocalUserOnce()?.id.orEmpty()
                    val fallbackId = if (localUserId.isNotBlank()) {
                        com.aster.app.domain.repository.getCanonicalDirectConversationId(localUserId, friend.userId)
                    } else {
                        "conv_${friend.userId}"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onNavigateToChat(fallbackId)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = NetworkSanitizer.sanitizeError(e, "Failed to open chat"))
            }
        }
    }

    fun removeFriend(friendUserId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val res = apiClient.blockUser(targetUserId = friendUserId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(message = "Contact removed", isLoading = false)
                    loadData()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = res.message, isLoading = false)
                }
            }
        }
    }

    fun clearFeedback() {
        _uiState.value = _uiState.value.copy(message = null, errorMessage = null)
    }
}
