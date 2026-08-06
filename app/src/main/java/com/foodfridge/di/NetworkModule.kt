package com.foodfridge.di

import com.foodfridge.BuildConfig
import com.foodfridge.data.remote.ApiService
import com.foodfridge.data.remote.crypto.SM4EncryptInterceptor
import com.foodfridge.data.remote.device.interceptor.ApiKeyInterceptor
import com.foodfridge.data.remote.device.interceptor.DynamicBaseUrlInterceptor
import com.foodfridge.data.remote.device.route.DeviceUploadApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            redactHeader("apisix")
            redactHeader("Token")
            redactHeader("SmKeys")
            level = if (BuildConfig.DEBUG) {
                // HEADERS 避免大响应体导致 OOM
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val sm4Interceptor = SM4EncryptInterceptor()

        return OkHttpClient.Builder()
            .addInterceptor(sm4Interceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideApiService(retrofit: Retrofit): ApiService {
        return retrofit.create(ApiService::class.java)
    }

    // ── 设备接入平台专用客户端（与旧接口完全独立） ─────────────────────────

    @Provides
    @Singleton
    @Named("device")
    fun provideDeviceOkHttpClient(
        apiKeyInterceptor: ApiKeyInterceptor,
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            redactHeader("api-key")
            level = if (BuildConfig.DEBUG) {
                // HEADERS 避免大响应体导致 OOM
                HttpLoggingInterceptor.Level.HEADERS
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .addInterceptor(dynamicBaseUrlInterceptor) // 动态 URL 必须在 api-key 之前
            .addInterceptor(apiKeyInterceptor)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    @Provides
    @Singleton
    @Named("device")
    fun provideDeviceRetrofit(
        @Named("device") okHttpClient: OkHttpClient,
    ): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideDeviceUploadApiService(
        @Named("device") retrofit: Retrofit,
    ): DeviceUploadApiService {
        return retrofit.create(DeviceUploadApiService::class.java)
    }
}
