package com.aster.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.aster.app.data.local.dao.ConstellationNodePrefDao
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.DeletedMessageDao
import com.aster.app.data.local.dao.FriendRequestDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import com.aster.app.data.local.entities.ConstellationNodePrefEntity
import com.aster.app.data.local.entities.ConversationEntity
import com.aster.app.data.local.entities.ConversationMemberEntity
import com.aster.app.data.local.entities.DeletedMessageEntity
import com.aster.app.data.local.entities.FriendRequestEntity
import com.aster.app.data.local.entities.MessageEntity
import com.aster.app.data.local.entities.UserEntity

/**
 * Local Room database for Aster.
 *
 * Persists users, conversations, conversation_members, messages, locally deleted message tombstones,
 * user-local constellation node preferences (positions and highlights), and incoming friend requests.
 */
@Database(
    entities = [
        UserEntity::class,
        ConversationEntity::class,
        ConversationMemberEntity::class,
        MessageEntity::class,
        DeletedMessageEntity::class,
        ConstellationNodePrefEntity::class,
        FriendRequestEntity::class
    ],
    version = 9,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AsterDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun conversationDao(): ConversationDao
    abstract fun conversationMemberDao(): ConversationMemberDao
    abstract fun messageDao(): MessageDao
    abstract fun deletedMessageDao(): DeletedMessageDao
    abstract fun constellationNodePrefDao(): ConstellationNodePrefDao
    abstract fun friendRequestDao(): FriendRequestDao

    companion object {
        const val DATABASE_NAME = "aster.db"
    }
}
