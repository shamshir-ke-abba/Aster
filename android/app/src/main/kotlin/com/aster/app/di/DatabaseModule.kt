package com.aster.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.dao.ConstellationNodePrefDao
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
import com.aster.app.data.local.dao.DeletedMessageDao
import com.aster.app.data.local.dao.FriendRequestDao
import com.aster.app.data.local.dao.MessageDao
import com.aster.app.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS deleted_messages (message_id TEXT PRIMARY KEY NOT NULL, deleted_at INTEGER NOT NULL)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE users ADD COLUMN last_seen INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE users ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN reactions TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("ALTER TABLE messages ADD COLUMN effect TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS constellation_node_prefs (
                    conversationId TEXT PRIMARY KEY NOT NULL,
                    customNormalizedX REAL,
                    customNormalizedY REAL,
                    isHighlighted INTEGER NOT NULL DEFAULT 0,
                    updatedAt INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN is_effect_played INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET is_effect_played = 1")
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Recreate messages table to ensure defaultValue = 0 matches Room TableInfo expectation
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS messages_new (
                    id TEXT PRIMARY KEY NOT NULL,
                    conversation_id TEXT NOT NULL,
                    sender_id TEXT NOT NULL,
                    body TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    status TEXT NOT NULL,
                    reply_to_id TEXT,
                    reactions TEXT NOT NULL DEFAULT '{}',
                    effect TEXT DEFAULT NULL,
                    is_effect_played INTEGER NOT NULL DEFAULT 0,
                    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
                    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("""
                INSERT INTO messages_new (id, conversation_id, sender_id, body, created_at, status, reply_to_id, reactions, effect, is_effect_played)
                SELECT id, conversation_id, sender_id, body, created_at, status, reply_to_id, reactions, effect, is_effect_played FROM messages
            """.trimIndent())
            db.execSQL("DROP TABLE messages")
            db.execSQL("ALTER TABLE messages_new RENAME TO messages")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversation_id ON messages(conversation_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sender_id ON messages(sender_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_created_at ON messages(created_at)")
        }
    }

    val MIGRATION_5_7 = object : Migration(5, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE messages ADD COLUMN is_effect_played INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET is_effect_played = 1")
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS friend_requests (
                    id TEXT PRIMARY KEY NOT NULL,
                    senderUserId TEXT NOT NULL,
                    recipientUserId TEXT NOT NULL,
                    status TEXT NOT NULL,
                    senderDisplayName TEXT NOT NULL,
                    senderUsername TEXT NOT NULL,
                    senderAsterId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE users ADD COLUMN is_blocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideAsterDatabase(
        @ApplicationContext context: Context
    ): AsterDatabase {
        return Room.databaseBuilder(
            context,
            AsterDatabase::class.java,
            AsterDatabase.DATABASE_NAME
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_5_7, MIGRATION_7_8, MIGRATION_8_9)
         .fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideUserDao(database: AsterDatabase): UserDao = database.userDao()

    @Provides
    fun provideConversationDao(database: AsterDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideConversationMemberDao(database: AsterDatabase): ConversationMemberDao =
        database.conversationMemberDao()

    @Provides
    fun provideMessageDao(database: AsterDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideDeletedMessageDao(database: AsterDatabase): DeletedMessageDao = database.deletedMessageDao()

    @Provides
    fun provideConstellationNodePrefDao(database: AsterDatabase): ConstellationNodePrefDao =
        database.constellationNodePrefDao()

    @Provides
    fun provideFriendRequestDao(database: AsterDatabase): FriendRequestDao =
        database.friendRequestDao()
}
