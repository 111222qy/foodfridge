package com.foodfridge.data.remote.device.interceptor

import com.foodfridge.BuildConfig
import okhttp3.Interceptor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备接入平台认证拦截器。
 *
 * 在每个请求的 Header 中注入 `api-key`。
 * [BuildConfig.API_DEVICE_KEY] 为占位符，需在设备注册/配置流程确定后替换为真实值。
 */
@Singleton
class ApiKeyInterceptor @Inject constructor() : Interceptor {

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val request = original.newBuilder()
            .header(HEADER_API_KEY, BuildConfig.API_DEVICE_KEY)
            .method(original.method, original.body)
            .build()
        return chain.proceed(request)
    }

    companion object {
        private const val HEADER_API_KEY = "api-key"
    }
}
