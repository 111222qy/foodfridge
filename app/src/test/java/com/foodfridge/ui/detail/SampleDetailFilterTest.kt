package com.foodfridge.ui.detail

import com.foodfridge.domain.model.FoodSample
import com.foodfridge.domain.model.MealType
import com.foodfridge.domain.model.SampleStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SampleDetailFilterTest {

    private fun sample(
        id: Int,
        foodName: String,
        operatorName: String,
        status: SampleStatus = SampleStatus.STORING,
    ) = FoodSample(
        id = id,
        operatorId = 1,
        operatorName = operatorName,
        foodName = foodName,
        weightGrams = 100f,
        mealType = MealType.LUNCH,
        barcode = "12345",
        status = status,
        storeTime = 0L,
        expireTime = 0L,
        createdAt = id.toLong(),
    )

    @Test
    fun `blank query returns all samples`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "张三"),
                sample(2, "红烧肉", "李四"),
            ),
            searchQuery = "",
        )
        assertEquals(2, state.filteredSamples.size)
    }

    @Test
    fun `filter by food name`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "张三"),
                sample(2, "红烧肉", "李四"),
                sample(3, "宫保虾球", "王五"),
            ),
            searchQuery = "宫保",
        )
        assertEquals(2, state.filteredSamples.size)
        assertEquals(listOf(1, 3), state.filteredSamples.map { it.id })
    }

    @Test
    fun `filter by operator name`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "张三"),
                sample(2, "红烧肉", "李四"),
            ),
            searchQuery = "李四",
        )
        assertEquals(1, state.filteredSamples.size)
        assertEquals(2, state.filteredSamples[0].id)
    }

    @Test
    fun `filter is case insensitive`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "GongBao", "zhangsan"),
            ),
            searchQuery = "gongbao",
        )
        assertEquals(1, state.filteredSamples.size)
    }

    @Test
    fun `no match returns empty`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "张三"),
            ),
            searchQuery = "不存在的菜品",
        )
        assertEquals(0, state.filteredSamples.size)
    }

    @Test
    fun `query with whitespace is trimmed`() {
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "张三"),
            ),
            searchQuery = "  宫保  ",
        )
        assertEquals(1, state.filteredSamples.size)
    }

    @Test
    fun `filter matches both food name and operator name`() {
        // 查询匹配 A 的菜名，匹配 B 的操作员 → 都应返回
        val state = SampleDetailUiState(
            samples = listOf(
                sample(1, "宫保鸡丁", "李四"),
                sample(2, "红烧肉", "宫保"),
                sample(3, "清蒸鱼", "王五"),
            ),
            searchQuery = "宫保",
        )
        assertEquals(2, state.filteredSamples.size)
        assertEquals(setOf(1, 2), state.filteredSamples.map { it.id }.toSet())
    }
}
