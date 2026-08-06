package com.foodfridge.data.repository

import com.foodfridge.data.local.dao.PendingUploadDao
import com.foodfridge.data.local.entity.PendingUploadEntity
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
import com.foodfridge.domain.repository.PendingUploadType
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备接入平台上报仓库实现。
 *
 * 失败请求会序列化到本地 pending_uploads 表，并通过 [flushPendingUploads] 重试。
 */
@Singleton
class DeviceUploadRepositoryImpl @Inject constructor(
    private val deviceUploadApiService: DeviceUploadApiService,
    private val pendingUploadDao: PendingUploadDao,
) : DeviceUploadRepository {

    private val gson = Gson()

    companion object {
        private const val MAX_RETRY_COUNT = 7 * 24 * 12
        private const val PENDING_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000
        private const val PENDING_FLUSH_BATCH_SIZE = 100
    }

    private val flushMutex = Mutex()

    override suspend fun refresh(
        data: DeviceRefreshData,
    ): Result<DeviceApiResponse<DeviceRefreshResponseData>> {
        return safeCall { deviceUploadApiService.refresh(data) }
    }

    override suspend fun uploadSampling(
        data: SamplingUploadData,
    ): Result<DeviceApiResponse<SamplingUploadResponseData>> {
        return safeCall(
            type = PendingUploadType.SAMPLING.name,
            payload = gson.toJson(data),
        ) { deviceUploadApiService.uploadSampling(data) }
    }

    override suspend fun uploadTemperature(
        data: TemperatureUploadData,
    ): Result<DeviceApiResponse<TemperatureUploadResponseData>> {
        return safeCall(
            type = PendingUploadType.TEMPERATURE.name,
            payload = gson.toJson(data),
        ) { deviceUploadApiService.uploadTemperature(data) }
    }

    override suspend fun uploadDoorRecord(
        data: DoorRecordData,
    ): Result<DeviceApiResponse<DoorRecordResponseData>> {
        return safeCall(
            type = PendingUploadType.DOOR.name,
            payload = gson.toJson(data),
        ) { deviceUploadApiService.uploadDoorRecord(data) }
    }

    override suspend fun flushPendingUploads(): Int = flushMutex.withLock {
        var successCount = 0
        try {
            pendingUploadDao.prune(MAX_RETRY_COUNT, System.currentTimeMillis() - PENDING_TTL_MILLIS)
            val pending = pendingUploadDao.getPendingBatch(PENDING_FLUSH_BATCH_SIZE)
            Timber.d("Flushing ${pending.size} pending uploads")
            for (item in pending) {
                currentCoroutineContext().ensureActive()
                val result: Result<*> = try {
                    when (item.type) {
                        PendingUploadType.SAMPLING.name -> {
                            val data = gson.fromJson(item.payloadJson, SamplingUploadData::class.java)
                            safeCall { deviceUploadApiService.uploadSampling(data) }
                        }
                        PendingUploadType.TEMPERATURE.name -> {
                            val data = gson.fromJson(item.payloadJson, TemperatureUploadData::class.java)
                            safeCall { deviceUploadApiService.uploadTemperature(data) }
                        }
                        PendingUploadType.DOOR.name -> {
                            val data = gson.fromJson(item.payloadJson, DoorRecordData::class.java)
                            safeCall { deviceUploadApiService.uploadDoorRecord(data) }
                        }
                        else -> {
                            Timber.w("Unknown pending upload type: ${item.type}, deleting")
                            pendingUploadDao.deleteById(item.id)
                            continue
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure<Any>(e)
                }
                val error = result.exceptionOrNull()
                if (error == null) {
                    pendingUploadDao.deleteById(item.id)
                    successCount++
                    Timber.i("Pending upload flushed: type=${item.type}, id=${item.id}")
                } else {
                    pendingUploadDao.incrementRetry(item.id, error.message?.take(200))
                    Timber.w(error, "Pending upload retry failed: type=${item.type}, id=${item.id}, retry=${item.retryCount + 1}")
                    if (error is IOException) {
                        Timber.w("Stopping pending batch after network failure")
                        break
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "flushPendingUploads failed")
        }
        successCount
    }

    private suspend fun <T> safeCall(
        call: suspend () -> DeviceApiResponse<T>,
    ): Result<DeviceApiResponse<T>> {
        return try {
            Result.success(requireSuccessfulResponse(call))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T> safeCall(
        type: String,
        payload: String,
        call: suspend () -> DeviceApiResponse<T>,
    ): Result<DeviceApiResponse<T>> {
        return try {
            Result.success(requireSuccessfulResponse(call))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                pendingUploadDao.insert(
                    PendingUploadEntity(
                        type = type,
                        payloadJson = payload,
                    )
                )
                Timber.i("Queued pending upload: type=$type, error=${e.message}")
            } catch (dbError: Exception) {
                Timber.e(dbError, "Failed to queue pending upload")
            }
            Result.failure(e)
        }
    }

    private suspend fun <T> requireSuccessfulResponse(
        call: suspend () -> DeviceApiResponse<T>,
    ): DeviceApiResponse<T> {
        val response = call()
        if (!isSuccessfulDeviceResponseCode(response.code)) {
            throw DeviceApiBusinessException(response.code, response.message)
        }
        return response
    }
}

internal fun isSuccessfulDeviceResponseCode(code: Int): Boolean = code in 200..299

private class DeviceApiBusinessException(code: Int, message: String) :
    IllegalStateException("Device API rejected request: code=$code, message=$message")
