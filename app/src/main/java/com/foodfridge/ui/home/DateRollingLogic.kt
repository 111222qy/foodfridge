package com.foodfridge.ui.home

import java.util.Calendar

/**
 * 首页三列日期滚动逻辑。
 * 规则：第一列 = 最早活跃样品的日期；无活跃样品时为今天。
 * 第二列 = 第一列 + 1 天；第三列 = 第一列 + 2 天。
 */
object DateRollingLogic {

    /** 返回当天 00:00:00.000 的毫秒时间戳 */
    fun dayStart(timeMillis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = timeMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    /**
     * 计算第一列日期。
     * @param activeSampleStoreTimes 活跃样品（存样中/待消样）的留样时间列表
     * @param todayMillis 当前时间（毫秒）
     * @return 第一列应显示的日期（当天 00:00 时间戳）
     */
    fun firstColumnDate(activeSampleStoreTimes: List<Long>, todayMillis: Long): Long {
        if (activeSampleStoreTimes.isEmpty()) {
            return dayStart(todayMillis)
        }
        val earliest = activeSampleStoreTimes.minOrNull()!!
        return dayStart(earliest)
    }

    /** 给定第一列日期和列偏移，返回该列的日期。 */
    fun columnDate(firstColumnDate: Long, dayOffset: Int): Long {
        return firstColumnDate + dayOffset * 24L * 60 * 60 * 1000
    }

    fun isTodayColumn(firstColumnDate: Long, dayOffset: Int, now: Long): Boolean {
        return dayStart(columnDate(firstColumnDate, dayOffset)) == dayStart(now)
    }
}
