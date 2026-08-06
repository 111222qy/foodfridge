package com.foodfridge.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModbusSettingsLogicTest {
    @Test
    fun `register address accepts decimal and hex`() {
        assertEquals(16, parseFlexibleInt("16"))
        assertEquals(16, parseFlexibleInt("0x10"))
        assertEquals(255, parseFlexibleInt("0Xff"))
    }

    @Test
    fun `valid default modbus configuration passes validation`() {
        assertNull(
            validateModbusConfiguration(
                devicePath = "/dev/ttyS2",
                baudRate = 115200,
                slaveAddress = 255,
                functionCode = 3,
                registerAddress = 0,
                registerCount = 2,
                temperatureRegisterOffset = 1,
                temperatureScale = 0.1f,
                calibrationOffset = 0f,
                requireDevicePath = true,
            )
        )
    }

    @Test
    fun `temperature register must be inside requested range`() {
        val error = validateModbusConfiguration(
            devicePath = "/dev/ttyS2",
            baudRate = 115200,
            slaveAddress = 255,
            functionCode = 3,
            registerAddress = 0,
            registerCount = 1,
            temperatureRegisterOffset = 1,
            temperatureScale = 0.1f,
            calibrationOffset = 0f,
            requireDevicePath = true,
        )

        assertNotNull(error)
    }

    @Test
    fun `register request must not cross maximum address`() {
        val error = validateModbusConfiguration(
            devicePath = "/dev/ttyS2",
            baudRate = 9600,
            slaveAddress = 1,
            functionCode = 4,
            registerAddress = 0xFFFF,
            registerCount = 2,
            temperatureRegisterOffset = 0,
            temperatureScale = 0.1f,
            calibrationOffset = 0f,
            requireDevicePath = true,
        )

        assertTrue(error?.contains("0xFFFF") == true)
    }

    @Test
    fun `parity must be a supported serial mode`() {
        val error = validateModbusConfiguration(
            devicePath = "/dev/ttyS2",
            baudRate = 9600,
            parity = 3,
            stopBits = 1,
            slaveAddress = 1,
            functionCode = 3,
            registerAddress = 0,
            registerCount = 2,
            temperatureRegisterOffset = 0,
            temperatureScale = 0.1f,
            calibrationOffset = 0f,
            requireDevicePath = true,
        )

        assertNotNull(error)
    }

    @Test
    fun `stop bits must be one or two`() {
        val error = validateModbusConfiguration(
            devicePath = "/dev/ttyS2",
            baudRate = 9600,
            parity = 2,
            stopBits = 3,
            slaveAddress = 1,
            functionCode = 3,
            registerAddress = 0,
            registerCount = 2,
            temperatureRegisterOffset = 0,
            temperatureScale = 0.1f,
            calibrationOffset = 0f,
            requireDevicePath = true,
        )

        assertNotNull(error)
    }

}
