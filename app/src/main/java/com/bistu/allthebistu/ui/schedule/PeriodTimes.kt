package com.bistu.allthebistu.ui.schedule

/**
 * 节次时间表。
 *
 * 数据来源：北信科教务系统课表页面的实际时间。
 * 不同学校/校区可能不同，这里按主校区的标准时间配置。
 *
 * 注意：第 1-5 节是上午，第 6-9 节是下午，第 10-17 节是晚上。
 * 中午 12:15-13:30 有午休，所以第 6 节从 13:30 开始。
 */
data class PeriodTime(
    val period: Int,
    val startTime: String,
    val endTime: String
)

object PeriodTimes {

    val ALL: List<PeriodTime> = listOf(
        PeriodTime(1, "08:00", "08:45"),
        PeriodTime(2, "08:50", "09:35"),
        PeriodTime(3, "09:50", "10:35"),
        PeriodTime(4, "10:40", "11:25"),
        PeriodTime(5, "11:30", "12:15"),
        PeriodTime(6, "13:30", "14:15"),
        PeriodTime(7, "14:20", "15:05"),
        PeriodTime(8, "15:20", "16:05"),
        PeriodTime(9, "16:10", "16:55"),
        PeriodTime(10, "17:00", "17:45"),
        PeriodTime(11, "18:30", "19:15"),
        PeriodTime(12, "19:20", "20:05"),
        PeriodTime(13, "20:10", "20:55"),
        PeriodTime(14, "21:00", "21:45"),
        PeriodTime(15, "21:55", "22:00"),
        PeriodTime(16, "22:05", "22:10"),
        PeriodTime(17, "22:15", "22:20")
    )

    private val byPeriod: Map<Int, PeriodTime> = ALL.associateBy { it.period }

    /** 取指定节次的时间；超出范围返回 null */
    fun of(period: Int): PeriodTime? = byPeriod[period]

    /** 总节数 */
    val maxPeriod: Int get() = ALL.size

    /** 判断是否上午（1-5 节） */
    fun isMorning(period: Int): Boolean = period in 1..5

    /** 判断是否下午（6-10 节） */
    fun isAfternoon(period: Int): Boolean = period in 6..10

    /** 判断是否晚上（11-17 节） */
    fun isEvening(period: Int): Boolean = period in 11..17
}
