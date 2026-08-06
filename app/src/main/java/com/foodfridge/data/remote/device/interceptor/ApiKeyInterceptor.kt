package com.foodfridge.data.remote.device.interceptor

import com.foodfridge.data.local.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备接入平台认证拦截器。
 *
 * 在每个请求的 Header 中注入 `api-key`。
 */
@Singleton
class ApiKeyInterceptor @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) : Interceptor {

    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var currentApiKey: String? = null

    fun setApiKey(apiKey: String?) {
        synchronized(initializationLock) {
            currentApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            initialized = true
        }
    }

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        initializeFromPreferencesIfNeeded()
        val apiKey = currentApiKey ?: throw MissingDeviceApiKeyException()
        val original = chain.request()
        val request = original.newBuilder()
            .header(HEADER_API_KEY, apiKey)
            .method(original.method, original.body)
            .build()
        return chain.proceed(request)
    }

    private fun initializeFromPreferencesIfNeeded() {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            runCatching {
                runBlocking(Dispatchers.IO) {
                    userPreferencesRepository.apiDeviceKey.first()
                }
            }.onSuccess { savedKey ->
                currentApiKey = savedKey
            }.onFailure { error ->
                Timber.e(error, "Failed to restore device API key")
            }
            initialized = true
        }
    }

    companion object {
        private const val HEADER_API_KEY = "api-key"
    }
}

class MissingDeviceApiKeyException :
    IOException("平台 API Key 未配置")
