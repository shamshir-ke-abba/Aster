package com.aster.app.di

import android.content.Context
import com.aster.app.R
import com.aster.app.data.network.AsterTlsManager
import com.aster.app.data.network.AsterWebSocketClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(15, TimeUnit.SECONDS)

        val caInputStream = context.resources.openRawResource(R.raw.aster_ca)
        val tlsConfig = AsterTlsManager.createTlsConfiguration(caInputStream)
        builder.sslSocketFactory(tlsConfig.sslSocketFactory, tlsConfig.trustManager)

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideAsterWebSocketClient(
        okHttpClient: OkHttpClient,
        authStorage: com.aster.app.data.auth.AsterAuthStorage,
        @ApplicationContext context: Context
    ): AsterWebSocketClient {
        return AsterWebSocketClient(okHttpClient, authStorage, context)
    }

    @Provides
    @Singleton
    fun provideAsterApiClient(
        okHttpClient: OkHttpClient,
        authStorage: com.aster.app.data.auth.AsterAuthStorage
    ): com.aster.app.data.network.AsterApiClient {
        return com.aster.app.data.network.AsterApiClient(okHttpClient, authStorage)
    }

    @Provides
    fun provideCoroutineDispatcher(): kotlinx.coroutines.CoroutineDispatcher {
        return kotlinx.coroutines.Dispatchers.IO
    }
}
