package com.foodfridge.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SerialPortLeaseRegistryTest {
    @Test
    fun `same serial path can only have one owner`() {
        val first = SerialPortLeaseRegistry.tryAcquire("/dev/test-lease", "temperature")
        assertNotNull(first)
        assertEquals("temperature", SerialPortLeaseRegistry.currentOwner("/dev/test-lease"))
        assertNull(SerialPortLeaseRegistry.tryAcquire("/dev/test-lease", "scanner"))

        first?.close()
        val second = SerialPortLeaseRegistry.tryAcquire("/dev/test-lease", "scanner")
        assertNotNull(second)
        second?.close()
        assertNull(SerialPortLeaseRegistry.currentOwner("/dev/test-lease"))
    }
}
