package com.foodfridge.data.repository

import com.foodfridge.data.remote.ApiService
import com.foodfridge.data.remote.dto.ActivateRequest
import com.foodfridge.data.remote.dto.ActivateResponseData
import com.foodfridge.data.remote.dto.ApiResponse
import com.foodfridge.domain.repository.DeviceRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : DeviceRepository {

    override suspend fun activateDevice(
        apisix: String,
        token: String,
        smKeys: String,
        deviceNumber: String,
        deviceMac: String,
        activationCode: String
    ): Result<ApiResponse<ActivateResponseData>> {
        return try {
            val request = ActivateRequest(
                device_number = deviceNumber,
                device_mac = deviceMac,
                activation_code = activationCode
            )
            val response = apiService.activateDevice(apisix, token, smKeys, request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
