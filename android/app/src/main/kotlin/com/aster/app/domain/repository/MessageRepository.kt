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
        replyToId: String? = null
    ): Message
    suspend fun retryMessage(messageId: String): Boolean
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)
}
