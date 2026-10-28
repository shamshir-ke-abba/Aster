package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aster.app.data.local.entities.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversations(conversations: List<ConversationEntity>): List<Long>

    @Update
    suspend fun updateConversation(conversation: ConversationEntity)

    @Query("UPDATE conversations SET updated_at = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTimestamp(conversationId: String, updatedAt: Long)

    @Query("UPDATE conversations SET title = :title, updated_at = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: String, title: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    fun getConversationById(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversationByIdOnce(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT c.* FROM conversations c
        INNER JOIN conversation_members cm ON c.id = cm.conversation_id
        WHERE cm.user_id = :userId
        ORDER BY c.updated_at DESC
        """
    )
    fun getConversationsForUser(userId: String): Flow<List<ConversationEntity>>

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun getConversationCount(): Int

    @Query(
        """
        SELECT c.* FROM conversations c
        INNER JOIN conversation_members cm1 ON c.id = cm1.conversation_id
        INNER JOIN conversation_members cm2 ON c.id = cm2.conversation_id
        WHERE c.is_group = 0 AND cm1.user_id = :userId1 AND cm2.user_id = :userId2
        LIMIT 1
        """
    )
    suspend fun findDirectConversation(userId1: String, userId2: String): ConversationEntity?
}
