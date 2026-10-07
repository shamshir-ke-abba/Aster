package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNotExists(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId AND status = :currentStatus")
    suspend fun updateMessageStatusIfCurrent(messageId: String, currentStatus: MessageStatus, status: MessageStatus): Int

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at ASC, id ASC")
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT 1")
    fun getLatestMessage(conversationId: String): Flow<MessageEntity?>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun getLatestMessageOnce(conversationId: String): MessageEntity?

    @Query(
        """
        SELECT COUNT(*) FROM messages 
        WHERE conversation_id = :conversationId 
        AND sender_id != :localUserId 
        AND status != 'READ'
        """
    )
    fun getUnreadCount(conversationId: String, localUserId: String): Flow<Int>

    @Query(
        """
        UPDATE messages 
        SET status = 'READ' 
        WHERE conversation_id = :conversationId 
        AND sender_id != :localUserId 
        AND status != 'READ'
        """
    )
    suspend fun markMessagesAsRead(conversationId: String, localUserId: String)

    @Query("SELECT * FROM messages WHERE sender_id = :localUserId AND status = 'SENDING' ORDER BY created_at ASC, id ASC")
    suspend fun getPendingMessages(localUserId: String): List<MessageEntity>
}
