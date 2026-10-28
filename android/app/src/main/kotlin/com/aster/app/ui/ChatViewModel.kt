package com.aster.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aster.app.data.network.AsterWebSocketClient
import com.aster.app.data.network.ConnectionState
import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.Message
import com.aster.app.domain.model.User
import com.aster.app.domain.repository.ConversationRepository
import com.aster.app.domain.repository.MessageRepository
import com.aster.app.domain.repository.UserRepository
import com.aster.app.ui.notification.AsterNotificationManager
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
    val members: List<User> = emptyList(),
    val memberRoles: Map<String, String> = emptyMap(),
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val isSending: Boolean = false,
    val isLoading: Boolean = true,
    val connectionState: ConnectionState = ConnectionState.CONNECTED,
    val replyingToMessage: Message? = null,
    val selectedEffect: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val userRepository: UserRepository,
    private val notificationManager: AsterNotificationManager? = null,
    private val webSocketClient: AsterWebSocketClient? = null
) : ViewModel() {

    val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val _inputText = MutableStateFlow("")
    private val _isSending = MutableStateFlow(false)
    private val _replyingToMessage = MutableStateFlow<Message?>(null)
    private val _selectedEffect = MutableStateFlow<String?>(null)

    init {
        notificationManager?.setActiveConversation(conversationId)
        if (notificationManager?.isAppForeground != false) {
            viewModelScope.launch {
                conversationRepository.markConversationAsRead(conversationId)
                messageRepository.syncMessageReads(conversationId)
                messageRepository.syncUnsentMessages(conversationId)
            }
        }
        viewModelScope.launch {
            messageRepository.getMessages(conversationId).collect {
                if (notificationManager?.isAppForeground == true) {
                    conversationRepository.markConversationAsRead(conversationId)
                }
            }
        }
        viewModelScope.launch {
            conversationRepository.getConversation(conversationId).collect { conv ->
                val other = conv?.otherMember
                if (other != null && !other.isLocalUser) {
                    userRepository.syncPresence(listOf(other.id))
                }
            }
        }
        viewModelScope.launch {
            conversationRepository.getConversationMembers(conversationId).collect { mems ->
                val otherIds = mems.filter { !it.isLocalUser }.map { it.id }
                if (otherIds.isNotEmpty()) {
                    userRepository.syncPresence(otherIds)
                }
            }
        }
    }

    fun markAsReadIfActive() {
        if (notificationManager?.isAppForeground == true) {
            viewModelScope.launch {
                conversationRepository.markConversationAsRead(conversationId)
                messageRepository.syncMessageReads(conversationId)
                messageRepository.syncUnsentMessages(conversationId)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (notificationManager?.getActiveConversation() == conversationId) {
            notificationManager?.setActiveConversation(null)
        }
    }

    fun onScreenExited() {
        if (notificationManager?.getActiveConversation() == conversationId) {
            notificationManager?.setActiveConversation(null)
        }
    }

    private data class ComposerState(
        val input: String,
        val isSending: Boolean,
        val replyingTo: Message?,
        val connState: ConnectionState,
        val effect: String?
    )

    private val connectionFlow = webSocketClient?.connectionState ?: MutableStateFlow(ConnectionState.CONNECTED)
    private val composerFlow = combine(
        _inputText,
        _isSending,
        _replyingToMessage,
        connectionFlow,
        _selectedEffect
    ) { input, isSending, replyMsg, connState, effect ->
        ComposerState(input, isSending, replyMsg, connState, effect)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        combine(
            conversationRepository.getConversation(conversationId),
            conversationRepository.getConversationMembers(conversationId),
            conversationRepository.getConversationMemberRoles(conversationId)
        ) { conversation, members, memberRoles -> Triple(conversation, members, memberRoles) },
        messageRepository.getMessages(conversationId),
        userRepository.getLocalUser(),
        composerFlow
    ) { (conversation, members, memberRoles), messages, localUser, composerState ->
        ChatUiState(
            conversation = conversation,
            otherUser = conversation?.otherMember,
            localUser = localUser,
            members = members,
            memberRoles = memberRoles,
            messages = messages,
            inputText = composerState.input,
            isSending = composerState.isSending,
            isLoading = false,
            connectionState = composerState.connState,
            replyingToMessage = composerState.replyingTo,
            selectedEffect = composerState.effect
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState()
    )

    fun onInputTextChanged(text: String) {
        _inputText.value = text
    }

    fun selectReplyMessage(message: Message?) {
        _replyingToMessage.value = message
    }

    fun cancelReply() {
        _replyingToMessage.value = null
    }

    fun selectEffect(effect: String?) {
        _selectedEffect.value = effect
    }

    fun toggleReaction(messageId: String, emoji: String) {
        val other = uiState.value.otherUser
        if (uiState.value.conversation?.isGroup == false && other?.isBlocked == true) {
            return
        }
        val localUserId = uiState.value.localUser?.id ?: return
        viewModelScope.launch {
            messageRepository.toggleReaction(messageId, conversationId, emoji, localUserId)
        }
    }

    fun blockUser() {
        val other = uiState.value.otherUser ?: return
        viewModelScope.launch {
            userRepository.blockUser(other.id)
        }
    }

    fun unblockUser() {
        val other = uiState.value.otherUser ?: return
        viewModelScope.launch {
            userRepository.unblockUser(other.id)
        }
    }

    fun applyEffectToMessage(messageId: String, effect: String?) {
        viewModelScope.launch {
            messageRepository.setMessageEffect(messageId, effect)
        }
    }

    fun markEffectPresented(messageId: String) {
        viewModelScope.launch {
            messageRepository.markEffectPlayed(messageId)
        }
    }

    fun sendMessage() {
        val text = _inputText.value.trim()
        if (text.isEmpty()) return

        val other = uiState.value.otherUser
        if (uiState.value.conversation?.isGroup == false && other?.isBlocked == true) {
            return
        }

        val localUser = uiState.value.localUser ?: return
        val localUserId = localUser.id
        if (localUserId.isBlank()) return

        val replyToId = _replyingToMessage.value?.id
        val effect = _selectedEffect.value

        viewModelScope.launch {
            _isSending.value = true
            _inputText.value = ""
            _replyingToMessage.value = null
            _selectedEffect.value = null
            try {
                messageRepository.sendMessage(
                    conversationId = conversationId,
                    senderId = localUserId,
                    body = text,
                    replyToId = replyToId,
                    effect = effect
                )
            } finally {
                _isSending.value = false
            }
        }
    }

    fun deleteForSelf(messageId: String) {
        viewModelScope.launch {
            messageRepository.deleteForSelf(messageId)
        }
    }

    fun unsendMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.unsendMessage(messageId, conversationId)
        }
    }

    fun retryMessage(messageId: String) {
        viewModelScope.launch {
            messageRepository.retryMessage(messageId)
        }
    }

    fun renameGroup(newName: String) {
        viewModelScope.launch {
            conversationRepository.renameGroup(conversationId, newName)
        }
    }

    fun leaveGroup(onLeft: () -> Unit) {
        viewModelScope.launch {
            conversationRepository.leaveGroup(conversationId)
            onLeft()
        }
    }

    fun addMember(userId: String) {
        viewModelScope.launch {
            conversationRepository.addGroupMember(conversationId, userId)
        }
    }

    fun removeMember(userId: String) {
        viewModelScope.launch {
            conversationRepository.removeGroupMember(conversationId, userId)
        }
    }
}
