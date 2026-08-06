package com.foodfridge.data.hardware

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemperatureMonitorLogicTest {
    @Test
    fun `fresh timestamp is accepted`() {
        assertTrue(
            isTemperatureTimestampAcceptable(
                recordedAt = 100_000L,
                now = 110_000L,
                staleAfterMs = 15_000L,
            )
        )
    }

    @Test
    fun `stale and far future timestamps are rejected`() {
        assertFalse(isTemperatureTimestampAcceptable(90_000L, 110_000L, 15_000L))
        assertFalse(isTemperatureTimestampAcceptable(116_000L, 110_000L, 15_000L))
    }

    @Test
    fun `alarm uses hysteresis before clearing`() {
        assertTrue(alarm(8.1f, wasActive = false))
        assertTrue(alarm(7.8f, wasActive = true))
        assertFalse(alarm(7.7f, wasActive = true))
    }

    @Test
    fun `invalid temperature never alarms`() {
        assertFalse(alarm(Float.NaN, wasActive = false))
        assertFalse(alarm(Float.POSITIVE_INFINITY, wasActive = true))
    }

    @Test
    fun `dismissing active alarm hides dialog for current episode`() {
        val activeEpisode = TemperatureAlarmEpisodeState().update(alarmActive = true)
        assertTrue(activeEpisode.shouldShowDialog)

        val acknowledgedEpisode = activeEpisode.acknowledge()
        assertTrue(acknowledgedEpisode.isActive)
        assertTrue(acknowledgedEpisode.isAcknowledged)
        assertFalse(acknowledgedEpisode.shouldShowDialog)
        assertFalse(acknowledgedEpisode.update(alarmActive = true).shouldShowDialog)
    }

    @Test
    fun `new alarm episode notifies after temperature recovers`() {
        val acknowledgedEpisode = TemperatureAlarmEpisodeState()
            .update(alarmActive = true)
            .acknowledge()

        val recoveredEpisode = acknowledgedEpisode.update(alarmActive = false)
        assertFalse(recoveredEpisode.isActive)
        assertFalse(recoveredEpisode.isAcknowledged)

        val nextEpisode = recoveredEpisode.update(alarmActive = true)
        assertTrue(nextEpisode.shouldShowDialog)
    }

    @Test
    fun `periodic scheduling uses monotonic time and recovers from reset`() {
        assertTrue(isPeriodicActionDue(lastElapsedAt = 0L, nowElapsed = 10L, intervalMs = 60L))
        assertFalse(isPeriodicActionDue(lastElapsedAt = 100L, nowElapsed = 150L, intervalMs = 60L))
        assertTrue(isPeriodicActionDue(lastElapsedAt = 100L, nowElapsed = 160L, intervalMs = 60L))
        assertTrue(isPeriodicActionDue(lastElapsedAt = 100L, nowElapsed = 20L, intervalMs = 60L))
    }

    private fun alarm(temperature: Float, wasActive: Boolean): Boolean {
        return temperatureAlarmState(
            temperature = temperature,
            wasAlarmActive = wasActive,
            enabled = true,
            low = 0f,
            high = 8f,
            hysteresis = 0.3f,
        )
    }
}
