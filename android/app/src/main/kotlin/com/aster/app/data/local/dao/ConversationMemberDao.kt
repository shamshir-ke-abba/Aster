package com.aster.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: ConversationMemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<ConversationMemberEntity>)

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN conversation_members cm ON u.id = cm.user_id
        WHERE cm.conversation_id = :conversationId
        """
    )
    fun getMembersForConversation(conversationId: String): Flow<List<UserEntity>>

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN conversation_members cm ON u.id = cm.user_id
        WHERE cm.conversation_id = :conversationId
        """
    )
    suspend fun getMembersForConversationOnce(conversationId: String): List<UserEntity>

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN conversation_members cm ON u.id = cm.user_id
        WHERE cm.conversation_id = :conversationId AND u.is_local_user = 0
        LIMIT 1
        """
    )
    fun getOtherMember(conversationId: String): Flow<UserEntity?>

    @Query(
        """
        SELECT u.* FROM users u
        INNER JOIN conversation_members cm ON u.id = cm.user_id
        WHERE cm.conversation_id = :conversationId AND u.is_local_user = 0
        LIMIT 1
        """
    )
    suspend fun getOtherMemberOnce(conversationId: String): UserEntity?
}
