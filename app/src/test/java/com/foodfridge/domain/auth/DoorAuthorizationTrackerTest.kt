package com.foodfridge.domain.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoorAuthorizationTrackerTest {
    @Test
    fun `authorization expiry while door is open keeps operator until close`() {
        val tracker = DoorAuthorizationTracker()
        val operator = sampleOperator()
        tracker.authorize(operator)
        tracker.onDoorOpened(openedAt = 1_500L)

        tracker.invalidateFutureAccess()

        assertFalse(tracker.hasFutureAuthorization())
        assertTrue(tracker.hasActiveDoorCycle())
        assertEquals(
            operator,
            tracker.completeDoorCycle(openedAt = 1_500L, closedAt = 5_000L),
        )
        assertFalse(tracker.hasActiveDoorCycle())
    }

    @Test
    fun `expired authorization cannot start a new door cycle`() {
        val tracker = DoorAuthorizationTracker()
        tracker.authorize(sampleOperator())

        tracker.onDoorOpened(openedAt = 2_001L)

        assertFalse(tracker.hasActiveDoorCycle())
        assertNull(tracker.completeDoorCycle(openedAt = 2_001L, closedAt = 2_500L))
    }

    @Test
    fun `authorization without an open cycle is removed on expiry`() {
        val tracker = DoorAuthorizationTracker()
        tracker.authorize(sampleOperator())

        tracker.invalidateFutureAccess()

        assertFalse(tracker.hasFutureAuthorization())
        assertNull(tracker.completeDoorCycle(openedAt = 1_500L, closedAt = 1_600L))
    }

    private fun sampleOperator() = DoorOperatorSnapshot(
        operatorId = 7,
        operatorName = "测试留样员",
        authorizedAt = 1_000L,
        expiresAt = 2_000L,
    )
}
