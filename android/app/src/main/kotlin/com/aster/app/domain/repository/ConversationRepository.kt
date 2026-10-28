package com.aster.app.domain.repository

import com.aster.app.domain.model.Conversation
import com.aster.app.domain.model.User
import kotlinx.coroutines.flow.Flow

/**
 * Generates deterministic, canonical 1:1 conversation ID for a pair of user IDs.
 * Independent of participant order (A -> B is identical to B -> A).
 */
fun getCanonicalDirectConversationId(userId1: String, userId2: String): String {
    val sorted = listOf(userId1.trim(), userId2.trim()).sorted()
    return "conv_${sorted[0]}_${sorted[1]}"
}

/**
 * Repository interface for conversations and constellation memberships.
 */
interface ConversationRepository {
    fun getConversations(): Flow<List<Conversation>>
    fun getConversation(conversationId: String): Flow<Conversation?>
    fun getConversationMembers(conversationId: String): Flow<List<User>>
    fun getConversationMemberRoles(conversationId: String): Flow<Map<String, String>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    suspend fun createConversation(conversation: Conversation, memberIds: List<String>)
    suspend fun getOrCreateDirectConversation(otherUserId: String): Conversation
    suspend fun markConversationAsRead(conversationId: String)
    suspend fun createGroup(name: String, memberIds: List<String>): Conversation
    suspend fun refreshGroups()
    suspend fun addGroupMember(groupId: String, userId: String)
    suspend fun removeGroupMember(groupId: String, userId: String)
    suspend fun leaveGroup(groupId: String)
    suspend fun renameGroup(groupId: String, newName: String)
    fun getNodePrefs(): Flow<List<com.aster.app.data.local.entities.ConstellationNodePrefEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
    suspend fun setConstellationHighlight(conversationId: String, isHighlighted: Boolean) {}
    suspend fun setConstellationPosition(conversationId: String, normalizedX: Float?, normalizedY: Float?) {}
}
