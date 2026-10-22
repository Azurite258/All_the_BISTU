package com.example.study1.ui.schedule

import com.example.study1.data.TermWeek
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日期工具：计算第 N 周对应的具体日期。
 *
 * 教务系统的教学周是按「周一」开始算的。
 * 例如：第 1 周周一 = 2026-09-01，
 *      第 2 周周一 = 2026-09-08，以此类推。
 */
object DateUtils {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
    private val dayFormat = SimpleDateFormat("d", Locale.CHINA)
    private val monthFormat = SimpleDateFormat("M", Locale.CHINA)

    /**
     * 计算指定周的 7 天日期。
     *
     * @param termStartDate 第 1 周周一的日期，格式 "yyyy-MM-dd"
     * @param weekNumber 周次（从 1 开始）
     * @return 7 个日期字符串，索引 0 = 周一，索引 6 = 周日
     */
    fun weekDates(termStartDate: String, weekNumber: Int): List<String> {
        val start = isoFormat.parse(termStartDate) ?: return emptyList()
        val cal = Calendar.getInstance().apply {
            time = start
            add(Calendar.DAY_OF_MONTH, (weekNumber - 1) * 7)
        }
        return (0..6).map { offset ->
            val c = cal.clone() as Calendar
            c.add(Calendar.DAY_OF_MONTH, offset)
            isoFormat.format(c.time)
        }
    }

    /** 从 "yyyy-MM-dd" 取「日」，如 "2026-10-21" → "21" */
    fun dayOfMonth(isoDate: String): String? =
        runCatching { dayFormat.format(isoFormat.parse(isoDate)!!) }.getOrNull()

    /** 从 "yyyy-MM-dd" 取「月」，如 "2026-10-21" → "10" */
    fun monthOf(isoDate: String): String? =
        runCatching { monthFormat.format(isoFormat.parse(isoDate)!!) }.getOrNull()

    /** 今天是星期几：1=周一 ... 7=周日 */
    fun todayDayOfWeek(): Int {
        val d = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }

    /** 今天是否在学期内（用于判断"今天高亮"是否该显示） */
    fun todayIso(): String = isoFormat.format(Date())

    /**
     * 根据教学周列表 + 今天日期，算出当前是第几周。
     *
     * 比 [currentWeekOf] 更可靠：直接用教务系统给的每周起止日期，
     * 不依赖「学期第 1 周周一」这个需要人工维护的常量。
     *
     * @param termWeeks 教学周列表（可为空）
     * @param today 今天日期，格式 "yyyy-MM-dd"
     * @return 周次；今天不在任何教学周内则返回 null
     */
    fun currentWeekOfTermWeeks(termWeeks: List<TermWeek>, today: String = todayIso()): Int? {
        if (termWeeks.isEmpty()) return null
        val todayMs = runCatching { isoFormat.parse(today)?.time }.getOrNull() ?: return null

        // 先找「今天落在 startDate..endDate 内」的那一周
        termWeeks.forEach { w ->
            val start = runCatching { isoFormat.parse(w.startDate)?.time }.getOrNull()
            val end = runCatching { isoFormat.parse(w.endDate)?.time }.getOrNull()
            if (start != null && end != null) {
                // endDate 是当天 00:00，补足一天再比较
                if (todayMs >= start && todayMs < end + 24 * 60 * 60 * 1000L) {
                    return w.weekNumber
                }
            }
        }

        // 没落在任何周内（假期/开学前）→ 取最近的一周
        val firstStart = runCatching { isoFormat.parse(termWeeks.first().startDate)?.time }.getOrNull()
        if (firstStart != null && todayMs < firstStart) return termWeeks.first().weekNumber
        return termWeeks.lastOrNull()?.weekNumber
    }

    /**
     * 根据当前日期反推是第几周。
     *
     * @param termStartDate 第 1 周周一日期
     * @return 周次；如果不在学期内返回 null
     */
    fun currentWeekOf(termStartDate: String): Int? {
        val start = isoFormat.parse(termStartDate) ?: return null
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val startCal = Calendar.getInstance().apply {
            time = start
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

        val diffMs = today.time - startCal.time
        if (diffMs < 0) return null
        val days = diffMs / (1000 * 60 * 60 * 24)
        return (days / 7).toInt() + 1
    }
}
