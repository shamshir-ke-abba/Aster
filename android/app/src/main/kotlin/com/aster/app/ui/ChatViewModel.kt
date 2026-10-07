package com.aster.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val conversation: Conversation? = null,
    val otherUser: User? = null,
    val localUser: User? = null,
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isLoading: Boolean = true
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _inputText = MutableStateFlow("")
    private val _isSending = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            conversationRepository.markConversationAsRead(conversationId)
        }
    }

    val uiState: StateFlow<ChatUiState> = combine(
        conversationRepository.getConversation(conversationId),
        messageRepository.getMessages(conversationId),
        userRepository.getLocalUser(),
        _inputText,
        _isSending
    ) { conversation, messages, localUser, inputText, isSending ->
        ChatUiState(
            conversation = conversation,
            otherUser = conversation?.otherMember,
            localUser = localUser,
            messages = messages,
            inputText = inputText,
            isSending = isSending,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val localUserId = uiState.value.localUser?.id ?: "user_saad"

        viewModelScope.launch {
            _isSending.value = true
            _inputText.value = ""
            try {
                messageRepository.sendMessage(
                    conversationId = conversationId,
                    senderId = localUserId,
                    body = text
                )
            } finally {
                _isSending.value = false
            }
        }
    }
}
