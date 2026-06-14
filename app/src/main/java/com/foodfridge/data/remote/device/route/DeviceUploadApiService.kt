package com.foodfridge.data.remote.device.route

import com.foodfridge.data.remote.device.dto.DeviceApiRequest
import com.foodfridge.data.remote.device.dto.DeviceApiResponse
import com.foodfridge.data.remote.device.dto.DeviceRefreshData
import com.foodfridge.data.remote.device.dto.DeviceRefreshResponseData
import com.foodfridge.data.remote.device.dto.SamplingUploadData
import com.foodfridge.data.remote.device.dto.SamplingUploadResponseData
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 设备接入平台接口路由。
 *
 * 与旧版 [com.foodfridge.data.remote.ApiService] 完全独立，用于 `/api/device/...` 设备主动上报。
 */
interface DeviceUploadApiService {

    /**
     * 心跳保活。
     */
    @POST("/api/device/refresh")
    suspend fun refresh(
        @Body request: DeviceApiRequest<DeviceRefreshData>,
    ): DeviceApiResponse<DeviceRefreshResponseData>

    /**
     * 留样上报。
     */
    @POST("/api/device/sampling/upload")
    suspend fun uploadSampling(
        @Body request: DeviceApiRequest<SamplingUploadData>,
    ): DeviceApiResponse<SamplingUploadResponseData>
}
