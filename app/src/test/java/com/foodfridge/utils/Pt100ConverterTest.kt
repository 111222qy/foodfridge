package com.foodfridge.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class Pt100ConverterTest {
    @Test
    fun `known PT100 and PT1000 resistances convert to temperature`() {
        assertEquals(
            0.0,
            Pt100Converter.resistanceToTemperature(100.0) ?: Double.NaN,
            0.0001,
        )
        assertEquals(
            100.0,
            Pt100Converter.resistanceToTemperature(138.5055) ?: Double.NaN,
            0.001,
        )
        assertEquals(
            100.0,
            Pt100Converter.resistanceToTemperature(
                1385.055,
                Pt100Converter.SensorType.PT1000,
            ) ?: Double.NaN,
            0.001,
        )
    }

    @Test
    fun `negative temperature round trips through IEC curve`() {
        val resistance = Pt100Converter.temperatureToResistance(-50.0)

        assertEquals(
            -50.0,
            Pt100Converter.resistanceToTemperature(resistance) ?: Double.NaN,
            0.001,
        )
    }

    @Test
    fun `non finite and out of range resistance is rejected`() {
        assertNull(Pt100Converter.resistanceToTemperature(Double.NaN))
        assertNull(Pt100Converter.resistanceToTemperature(Double.POSITIVE_INFINITY))
        assertNull(Pt100Converter.resistanceToTemperature(0.0))
        assertNull(Pt100Converter.resistanceToTemperature(10.0))
        assertNull(Pt100Converter.resistanceToTemperature(1_000.0))
        assertNull(Pt100Converter.convertRawValue(Double.MAX_VALUE, 2.0))
    }

    @Test
    fun `temperature to resistance requires finite IEC range`() {
        assertThrows(IllegalArgumentException::class.java) {
            Pt100Converter.temperatureToResistance(Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pt100Converter.temperatureToResistance(-201.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Pt100Converter.temperatureToResistance(851.0)
        }
    }
}
