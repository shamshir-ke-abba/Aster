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
    suspend fun getOrCreateDirectConversation(otherUserId: String): Conversation
    suspend fun markConversationAsRead(conversationId: String)
    suspend fun createGroup(name: String, memberIds: List<String>): Conversation
    suspend fun refreshGroups()
    suspend fun addGroupMember(groupId: String, userId: String)
    suspend fun removeGroupMember(groupId: String, userId: String)
    suspend fun leaveGroup(groupId: String)
    suspend fun renameGroup(groupId: String, newName: String)
}
