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
import com.foodfridge.domain.repository.PendingUploadType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceUploadResponseLogicTest {
    @Test
    fun `only 2xx device response codes are successful`() {
        assertTrue(isSuccessfulDeviceResponseCode(200))
        assertTrue(isSuccessfulDeviceResponseCode(204))
        assertFalse(isSuccessfulDeviceResponseCode(400))
        assertFalse(isSuccessfulDeviceResponseCode(500))
    }

    @Test
    fun `business failure is queued for retry`() = runBlocking {
        val dao = RecordingPendingUploadDao()
        val repository = DeviceUploadRepositoryImpl(
            deviceUploadApiService = TemperatureApi {
                DeviceApiResponse(code = 500, message = "rejected", data = null)
            },
            pendingUploadDao = dao,
        )

        val result = repository.uploadTemperature(sampleTemperature())

        assertTrue(result.isFailure)
        assertEquals(1, dao.items.size)
        assertEquals(PendingUploadType.TEMPERATURE.name, dao.items.single().type)
    }

    @Test
    fun `cancellation propagates without creating pending upload`() {
        val dao = RecordingPendingUploadDao()
        val repository = DeviceUploadRepositoryImpl(
            deviceUploadApiService = TemperatureApi { throw CancellationException("cancelled") },
            pendingUploadDao = dao,
        )

        try {
            runBlocking { repository.uploadTemperature(sampleTemperature()) }
            fail("CancellationException expected")
        } catch (_: CancellationException) {
            assertTrue(dao.items.isEmpty())
        }
    }

    @Test
    fun `malformed pending payload does not block later valid upload`() = runBlocking {
        val validPayload = """{"device_id":"F28V2","timestamp":123,"temperature":5.5}"""
        val dao = RecordingPendingUploadDao(
            mutableListOf(
                PendingUploadEntity(id = 1, type = PendingUploadType.TEMPERATURE.name, payloadJson = "{"),
                PendingUploadEntity(id = 2, type = PendingUploadType.TEMPERATURE.name, payloadJson = validPayload),
            )
        )
        val repository = DeviceUploadRepositoryImpl(
            deviceUploadApiService = TemperatureApi {
                DeviceApiResponse(code = 200, message = "success", data = null)
            },
            pendingUploadDao = dao,
        )

        assertEquals(1, repository.flushPendingUploads())
        assertEquals(listOf(1), dao.items.map { it.id })
        assertEquals(listOf(1), dao.retriedIds)
    }

    private fun sampleTemperature() = TemperatureUploadData(
        device_id = "F28V2",
        timestamp = 123L,
        temperature = 5.5f,
    )

    private class TemperatureApi(
        private val upload: suspend (TemperatureUploadData) ->
            DeviceApiResponse<TemperatureUploadResponseData>,
    ) : DeviceUploadApiService {
        override suspend fun uploadTemperature(
            request: TemperatureUploadData,
        ): DeviceApiResponse<TemperatureUploadResponseData> = upload(request)

        override suspend fun refresh(
            request: DeviceRefreshData,
        ): DeviceApiResponse<DeviceRefreshResponseData> = error("unused")

        override suspend fun uploadSampling(
            request: SamplingUploadData,
        ): DeviceApiResponse<SamplingUploadResponseData> = error("unused")

        override suspend fun uploadDoorRecord(
            request: DoorRecordData,
        ): DeviceApiResponse<DoorRecordResponseData> = error("unused")
    }

    private class RecordingPendingUploadDao(
        val items: MutableList<PendingUploadEntity> = mutableListOf(),
    ) : PendingUploadDao {
        val retriedIds = mutableListOf<Int>()

        override suspend fun getPendingByType(type: String): List<PendingUploadEntity> =
            items.filter { it.type == type }.sortedBy { it.createdAt }

        override suspend fun getAllPending(): List<PendingUploadEntity> =
            items.sortedBy { it.createdAt }

        override suspend fun getPendingBatch(limit: Int): List<PendingUploadEntity> =
            items.sortedBy { it.createdAt }.take(limit)

        override suspend fun insert(entity: PendingUploadEntity): Long {
            val id = entity.id.takeIf { it != 0 } ?: ((items.maxOfOrNull { it.id } ?: 0) + 1)
            items += entity.copy(id = id)
            return id.toLong()
        }

        override suspend fun deleteById(id: Int) {
            items.removeAll { it.id == id }
        }

        override suspend fun incrementRetry(id: Int, lastError: String?) {
            retriedIds += id
            val index = items.indexOfFirst { it.id == id }
            if (index >= 0) {
                val item = items[index]
                items[index] = item.copy(retryCount = item.retryCount + 1, lastError = lastError)
            }
        }

        override suspend fun prune(maxRetries: Int, olderThan: Long) {
            items.removeAll { it.retryCount >= maxRetries || it.createdAt < olderThan }
        }
    }
}
