package com.foodfridge.domain.repository

import com.foodfridge.data.local.entity.PendingUploadEntity

/**
 * 设备平台上报类型。
 */
enum class PendingUploadType {
    SAMPLING,
    TEMPERATURE,
    DOOR,
}

/**
 * 设备平台上报仓库。
 *
 * 除立即上传外，失败时会将请求序列化到本地 [PendingUploadEntity] 队列，
 * 供后续心跳或定时任务重试。
 */
interface DeviceUploadRepository {

    /**
     * 心跳保活。
     */
    suspend fun refresh(data: com.foodfridge.data.remote.device.dto.DeviceRefreshData): Result<com.foodfridge.data.remote.device.dto.DeviceApiResponse<com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData>>

    /**
     * 留样上报。失败时自动入队。
     */
    suspend fun uploadSampling(data: com.foodfridge.data.remote.device.dto.SamplingUploadData): Result<com.foodfridge.data.remote.device.dto.DeviceApiResponse<com.foodfridge.data.remote.device.dto.SamplingUploadResponseData>>

    /**
     * 温度上报。失败时自动入队。
     */
    suspend fun uploadTemperature(data: com.foodfridge.data.remote.device.dto.TemperatureUploadData): Result<com.foodfridge.data.remote.device.dto.DeviceApiResponse<com.foodfridge.data.remote.device.dto.TemperatureUploadResponseData>>

    /**
     * 开关门记录上报。失败时自动入队。
     */
    suspend fun uploadDoorRecord(data: com.foodfridge.data.remote.device.dto.DoorRecordData): Result<com.foodfridge.data.remote.device.dto.DeviceApiResponse<com.foodfridge.data.remote.device.dto.DoorRecordResponseData>>

    /**
     * 刷新并重试所有本地缓存的上报。
     * @return 成功处理的条目数。
     */
    suspend fun flushPendingUploads(): Int
}
