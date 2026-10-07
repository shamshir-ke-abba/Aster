package com.aster.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.seed.DatabaseSeedInitializer
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val seedInitializer: DatabaseSeedInitializer,
    private val webSocketClient: com.aster.app.data.network.AsterWebSocketClient
) : ViewModel() {

    init {
        viewModelScope.launch {
            seedInitializer.initializeIfNeeded()
            val localUser = userRepository.getLocalUserOnce()
            val userId = localUser?.id ?: "user_saad"
            webSocketClient.connect(userId)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        conversationRepository.getConversations(),
        userRepository.getLocalUser()
    ) { conversations, localUser ->
        HomeUiState(
            conversations = conversations,
            localUser = localUser,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )
}
