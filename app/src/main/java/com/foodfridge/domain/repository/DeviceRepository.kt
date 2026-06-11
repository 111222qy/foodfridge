package com.foodfridge.domain.repository

import com.foodfridge.data.remote.dto.ActivateResponseData
import com.foodfridge.data.remote.dto.ApiResponse

interface DeviceRepository {

    suspend fun activateDevice(
        apisix: String,
        token: String,
        smKeys: String,
        deviceNumber: String,
        deviceMac: String,
        activationCode: String
    ): Result<ApiResponse<ActivateResponseData>>
}
