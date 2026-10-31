package com.bistu.allthebistu.data.event

import kotlinx.serialization.Serializable
import java.util.Calendar

/**
 * 自定义日程的重复方式。
 *
 * 只有四档，够覆盖学生日常（上课/背单词/周会/工作日跑操），
 * 再多的规则（每月第几个周几、自定义间隔）收益很低、编辑成本却很高。
 */
object Repeat {
    const val NONE = 0
    const val DAILY = 1
    const val WEEKLY = 2
    const val WEEKDAY = 3

    val names: List<String> = listOf("永不", "每天", "每周", "工作日")

    fun nameOf(value: Int): String = names.getOrElse(value) { names[0] }

    fun sanitize(value: Int): Int = value.coerceIn(0, names.lastIndex)
}

/**
 * 日程标签。
 *
 * 只存序号，颜色放在 UI 层（`ui/event/EventTags.kt`）——
 * 数据层不该依赖 Compose 的 Color，否则单元测试要带上整个 Compose 运行时。
 */
object EventTag {
    const val CLASS = 0
    const val HOMEWORK = 1
    const val EXAM = 2
    const val LIFE = 3
    const val OTHER = 4

    val names: List<String> = listOf("课程", "作业", "考试", "生活", "其他")

    fun nameOf(value: Int): String = names.getOrElse(value) { names.last() }

    fun sanitize(value: Int): Int = value.coerceIn(0, names.lastIndex)
}

/**
 * 用户自己加的日程。
 *
 * 和课程的区别：课程是教务系统同步下来的、按「第几周 + 第几节」定位；
 * 日程是用户自己写的、按真实时刻定位，只存在本机。
 * 所以这里用 epoch 毫秒而不是「周次 + 节次」，也不参与 [com.bistu.allthebistu.data.patch] 那套改动层。
 */
@Serializable
data class UserEvent(
    /** 本机生成的 id（UUID），空串表示还没存过 */
    val id: String = "",
    val title: String = "",
    /** 开始 / 结束时刻，epoch 毫秒 */
    val startAt: Long = 0L,
    val endAt: Long = 0L,
    val repeat: Int = Repeat.NONE,
    val tag: Int = EventTag.OTHER,
    val note: String = "",
) {

    val startMinutes: Int get() = minutesOf(startAt)

    val endMinutes: Int get() = minutesOf(endAt)

    /** "08:00" */
    val startClock: String get() = clockOf(startAt)

    val endClock: String get() = clockOf(endAt)

    /** 时长（分钟），结束后早于开始时返回 0 */
    val durationMinutes: Int get() = ((endAt - startAt) / 60_000L).toInt().coerceAtLeast(0)

    /**
     * 这一天有没有这条日程。
     *
     * @param dayMillis 任意属于那一天的毫秒数（内部只取年月日）
     */
    fun occursOn(dayMillis: Long): Boolean {
        val startDay = dayKey(startAt)
        val target = dayKey(dayMillis)
        if (target < startDay) return false
        return when (Repeat.sanitize(repeat)) {
            Repeat.DAILY -> true
            Repeat.WEEKLY -> dayOfWeek(startAt) == dayOfWeek(dayMillis)
            Repeat.WEEKDAY -> dayOfWeek(dayMillis) in 1..5
            else -> target == startDay
        }
    }

    companion object {

        /** 分钟数（0..1439），用于和课程时间线混排 */
        fun minutesOf(millis: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        }

        fun clockOf(millis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        /** 2026-10-31 这种「日粒度」的整数键，直接比大小即可判断先后 */
        fun dayKey(millis: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 +
                    c.get(Calendar.DAY_OF_MONTH)
        }

        /** 1 = 周一 … 7 = 周日 */
        fun dayOfWeek(millis: Long): Int {
            val d = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
            return if (d == Calendar.SUNDAY) 7 else d - 1
        }

        /** 把某天的年月日搬到 [millis] 上，时分秒保留 */
        fun withDateOf(millis: Long, likeMillis: Long): Long {
            val from = Calendar.getInstance().apply { timeInMillis = likeMillis }
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            c.set(Calendar.YEAR, from.get(Calendar.YEAR))
            c.set(Calendar.MONTH, from.get(Calendar.MONTH))
            c.set(Calendar.DAY_OF_MONTH, from.get(Calendar.DAY_OF_MONTH))
            return c.timeInMillis
        }

        /** 把某个时刻的时/分搬到 [millis] 上，日期保留 */
        fun withTimeOf(millis: Long, likeMillis: Long): Long {
            val from = Calendar.getInstance().apply { timeInMillis = likeMillis }
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            c.set(Calendar.HOUR_OF_DAY, from.get(Calendar.HOUR_OF_DAY))
            c.set(Calendar.MINUTE, from.get(Calendar.MINUTE))
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
