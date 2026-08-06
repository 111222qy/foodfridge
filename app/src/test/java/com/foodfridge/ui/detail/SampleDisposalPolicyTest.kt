package com.foodfridge.ui.detail

import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDisposalPolicyTest {
    private val storeTime = 1_000_000L

    private fun sample(
        id: Int = 1,
        status: SampleStatus = SampleStatus.STORING,
    ) = FoodSample(
        id = id,
        operatorId = 1,
        operatorName = "留样员",
        foodName = "测试菜品",
        weightGrams = 100f,
        mealType = MealType.LUNCH,
        barcode = "sample-$id",
        status = status,
        storeTime = storeTime,
        expireTime = storeTime + SampleDisposalPolicy.RETENTION_DURATION_MS,
        createdAt = storeTime,
    )

    @Test
    fun `storing sample before 48 hours requires admin`() {
        val now = storeTime + SampleDisposalPolicy.RETENTION_DURATION_MS - 1

        assertTrue(SampleDisposalPolicy.requiresAdmin(listOf(sample()), now))
    }

    @Test
    fun `storing sample at 48 hours can be disposed normally`() {
        val now = storeTime + SampleDisposalPolicy.RETENTION_DURATION_MS

        assertFalse(SampleDisposalPolicy.requiresAdmin(listOf(sample()), now))
    }

    @Test
    fun `waiting dispose sample never requires admin`() {
        val now = storeTime + 1

        assertFalse(
            SampleDisposalPolicy.requiresAdmin(
                listOf(sample(status = SampleStatus.WAITING_DISPOSE)),
                now,
            )
        )
    }

    @Test
    fun `mixed selection requires admin when any sample is early`() {
        val now = storeTime + SampleDisposalPolicy.RETENTION_DURATION_MS - 1
        val samples = listOf(
            sample(id = 1, status = SampleStatus.WAITING_DISPOSE),
            sample(id = 2, status = SampleStatus.STORING),
        )

        assertTrue(SampleDisposalPolicy.requiresAdmin(samples, now))
    }
}
