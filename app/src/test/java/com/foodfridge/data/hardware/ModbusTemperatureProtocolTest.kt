package com.foodfridge.data.hardware

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusTemperatureProtocolTest {
    private fun response(vararg payload: Int): ByteArray {
        val data = payload.map { it.toByte() }.toByteArray()
        val crc = modbusCrc16(data, 0, data.size)
        return data + byteArrayOf(
            (crc and 0xFF).toByte(),
            ((crc shr 8) and 0xFF).toByte(),
        )
    }

    @Test
    fun `default request is standard read holding registers frame`() {
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0x03, 0x00, 0x00, 0x00, 0x02,
                0xD1.toByte(), 0xD5.toByte(),
            ),
            buildModbusReadHoldingRegistersRequest(0xFF, 0, 2),
        )
    }

    @Test
    fun `request uses configured register address and count`() {
        val frame = buildModbusReadHoldingRegistersRequest(0xFF, 0x0010, 1)

        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0x03, 0x00, 0x10, 0x00, 0x01),
            frame.copyOfRange(0, 6),
        )
    }

    @Test
    fun `input register function builds function 04 request`() {
        val frame = buildModbusReadRegistersRequest(0x11, 0x04, 0x0020, 3)

        assertArrayEquals(
            byteArrayOf(0x11, 0x04, 0x00, 0x20, 0x00, 0x03),
            frame.copyOfRange(0, 6),
        )
    }

    @Test
    fun `response parses positive temperature from configured register`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)

        assertEquals(6.5f, parse(frame) ?: Float.NaN, 0.001f)
    }

    @Test
    fun `response parses signed negative temperature`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0xFF, 0xE7)

        assertEquals(-2.5f, parse(frame) ?: Float.NaN, 0.001f)
    }

    @Test
    fun `response applies calibration offset`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)

        assertEquals(
            7.0f,
            parseModbusTemperatureResponse(
                data = frame,
                slaveAddress = 0xFF,
                registerCount = 2,
                temperatureRegisterOffset = 1,
                temperatureScale = 0.1f,
                calibrationOffset = 0.5f,
            ) ?: Float.NaN,
            0.001f,
        )
    }

    @Test
    fun `response with invalid CRC is rejected`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)
        frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()

        assertNull(parse(frame))
    }

    @Test
    fun `response with unexpected byte count is rejected`() {
        val frame = response(0xFF, 0x03, 0x02, 0x00, 0x41)

        assertNull(parse(frame))
    }

    @Test
    fun `implausible temperature is rejected`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x03, 0xE8)

        assertNull(parse(frame))
    }

    @Test
    fun `valid response is extracted after leading noise`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)
        val stream = byteArrayOf(0x12, 0x34, 0x56) + frame

        assertArrayEquals(frame, extractModbusResponseFrame(stream, 0xFF))
    }

    @Test
    fun `valid response is extracted after echoed request`() {
        val request = buildModbusReadHoldingRegistersRequest(0xFF, 0, 2)
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)

        assertArrayEquals(frame, extractModbusResponseFrame(request + frame, 0xFF))
    }

    @Test
    fun `invalid crc frame is not extracted`() {
        val frame = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)
        frame[frame.lastIndex] = (frame.last().toInt() xor 0x01).toByte()

        assertNull(extractModbusResponseFrame(frame, 0xFF))
    }

    @Test
    fun `reconnect delay backs off and is capped`() {
        assertEquals(5_000L, reconnectDelayMs(1, 5_000L, 60_000L))
        assertEquals(10_000L, reconnectDelayMs(2, 5_000L, 60_000L))
        assertEquals(40_000L, reconnectDelayMs(4, 5_000L, 60_000L))
        assertEquals(60_000L, reconnectDelayMs(20, 5_000L, 60_000L))
    }

    @Test
    fun `large runtime jump requires a matching quick confirmation`() {
        assertTrue(requiresTemperatureJumpConfirmation(5f, 20f, 10f))
        assertFalse(requiresTemperatureJumpConfirmation(5f, 7f, 10f))
        assertTrue(isTemperatureConfirmationMatch(20f, 20.8f, 2f))
        assertFalse(isTemperatureConfirmationMatch(20f, 23f, 2f))
    }

    @Test
    fun `probe reports modbus exception instead of no response`() {
        val exception = response(0xFF, 0x83, 0x02)
        val config = ModbusTemperatureConfig(devicePath = "/dev/test")
        val result = analyzeModbusProbeResponse(
            config = config,
            requestFrame = buildModbusReadHoldingRegistersRequest(0xFF, 0, 2),
            rawResponse = exception,
        )

        assertEquals(ModbusProbeStatus.MODBUS_EXCEPTION, result.status)
        assertEquals(0x02, result.exceptionCode)
        assertEquals(0xFF, result.responseAddress)
        assertEquals(0x83, result.responseFunctionCode)
        assertTrue(result.hasValidFrame)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `probe distinguishes no bytes crc failure and address mismatch`() {
        val config = ModbusTemperatureConfig(devicePath = "/dev/test")
        val request = buildModbusReadHoldingRegistersRequest(0xFF, 0, 2)
        val validOtherAddress = response(0x01, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)
        val badCrc = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }

        assertEquals(
            ModbusProbeStatus.NO_BYTES,
            analyzeModbusProbeResponse(config, request, byteArrayOf()).status,
        )
        assertEquals(
            ModbusProbeStatus.INVALID_FRAME,
            analyzeModbusProbeResponse(config, request, badCrc).status,
        )
        assertEquals(
            ModbusProbeStatus.ADDRESS_MISMATCH,
            analyzeModbusProbeResponse(config, request, validOtherAddress).status,
        )
    }

    @Test
    fun `numeric decoder supports signed and unsigned integer types`() {
        assertEquals(
            -25.0,
            decode(byteArrayOf(0xFF.toByte(), 0xE7.toByte()), ModbusValueType.INT16),
            0.0,
        )
        assertEquals(
            65_511.0,
            decode(byteArrayOf(0xFF.toByte(), 0xE7.toByte()), ModbusValueType.UINT16),
            0.0,
        )
        assertEquals(
            -123_456.0,
            decode(
                byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x1D, 0xC0.toByte()),
                ModbusValueType.INT32,
            ),
            0.0,
        )
        assertEquals(
            4_294_967_295.0,
            decode(
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
                ModbusValueType.UINT32,
            ),
            0.0,
        )
    }

    @Test
    fun `float decoder supports all byte and word orders`() {
        val cases = listOf(
            Triple(
                byteArrayOf(0x41, 0xCC.toByte(), 0x00, 0x00),
                ModbusByteOrder.BIG_ENDIAN,
                ModbusWordOrder.HIGH_WORD_FIRST,
            ),
            Triple(
                byteArrayOf(0xCC.toByte(), 0x41, 0x00, 0x00),
                ModbusByteOrder.LITTLE_ENDIAN,
                ModbusWordOrder.HIGH_WORD_FIRST,
            ),
            Triple(
                byteArrayOf(0x00, 0x00, 0x41, 0xCC.toByte()),
                ModbusByteOrder.BIG_ENDIAN,
                ModbusWordOrder.LOW_WORD_FIRST,
            ),
            Triple(
                byteArrayOf(0x00, 0x00, 0xCC.toByte(), 0x41),
                ModbusByteOrder.LITTLE_ENDIAN,
                ModbusWordOrder.LOW_WORD_FIRST,
            ),
        )

        cases.forEach { (bytes, byteOrder, wordOrder) ->
            assertEquals(
                25.5,
                decode(bytes, ModbusValueType.FLOAT32, byteOrder, wordOrder),
                0.0,
            )
        }
    }

    @Test
    fun `numeric decoder honors register offset and rejects short payload`() {
        val bytes = byteArrayOf(0x12, 0x34, 0x41, 0xCC.toByte(), 0x00, 0x00)

        assertEquals(
            25.5,
            decodeModbusNumericValue(
                registerBytes = bytes,
                registerOffset = 1,
                valueType = ModbusValueType.FLOAT32,
                byteOrder = ModbusByteOrder.BIG_ENDIAN,
                wordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
            ) ?: Double.NaN,
            0.0,
        )
        assertNull(
            decodeModbusNumericValue(
                registerBytes = bytes,
                registerOffset = 2,
                valueType = ModbusValueType.FLOAT32,
                byteOrder = ModbusByteOrder.BIG_ENDIAN,
                wordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
            )
        )
    }

    @Test
    fun `response parses direct float and unsigned values`() {
        val floatFrame = response(0xFF, 0x03, 0x04, 0x41, 0xCC, 0x00, 0x00)
        val unsignedFrame = response(0xFF, 0x03, 0x02, 0xFF, 0xFF)

        assertEquals(
            25.5f,
            parseModbusTemperatureResponse(
                data = floatFrame,
                slaveAddress = 0xFF,
                registerCount = 2,
                temperatureRegisterOffset = 0,
                temperatureScale = 1f,
                valueType = ModbusValueType.FLOAT32,
            ) ?: Float.NaN,
            0.001f,
        )
        assertEquals(
            65.535f,
            parseModbusTemperatureResponse(
                data = unsignedFrame,
                slaveAddress = 0xFF,
                registerCount = 1,
                temperatureRegisterOffset = 0,
                temperatureScale = 0.001f,
                valueType = ModbusValueType.UINT16,
            ) ?: Float.NaN,
            0.001f,
        )
    }

    @Test
    fun `response parses signed and unsigned 32 bit temperatures`() {
        val signedFrame = response(0xFF, 0x03, 0x04, 0xFF, 0xFF, 0xFF, 0x06)
        val unsignedFrame = response(0xFF, 0x03, 0x04, 0x00, 0x00, 0x09, 0xC4)

        assertEquals(
            -25f,
            parseModbusTemperatureResponse(
                data = signedFrame,
                slaveAddress = 0xFF,
                registerCount = 2,
                temperatureRegisterOffset = 0,
                temperatureScale = 0.1f,
                valueType = ModbusValueType.INT32,
            ) ?: Float.NaN,
            0.001f,
        )
        assertEquals(
            25f,
            parseModbusTemperatureResponse(
                data = unsignedFrame,
                slaveAddress = 0xFF,
                registerCount = 2,
                temperatureRegisterOffset = 0,
                temperatureScale = 0.01f,
                valueType = ModbusValueType.UINT32,
            ) ?: Float.NaN,
            0.001f,
        )
    }

    @Test
    fun `response converts PT100 and PT1000 resistance before calibration`() {
        val pt100Frame = response(0xFF, 0x03, 0x02, 0x03, 0xE8)
        val pt1000Frame = response(0xFF, 0x03, 0x02, 0x27, 0x10)

        assertEquals(
            0.5f,
            parseModbusTemperatureResponse(
                data = pt100Frame,
                slaveAddress = 0xFF,
                registerCount = 1,
                temperatureRegisterOffset = 0,
                temperatureScale = 0.1f,
                calibrationOffset = 0.5f,
                valueMode = ModbusTemperatureValueMode.PT100_RESISTANCE,
            ) ?: Float.NaN,
            0.001f,
        )
        assertEquals(
            0f,
            parseModbusTemperatureResponse(
                data = pt1000Frame,
                slaveAddress = 0xFF,
                registerCount = 1,
                temperatureRegisterOffset = 0,
                temperatureScale = 0.1f,
                valueMode = ModbusTemperatureValueMode.PT1000_RESISTANCE,
            ) ?: Float.NaN,
            0.001f,
        )
    }

    @Test
    fun `non finite float payloads are rejected`() {
        val nanFrame = response(0xFF, 0x03, 0x04, 0x7F, 0xC0, 0x00, 0x00)
        val infinityFrame = response(0xFF, 0x03, 0x04, 0x7F, 0x80, 0x00, 0x00)

        listOf(nanFrame, infinityFrame).forEach { frame ->
            assertNull(
                parseModbusTemperatureResponse(
                    data = frame,
                    slaveAddress = 0xFF,
                    registerCount = 2,
                    temperatureRegisterOffset = 0,
                    temperatureScale = 1f,
                    valueType = ModbusValueType.FLOAT32,
                )
            )
        }
    }

    @Test
    fun `configuration validates register range and value width`() {
        assertThrows(IllegalArgumentException::class.java) {
            ModbusTemperatureConfig(
                devicePath = "/dev/test",
                registerAddress = 0xFFFF,
                registerCount = 2,
                temperatureRegisterOffset = 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModbusTemperatureConfig(
                devicePath = "/dev/test",
                registerCount = 2,
                temperatureRegisterOffset = 1,
                valueType = ModbusValueType.INT32,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildModbusReadRegistersRequest(0x01, 0x03, 0xFFFF, 2)
        }
        ModbusTemperatureConfig(
            devicePath = "/dev/test",
            registerCount = 3,
            temperatureRegisterOffset = 1,
            valueType = ModbusValueType.INT32,
        )
    }

    @Test
    fun `probe prefers target frame and exposes structured frame data`() {
        val config = ModbusTemperatureConfig(devicePath = "/dev/test")
        val request = buildModbusReadHoldingRegistersRequest(0xFF, 0, 2)
        val other = response(0x01, 0x03, 0x04, 0x00, 0x50, 0x00, 0x40)
        val target = response(0xFF, 0x03, 0x04, 0x00, 0x50, 0x00, 0x41)

        val result = analyzeModbusProbeResponse(config, request, other + target)

        assertEquals(ModbusProbeStatus.SUCCESS, result.status)
        assertEquals(0xFF, result.responseAddress)
        assertEquals(0x03, result.responseFunctionCode)
        assertEquals(target.toHexString(), result.responseFrameHex)
        assertTrue(result.hasValidFrame)
        assertTrue(result.hasRegisterData)
    }

    private fun parse(frame: ByteArray): Float? {
        return parseModbusTemperatureResponse(
            data = frame,
            slaveAddress = 0xFF,
            registerCount = 2,
            temperatureRegisterOffset = 1,
            temperatureScale = 0.1f,
        )
    }

    private fun decode(
        bytes: ByteArray,
        valueType: ModbusValueType,
        byteOrder: ModbusByteOrder = ModbusByteOrder.BIG_ENDIAN,
        wordOrder: ModbusWordOrder = ModbusWordOrder.HIGH_WORD_FIRST,
    ): Double {
        return decodeModbusNumericValue(
            registerBytes = bytes,
            registerOffset = 0,
            valueType = valueType,
            byteOrder = byteOrder,
            wordOrder = wordOrder,
        ) ?: Double.NaN
    }
}
