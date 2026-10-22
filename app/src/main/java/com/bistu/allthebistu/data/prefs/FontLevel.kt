package com.bistu.allthebistu.data.prefs

/**
 * 课表字体挡位。
 *
 * 三档对应的不是「字号数字」，而是「课程名一行能放几个汉字」：
 * 5 / 4 / 3。这样换设备（列宽变了）时用户的预期仍然是稳定的，
 * 具体字号由 [com.bistu.allthebistu.ui.schedule.CourseTypography] 按列宽反算。
 */
object FontLevel {
    /** 最小字，课名一行约 5 个字 */
    const val SMALL = 0

    /** 默认档，课名一行约 4 个字 */
    const val MEDIUM = 1

    /** 最大字，课名一行约 3 个字 */
    const val LARGE = 2

    /** 把任意 Int 收敛到合法挡位，避免脏数据把排版搞崩 */
    fun sanitize(level: Int): Int = level.coerceIn(SMALL, LARGE)

    /** 给界面用的文字标签 */
    fun label(level: Int): String = when (sanitize(level)) {
        SMALL -> "小"
        LARGE -> "大"
        else -> "中"
    }
}
