package com.foodfridge.data.remote.device.interceptor

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态 Base URL 拦截器。
 *
 * 允许在运行时修改 API 基础地址，无需重建 Retrofit。
 * 优先级：[currentBaseUrl]（运行时设置）> 构造时的 [defaultBaseUrl]。
 */
@Singleton
class DynamicBaseUrlInterceptor @Inject constructor() : Interceptor {

    @Volatile
    var currentBaseUrl: String? = null

    @Volatile
    private var cachedHttpUrl: HttpUrl? = null

    fun setBaseUrl(url: String?) {
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
        val base = cachedHttpUrl ?: return chain.proceed(chain.request())
        val original = chain.request()
        val newUrl = original.url.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
        return chain.proceed(original.newBuilder().url(newUrl).build())
    }
}
