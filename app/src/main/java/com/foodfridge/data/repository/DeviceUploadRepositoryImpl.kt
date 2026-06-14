package com.foodfridge.data.repository

import com.foodfridge.data.remote.device.route.DeviceUploadApiService
import com.foodfridge.data.remote.device.dto.DeviceApiRequest
import com.foodfridge.data.remote.device.dto.DeviceApiResponse
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.data.remote.device.dto.SamplingUploadResponseData
import com.foodfridge.domain.repository.DeviceUploadRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备接入平台上报仓库实现。
 */
@Singleton
class DeviceUploadRepositoryImpl @Inject constructor(
    private val deviceUploadApiService: DeviceUploadApiService,
) : DeviceUploadRepository {

    override suspend fun refresh(data: DeviceRefreshData): Result<DeviceApiResponse<DeviceRefreshResponseData>> {
        return try {
            val request = DeviceApiRequest(data = data)
            val response = deviceUploadApiService.refresh(request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadSampling(
        data: SamplingUploadData,
    ): Result<DeviceApiResponse<SamplingUploadResponseData>> {
        return try {
            val request = DeviceApiRequest(data = data)
            val response = deviceUploadApiService.uploadSampling(request)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
