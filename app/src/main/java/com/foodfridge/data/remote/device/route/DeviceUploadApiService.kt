package com.foodfridge.data.remote.device.route

import com.foodfridge.data.remote.device.dto.DeviceApiResponse
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData
import com.foodfridge.data.remote.device.dto.DoorRecordData
import com.foodfridge.data.remote.device.dto.DoorRecordResponseData
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.data.remote.device.dto.SamplingUploadResponseData
import com.foodfridge.data.remote.device.dto.TemperatureUploadData
import com.foodfridge.data.remote.device.dto.TemperatureUploadResponseData
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 设备接入平台接口路由。
 *
 * 与旧版 [com.foodfridge.data.remote.ApiService] 完全独立，用于 `/api/device/...` 设备主动上报。
 *
 * 请求体均为纯业务 JSON，不再使用 `code/message/data` 包装；
 * 响应体仍使用 [DeviceApiResponse] 包装。
 */
interface DeviceUploadApiService {

    /**
     * 心跳保活。
     */
    @POST("/api/device/refresh")
    suspend fun refresh(
        @Body request: DeviceRefreshData,
    ): DeviceApiResponse<DeviceRefreshResponseData>

    /**
     * 留样上报。
     */
    @POST("/api/device/sampling/upload")
    suspend fun uploadSampling(
        @Body request: SamplingUploadData,
    ): DeviceApiResponse<SamplingUploadResponseData>

    /**
     * 温度上报。
     *
     * 路径与《设备接入方案》第 5.2.2 节对齐。
     */
    @POST("/api/device/sampling-fridge/temperature/upload")
    suspend fun uploadTemperature(
        @Body request: TemperatureUploadData,
    ): DeviceApiResponse<TemperatureUploadResponseData>

    /**
     * 开关门记录上报。
     *
     * 路径与《设备接入方案》第 5.2.3 节对齐。
     */
    @POST("/api/device/sampling-fridge/door/record")
    suspend fun uploadDoorRecord(
        @Body request: DoorRecordData,
    ): DeviceApiResponse<DoorRecordResponseData>
}
