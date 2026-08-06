package com.foodfridge.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class DateRollingLogicTest {

    /** 构造某一天的 00:00 时间戳 */
    private fun dayStartOf(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)  // Calendar.MONTH 0-based
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    @Test
    fun `firstColumnDate returns today when no active samples`() {
        val today = dayStartOf(2026, 7, 20)
        val todayAfternoon = today + 15 * 60 * 60 * 1000  // 今天下午3点
        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = emptyList(),
            todayMillis = todayAfternoon,
        )
        assertEquals(today, result)
    }

    @Test
    fun `firstColumnDate returns earliest active sample date`() {
        val today = dayStartOf(2026, 7, 20)
        val day1 = dayStartOf(2026, 7, 17) + 10 * 60 * 60 * 1000  // 7月17日 10:00
        val day2 = dayStartOf(2026, 7, 18) + 12 * 60 * 60 * 1000  // 7月18日 12:00
        val day3 = dayStartOf(2026, 7, 19) + 14 * 60 * 60 * 1000  // 7月19日 14:00

        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = listOf(day2, day3, day1),  // 乱序
            todayMillis = today,
        )
        assertEquals(dayStartOf(2026, 7, 17), result)
    }

    @Test
    fun `firstColumnDate normalizes to day start`() {
        val today = dayStartOf(2026, 7, 20)
        // 活跃样品是 7月19日 23:59:59
        val lateNight = dayStartOf(2026, 7, 19) + 23 * 60 * 60 * 1000 + 59 * 60 * 1000 + 59 * 1000

        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = listOf(lateNight),
            todayMillis = today,
        )
        assertEquals(dayStartOf(2026, 7, 19), result)
    }

    @Test
    fun `firstColumnDate returns today when earliest active is today`() {
        val today = dayStartOf(2026, 7, 20)
        val todayMorning = today + 8 * 60 * 60 * 1000  // 今早 8 点

        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = listOf(todayMorning),
            todayMillis = today + 15 * 60 * 60 * 1000,
        )
        assertEquals(today, result)
    }

    @Test
    fun `columnDate adds day offset correctly`() {
        val firstDay = dayStartOf(2026, 7, 18)
        assertEquals(dayStartOf(2026, 7, 18), DateRollingLogic.columnDate(firstDay, 0))
        assertEquals(dayStartOf(2026, 7, 19), DateRollingLogic.columnDate(firstDay, 1))
        assertEquals(dayStartOf(2026, 7, 20), DateRollingLogic.columnDate(firstDay, 2))
    }

    @Test
    fun `dayStart normalizes to midnight`() {
        val afternoon = dayStartOf(2026, 7, 20) + 14 * 60 * 60 * 1000 + 30 * 60 * 1000
        assertEquals(dayStartOf(2026, 7, 20), DateRollingLogic.dayStart(afternoon))
    }

    @Test
    fun `firstColumnDate advances when earliest active is disposed`() {
        // 模拟场景：所有 7月17日 样品消样后，最早活跃变为 7月18日
        val today = dayStartOf(2026, 7, 20)
        val day18 = dayStartOf(2026, 7, 18) + 10 * 60 * 60 * 1000
        val day19 = dayStartOf(2026, 7, 19) + 10 * 60 * 60 * 1000

        val result = DateRollingLogic.firstColumnDate(
            activeSampleStoreTimes = listOf(day18, day19),
            todayMillis = today,
        )
        assertEquals(dayStartOf(2026, 7, 18), result)
        assertTrue("日期应该向前滚动", result > dayStartOf(2026, 7, 17))
    }

    @Test
    fun `only column matching today allows storing`() {
        val firstColumn = dayStartOf(2026, 7, 23)
        val today = dayStartOf(2026, 7, 24) + 10 * 60 * 60 * 1000

        assertTrue(DateRollingLogic.isTodayColumn(firstColumn, 1, today))
        assertEquals(false, DateRollingLogic.isTodayColumn(firstColumn, 0, today))
        assertEquals(false, DateRollingLogic.isTodayColumn(firstColumn, 2, today))
    }
}
