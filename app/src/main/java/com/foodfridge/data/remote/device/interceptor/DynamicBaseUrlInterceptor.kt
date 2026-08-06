package com.foodfridge.data.remote.device.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import com.foodfridge.data.local.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态 Base URL 拦截器。
 *
 * 允许在运行时修改 API 基础地址，无需重建 Retrofit。
 * 第一次请求前会从 DataStore 恢复保存值，设置页修改后立即覆盖。
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
) : Interceptor {

    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    var currentBaseUrl: String? = null
        private set

    @Volatile
    private var cachedHttpUrl: HttpUrl? = null

    fun setBaseUrl(url: String?) {
        synchronized(initializationLock) {
            applyBaseUrl(url)
            initialized = true
        }
    }

    private fun applyBaseUrl(url: String?) {
        currentBaseUrl = url
        cachedHttpUrl = if (!url.isNullOrBlank()) {
            try {
                var normalized = url.trim()
                // 自动补全协议前缀
                if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
                    normalized = "http://$normalized"
                }
                if (!normalized.endsWith("/")) normalized = "$normalized/"
                normalized.toHttpUrl()
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        initializeFromPreferencesIfNeeded()
        val base = cachedHttpUrl ?: return chain.proceed(chain.request())
        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }

    private fun initializeFromPreferencesIfNeeded() {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return
            runCatching {
                runBlocking(Dispatchers.IO) {
                    userPreferencesRepository.apiBaseUrl.first()
                }
            }.onSuccess(::applyBaseUrl)
                .onFailure { error ->
                    Timber.e(error, "Failed to restore dynamic API base URL; using build default")
                }
            initialized = true
        }
    }
}
