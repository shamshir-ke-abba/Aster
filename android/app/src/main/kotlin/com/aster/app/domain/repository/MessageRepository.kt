package com.aster.app.domain.repository

import com.aster.app.domain.model.Message
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for message operations.
 */
interface MessageRepository {
    fun getMessages(conversationId: String): Flow<List<Message>>
    suspend fun sendMessage(
        conversationId: String,
        senderId: String,
        body: String,
        replyToId: String? = null,
        effect: String? = null
    ): Message
    suspend fun retryMessage(messageId: String): Boolean
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
    suspend fun syncMessageReads(conversationId: String) {}
    suspend fun deleteForSelf(messageId: String) {}
    suspend fun unsendMessage(messageId: String, conversationId: String): Boolean = false
    suspend fun syncUnsentMessages(conversationId: String) {}
    suspend fun toggleReaction(messageId: String, emoji: String, userId: String): Boolean = false
    suspend fun toggleReaction(messageId: String, conversationId: String, emoji: String, userId: String): Boolean =
        toggleReaction(messageId, emoji, userId)
    suspend fun setMessageEffect(messageId: String, effect: String?) {}
    suspend fun markEffectPlayed(messageId: String) {}
}
