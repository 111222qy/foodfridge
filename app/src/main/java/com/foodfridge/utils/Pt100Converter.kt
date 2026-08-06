package com.foodfridge.utils

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

object Pt100Converter {
    enum class SensorType {
        PT100,
        PT1000,
    }

    private const val A = 3.9083e-3
    private const val B = -5.775e-7
    private const val C = -4.183e-12
    private const val MIN_STANDARD_TEMPERATURE = -200.0
    private const val MAX_STANDARD_TEMPERATURE = 850.0
    private const val R0_PT100 = 100.0
    private const val R0_PT1000 = 1000.0

    fun resistanceToTemperature(
        resistance: Double,
        sensorType: SensorType = SensorType.PT100,
    ): Double? {
        if (!resistance.isFinite() || resistance <= 0.0) return null

        val r0 = referenceResistance(sensorType)
        val validResistanceRange =
            resistanceAtTemperature(MIN_STANDARD_TEMPERATURE, r0)..
                resistanceAtTemperature(MAX_STANDARD_TEMPERATURE, r0)
        if (resistance !in validResistanceRange) return null

        val temperatureEstimate = (resistance - r0) / (r0 * A)
        if (temperatureEstimate < 0.0) {
            return solveNegativeTemperature(resistance, r0)
        }

        val discriminant = A * A - 4.0 * B * (1.0 - resistance / r0)
        if (discriminant < 0.0) return null
        val first = (-A + sqrt(discriminant)) / (2.0 * B)
        val second = (-A - sqrt(discriminant)) / (2.0 * B)
        val result = when {
            first >= 0.0 && second >= 0.0 -> min(first, second)
            first >= 0.0 -> first
            second >= 0.0 -> second
            else -> return null
        }
        return result.takeIf { it.isFinite() && it in 0.0..MAX_STANDARD_TEMPERATURE }
    }

    fun temperatureToResistance(
        temperature: Double,
        sensorType: SensorType = SensorType.PT100,
    ): Double {
        require(temperature.isFinite()) { "temperature must be finite" }
        require(temperature in MIN_STANDARD_TEMPERATURE..MAX_STANDARD_TEMPERATURE) {
            "temperature must be within the IEC 60751 range"
        }
        return resistanceAtTemperature(temperature, referenceResistance(sensorType))
    }

    fun convertRawValue(
        rawValue: Double,
        scaleFactor: Double = 1.0,
        sensorType: SensorType = SensorType.PT100,
    ): Double? {
        if (!rawValue.isFinite() || !scaleFactor.isFinite()) return null
        val resistance = rawValue * scaleFactor
        if (!resistance.isFinite()) return null
        return resistanceToTemperature(resistance, sensorType)
    }

    private fun solveNegativeTemperature(resistance: Double, r0: Double): Double? {
        var temperature = -10.0
        val epsilon = 1e-6
        repeat(100) {
            val function = resistanceAtTemperature(temperature, r0) - resistance
            val derivative = r0 * (
                A + 2.0 * B * temperature +
                    3.0 * C * (temperature - 100.0) * temperature * temperature +
                    C * temperature * temperature * temperature
                )
            if (!function.isFinite() || !derivative.isFinite() || abs(derivative) < epsilon) {
                return null
            }

            val next = temperature - function / derivative
            if (!next.isFinite()) return null
            if (abs(next - temperature) < epsilon) {
                return next.takeIf { it in MIN_STANDARD_TEMPERATURE..0.0 }
            }
            temperature = next
        }
        return temperature.takeIf { it in MIN_STANDARD_TEMPERATURE..0.0 }
    }

    private fun resistanceAtTemperature(temperature: Double, r0: Double): Double {
        return if (temperature >= 0.0) {
            r0 * (1.0 + A * temperature + B * temperature * temperature)
        } else {
            r0 * (
                1.0 + A * temperature + B * temperature * temperature +
                    C * (temperature - 100.0) * temperature * temperature * temperature
                )
        }
    }

    private fun referenceResistance(sensorType: SensorType): Double = when (sensorType) {
        SensorType.PT100 -> R0_PT100
        SensorType.PT1000 -> R0_PT1000
    }
}
