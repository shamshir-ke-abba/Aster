package com.aster.app.di

import com.aster.app.data.network.AsterWebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideAsterWebSocketClient(okHttpClient: OkHttpClient): AsterWebSocketClient {
        return AsterWebSocketClient(okHttpClient)
    }
}
