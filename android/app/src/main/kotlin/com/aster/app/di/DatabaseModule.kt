package com.aster.app.di

import android.content.Context
import androidx.room.Room
import com.aster.app.data.local.AsterDatabase
import com.aster.app.data.local.dao.ConversationDao
import com.aster.app.data.local.dao.ConversationMemberDao
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

    @Provides
    @Singleton
    fun provideAsterDatabase(
        @ApplicationContext context: Context
    ): AsterDatabase {
        return Room.databaseBuilder(
            context,
            AsterDatabase::class.java,
            AsterDatabase.DATABASE_NAME
        ).fallbackToDestructiveMigration().build()
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
}
