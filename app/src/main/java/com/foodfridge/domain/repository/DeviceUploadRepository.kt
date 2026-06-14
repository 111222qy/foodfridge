package com.foodfridge.domain.repository

import com.foodfridge.data.remote.device.dto.DeviceApiResponse
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData
import com.foodfridge.data.remote.device.dto.DoorRecordData
import com.foodfridge.data.remote.device.dto.DoorRecordResponseData
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.data.remote.device.dto.SamplingUploadResponseData
import com.foodfridge.data.remote.device.dto.TemperatureUploadData
import com.foodfridge.data.remote.device.dto.TemperatureUploadResponseData

/**
 * 设备接入平台上报仓库。
 */
interface DeviceUploadRepository {

    /**
     * 心跳保活。
     */
    suspend fun refresh(data: DeviceRefreshData): Result<DeviceApiResponse<DeviceRefreshResponseData>>

    /**
     * 留样上报。
     */
    suspend fun uploadSampling(data: SamplingUploadData): Result<DeviceApiResponse<SamplingUploadResponseData>>

    /**
     * 温度上报。
     */
    suspend fun uploadTemperature(data: TemperatureUploadData): Result<DeviceApiResponse<TemperatureUploadResponseData>>

    /**
     * 开关门记录上报。
     */
    suspend fun uploadDoorRecord(data: DoorRecordData): Result<DeviceApiResponse<DoorRecordResponseData>>
}
