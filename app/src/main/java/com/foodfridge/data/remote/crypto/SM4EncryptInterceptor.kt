package com.foodfridge.data.remote.crypto

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import timber.log.Timber

class SM4EncryptInterceptor : Interceptor {

    private val jsonMediaType = "application/json; charset=UTF-8".toMediaTypeOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method

        if (method in listOf("POST", "PUT", "PATCH")) {
            val body = request.body
            if (body != null && body.contentLength() > 0) {
                val buffer = okio.Buffer()
                body.writeTo(buffer)
                val originalJson = buffer.readUtf8()

                Timber.d("SM4 Original Request JSON: $originalJson")

                val encryptedHex = try {
                    SM4Crypto.encrypt(originalJson)
                } catch (e: Exception) {
                    Timber.e(e, "SM4 encryption failed")
                    return chain.proceed(request)
                }

                Timber.d("SM4 Encrypted Request: $encryptedHex")

                val encryptedBody = encryptedHex.toRequestBody("text/plain; charset=UTF-8".toMediaTypeOrNull())

                val newRequest = request.newBuilder()
                    .method(method, encryptedBody)
                    .header("Content-Type", "application/json")
                    .build()

                val response = chain.proceed(newRequest)
                return handleEncryptedResponse(response)
            }
        }

        val response = chain.proceed(request)
        return handleEncryptedResponse(response)
    }

    private fun handleEncryptedResponse(response: Response): Response {
        val body = response.body ?: return response
        val contentType = body.contentType()
        val contentLength = body.contentLength()

        if (contentLength == 0L) return response
        if (contentType != null && contentType.subtype != "json") return response

        val responseString = body.string()

        val decryptedJson = try {
            SM4Crypto.decrypt(responseString)
        } catch (e: Exception) {
            Timber.e(e, "SM4 decryption failed, returning original response")
            responseString
        }

        Timber.d("SM4 Decrypted Response: $decryptedJson")

        val newBody = decryptedJson.toResponseBody(contentType)
        return response.newBuilder()
            .body(newBody)
            .build()
    }
}
