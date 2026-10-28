package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aster.app.data.local.entities.DeletedMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeletedMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeletedMessage(deletedMessage: DeletedMessageEntity)

    @Query("SELECT message_id FROM deleted_messages")
    fun getDeletedMessageIds(): Flow<List<String>>

    @Query("SELECT message_id FROM deleted_messages")
    suspend fun getDeletedMessageIdsOnce(): List<String>

    @Query("SELECT 1 FROM deleted_messages WHERE message_id = :messageId LIMIT 1")
    suspend fun isDeleted(messageId: String): Int?
}
