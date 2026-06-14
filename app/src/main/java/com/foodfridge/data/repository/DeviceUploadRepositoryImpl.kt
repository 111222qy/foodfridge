package com.foodfridge.data.repository

import com.foodfridge.data.remote.device.dto.DeviceApiResponse
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData
import com.foodfridge.data.remote.device.dto.DoorRecordData
import com.foodfridge.data.remote.device.dto.DoorRecordResponseData
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.data.remote.device.dto.SamplingUploadResponseData
import com.foodfridge.data.remote.device.dto.TemperatureUploadData
import com.foodfridge.data.remote.device.dto.TemperatureUploadResponseData
import com.foodfridge.data.remote.device.route.DeviceUploadApiService
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

    override suspend fun refresh(
        data: DeviceRefreshData,
    ): Result<DeviceApiResponse<DeviceRefreshResponseData>> {
        return try {
            Result.success(deviceUploadApiService.refresh(data))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadSampling(
        data: SamplingUploadData,
    ): Result<DeviceApiResponse<SamplingUploadResponseData>> {
        return try {
            Result.success(deviceUploadApiService.uploadSampling(data))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadTemperature(
        data: TemperatureUploadData,
    ): Result<DeviceApiResponse<TemperatureUploadResponseData>> {
        return try {
            Result.success(deviceUploadApiService.uploadTemperature(data))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadDoorRecord(
        data: DoorRecordData,
    ): Result<DeviceApiResponse<DoorRecordResponseData>> {
        return try {
            Result.success(deviceUploadApiService.uploadDoorRecord(data))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
