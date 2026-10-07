package com.aster.app.domain.repository

import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for conversations and constellation memberships.
 */
interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getConversation(conversationId: String): Flow<Conversation?>
    fun getConversationMembers(conversationId: String): Flow<List<User>>
    suspend fun createConversation(conversation: Conversation, memberIds: List<String>)
    suspend fun markConversationAsRead(conversationId: String)
}
