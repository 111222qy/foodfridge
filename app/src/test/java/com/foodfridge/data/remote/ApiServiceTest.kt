package com.foodfridge.data.remote

import com.foodfridge.data.remote.dto.ActivateRequest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ApiServiceTest {

    private val retrofit = Retrofit.Builder()
        .baseUrl("http://127.0.0.1:34537/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val apiService = retrofit.create(ApiService::class.java)

    @Test
    fun testActivateDevice() = runBlocking {
        try {
            val response = apiService.activateDevice(
                token = "test_token",
                smKeys = "test_sm_keys",
                request = ActivateRequest(
                    device_number = "0123456789ABCDEF",
                    device_mac = "00:11:22:33:44:55",
                    activation_code = "test_code"
                )
            )
            println("Response code: ${response.code}")
            println("Response msg: ${response.msg}")
            println("Response data: ${response.data}")
        } catch (e: Exception) {
            println("Error: ${e.message}")
            e.printStackTrace()
        }
    }
}
