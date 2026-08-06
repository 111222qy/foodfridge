package com.foodfridge.data.hardware

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientLightDecisionTest {
    @Test
    fun `two dark samples turn light on`() {
        val first = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_ON_THRESHOLD - 1,
            lightState = AmbientLightController.LightState.OFF,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = 0,
            canTurnOff = true,
        )
        assertEquals(AmbientLightCommand.NONE, first.command)

        val second = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_ON_THRESHOLD - 1,
            lightState = AmbientLightController.LightState.OFF,
            consecutiveDarkSamples = first.consecutiveDarkSamples,
            consecutiveBrightSamples = first.consecutiveBrightSamples,
            canTurnOff = true,
        )
        assertEquals(AmbientLightCommand.TURN_ON, second.command)
    }

    @Test
    fun `one dark sample does not react to transient shadow`() {
        val decision = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_ON_THRESHOLD - 1,
            lightState = AmbientLightController.LightState.OFF,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = 0,
            canTurnOff = true,
        )

        assertEquals(AmbientLightCommand.NONE, decision.command)
        assertEquals(1, decision.consecutiveDarkSamples)
    }

    @Test
    fun `hysteresis range resets both counters`() {
        val decision = decideAmbientLight(
            brightness = (AmbientLightController.LIGHT_ON_THRESHOLD +
                AmbientLightController.LIGHT_OFF_THRESHOLD) / 2,
            lightState = AmbientLightController.LightState.ON,
            consecutiveDarkSamples = 1,
            consecutiveBrightSamples = 8,
            canTurnOff = true,
        )

        assertEquals(AmbientLightCommand.NONE, decision.command)
        assertEquals(0, decision.consecutiveDarkSamples)
        assertEquals(0, decision.consecutiveBrightSamples)
    }

    @Test
    fun `bright samples cannot turn light off during minimum on time`() {
        val decision = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_OFF_THRESHOLD + 1,
            lightState = AmbientLightController.LightState.ON,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = AmbientLightController.BRIGHT_SAMPLES_REQUIRED,
            canTurnOff = false,
        )

        assertEquals(AmbientLightCommand.NONE, decision.command)
        assertEquals(0, decision.consecutiveBrightSamples)
    }

    @Test
    fun `sustained brightness turns light off after hold time`() {
        val decision = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_OFF_THRESHOLD + 1,
            lightState = AmbientLightController.LightState.ON,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = AmbientLightController.BRIGHT_SAMPLES_REQUIRED - 1,
            canTurnOff = true,
        )

        assertEquals(AmbientLightCommand.TURN_OFF, decision.command)
    }

    @Test
    fun `samples do not accumulate when light is already in target state`() {
        val darkWhileOn = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_ON_THRESHOLD - 1,
            lightState = AmbientLightController.LightState.ON,
            consecutiveDarkSamples = 100,
            consecutiveBrightSamples = 0,
            canTurnOff = true,
        )
        val brightWhileOff = decideAmbientLight(
            brightness = AmbientLightController.LIGHT_OFF_THRESHOLD + 1,
            lightState = AmbientLightController.LightState.OFF,
            consecutiveDarkSamples = 0,
            consecutiveBrightSamples = 100,
            canTurnOff = true,
        )

        assertEquals(0, darkWhileOn.consecutiveDarkSamples)
        assertEquals(0, brightWhileOff.consecutiveBrightSamples)
    }
}
