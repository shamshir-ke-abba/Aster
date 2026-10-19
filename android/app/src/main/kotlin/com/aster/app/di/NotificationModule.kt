package com.aster.app.di

import android.content.Context
import com.aster.app.ui.notification.AsterNotificationManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NotificationModule {

    @Provides
    @Singleton
    fun provideAsterNotificationManager(
        @ApplicationContext context: Context
    ): AsterNotificationManager {
        val manager = AsterNotificationManager(context)
        manager.initChannels()
        return manager
    }
}
