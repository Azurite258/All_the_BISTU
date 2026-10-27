package com.bistu.allthebistu.data.reminder

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.ui.schedule.PeriodTimes
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 一条待触发的上课提醒。
 *
 * 时间都是 epoch 毫秒，方便直接喂给 AlarmManager 和 CalendarContract。
 */
data class ReminderPlan(
    val courseName: String,
    val room: String,
    val teacher: String,
    /** 第几周。通知里显示「第 8 周 周一」，用户对上教务网页更直观 */
    val weekNumber: Int,
    /** 上课时刻 */
    val startAt: Long,
    /** 下课时刻 */
    val endAt: Long,
    /** 提醒时刻 = 上课时刻 - 提前分钟数 */
    val triggerAt: Long,
    /**
     * 稳定的课程标识（课程码 + 星期 + 节次 + 教室）。
     *
     * 和 ScheduleParser.mergeDuplicates 用的是同一个 key 组合，
     * 所以「同一门课不同教室」不会被误当成一门 —— 那本来就是两段不同周次。
     */
    val courseKey: String
) {
    /**
     * AlarmManager 的 requestCode。
     *
     * 必须同时带上课程和上课时刻：只按课程算的话，同一门课下一周会被
     * FLAG_UPDATE_CURRENT 覆盖掉上一周的 PendingIntent，表现为「只有最后一次课会响」。
     */
    val requestCode: Int get() = (courseKey + "@" + startAt).hashCode()
}

/**
 * 把课表换算成「什么时候该提醒」。
 *
 * 这里全部是纯函数（不碰 Context / 数据库 / 系统 API），因为提醒最容易出错的地方
 * 不是调度，而是**算错时间**：提前量、周次、跨天、夏令时、已经过掉的时刻。
 * 算错只能靠用户投诉发现，所以必须能拿 JVM 单测钉住。
 *
 * ── 为什么需要「滚动窗口」而不是一次排满整学期 ──
 * 一学期 16 周 × 每周十几节 ≈ 200+ 个闹钟。AlarmManager 扛得住数量，
 * 但扛不住「排完之后全丢了」——重启、系统清理、换时区、用户改时间，
 * 任何一次都会让剩下半学期的提醒全部失效，而 App 可能一个月都不打开一次。
 * 所以只排未来一周，再用「每次响铃后重排 + 每天兜底重排 + 开机重排」续着。
 */
object ReminderPlanner {

    /** 提前量上下限（分钟）。上限 240 = 4 小时，够覆盖「上午的课提前一节课提醒」 */
    const val MIN_ADVANCE = 0
    const val MAX_ADVANCE = 240

    /** 档位步长。5 分钟一档：再细没有意义，又不是秒表 */
    const val ADVANCE_STEP = 5

    /** 默认提前 15 分钟 —— 从宿舍走到教学楼差不多就是这个时间 */
    const val DEFAULT_ADVANCE = 15

    /**
     * 排程窗口天数。
     *
     * 7 天是一轮，多留 1 天是给「提前量最多 4 小时」的跨天情况兜底：
     * 下周一的课，本周日晚上就要把闹钟排上。
     */
    const val WINDOW_DAYS = 8

    /** 把任意整数收敛到合法档位：先按步长向下取整，再夹到 0..240 */
    fun sanitizeAdvance(minutes: Int): Int {
        val stepped = (minutes / ADVANCE_STEP) * ADVANCE_STEP
        return stepped.coerceIn(MIN_ADVANCE, MAX_ADVANCE)
    }

    /**
     * 提前量拆成「小时、分钟」，给两个滚轮定位用。
     *
     * 先清洗再拆，所以传进来的脏数据（负数、不整档、超上限）
     * 都会得到和界面上显示一致的结果 —— 不这么做就会出现
     * 「轮子停在 4 小时，实际存的是 250 分钟」这种对不上的状态。
     */
    fun splitAdvance(minutes: Int): Pair<Int, Int> {
        val safe = sanitizeAdvance(minutes)
        return safe / 60 to safe % 60
    }

    /**
     * 两个滚轮的选择拼回提前量（分钟）。
     *
     * 顶到上限时多余的分钟会被夹掉：4 小时 30 分 → 240 分钟 → 分钟轮重新显示 00。
     * 界面上「4 小时」时分钟轮只剩 0 一项，所以正常操作走不到这条路径，
     * 这里是防脏数据和联动时序的兜底。
     */
    fun joinAdvance(hours: Int, minutes: Int): Int =
        sanitizeAdvance(hours.coerceAtLeast(0) * 60 + minutes.coerceAtLeast(0))

    /**
     * 计算 [fromMs, fromMs + windowDays 天) 这个窗口里需要触发的提醒。
     *
     * @param fromMs 窗口起点（一般传当前时刻）
     * @param windowDays 窗口长度，单位天
     * @param zone 时区。显式传参是为了单测不依赖机器时区
     */
    fun plan(
        data: ScheduleData,
        advanceMinutes: Int,
        fromMs: Long,
        windowDays: Int = WINDOW_DAYS,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ReminderPlan> {
        // 没有教学周历就等于不知道「第 3 周」是哪天，一节课都算不出来。
        // 这种情况不猜：宁可不出提醒，也不要提醒错时间。
        if (data.termWeeks.isEmpty()) return emptyList()

        val advance = sanitizeAdvance(advanceMinutes)
        val advanceMs = advance * 60_000L
        val toMs = fromMs + windowDays * 86_400_000L

        val arranged = data.courses.filter { it.isArranged && it.weeks.isNotEmpty() }
        if (arranged.isEmpty()) return emptyList()

        val out = ArrayList<ReminderPlan>()
        val seen = HashSet<String>()

        data.termWeeks.forEach weekLoop@{ termWeek ->
            val weekStart = parseIsoDate(termWeek.startDate) ?: return@weekLoop
            arranged.forEach courseLoop@{ course ->
                if (termWeek.weekNumber !in course.weeks) return@courseLoop

                val startTime = startTimeOf(course) ?: return@courseLoop
                val endTime = endTimeOf(course, startTime)

                val date = weekStart.plusDays((course.dayOfWeek - 1).toLong())
                val startAt = toEpochMs(date, startTime, zone)
                val triggerAt = startAt - advanceMs

                // 已经过掉的时刻不补发：开机、重排、刷新课表都可能撞上，
                // 一旦补发，用户会被一串「三门课已经开始了」轰炸
                if (triggerAt < fromMs) return@courseLoop
                if (triggerAt >= toMs) return@courseLoop

                val key = courseKeyOf(course)
                // 同一门课同一时刻只排一次（教学周历里不该有重复周次，但脏数据会有）
                if (!seen.add("$key@$startAt")) return@courseLoop

                out += ReminderPlan(
                    courseName = course.name,
                    room = course.room,
                    teacher = course.teacher,
                    weekNumber = termWeek.weekNumber,
                    startAt = startAt,
                    endAt = toEpochMs(date, endTime, zone),
                    triggerAt = triggerAt,
                    courseKey = key
                )
            }
        }

        return out.sortedBy { it.triggerAt }
    }

    /**
     * 整学期的提醒（给系统日历同步用，不受窗口限制）。
     *
     * 和 [plan] 共用同一套时间计算，区别只有两点：
     *  - 不裁窗口，整学期都要写进日历
     *  - 不丢掉「已经开始」的，只去掉已经过去的（日历里留历史没有意义）
     */
    fun planWholeTerm(
        data: ScheduleData,
        advanceMinutes: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<ReminderPlan> {
        if (data.termWeeks.isEmpty()) return emptyList()
        val advance = sanitizeAdvance(advanceMinutes)
        val advanceMs = advance * 60_000L
        val out = ArrayList<ReminderPlan>()
        val seen = HashSet<String>()

        val arranged = data.courses.filter { it.isArranged && it.weeks.isNotEmpty() }
        data.termWeeks.forEach weekLoop@{ termWeek ->
            val weekStart = parseIsoDate(termWeek.startDate) ?: return@weekLoop
            arranged.forEach courseLoop@{ course ->
                if (termWeek.weekNumber !in course.weeks) return@courseLoop
                val startTime = startTimeOf(course) ?: return@courseLoop
                val endTime = endTimeOf(course, startTime)
                val date = weekStart.plusDays((course.dayOfWeek - 1).toLong())
                val startAt = toEpochMs(date, startTime, zone)
                // 下课都过完了就不要了；正在上的那节仍然保留
                val endAt = toEpochMs(date, endTime, zone)
                if (endAt < nowMs) return@courseLoop
                val key = courseKeyOf(course)
                if (!seen.add("$key@$startAt")) return@courseLoop
                out += ReminderPlan(
                    courseName = course.name,
                    room = course.room,
                    teacher = course.teacher,
                    weekNumber = termWeek.weekNumber,
                    startAt = startAt,
                    endAt = endAt,
                    triggerAt = startAt - advanceMs,
                    courseKey = key
                )
            }
        }
        return out.sortedBy { it.startAt }
    }

    fun courseKeyOf(course: Course): String =
        "${course.code}|${course.dayOfWeek}|${course.beginSection}|${course.room}"

    // ───────────────────────────────────────────────────────────
    // 时间解析
    // ───────────────────────────────────────────────────────────

    /**
     * 上课时刻。优先用教务系统给的时间（最准），它没给才按节次查表。
     *
     * 节次表（[PeriodTimes]）是写死的校区标准时间，一旦调课就过期，
     * 所以只能当兜底，不能当主数据源。
     */
    private fun startTimeOf(course: Course): LocalTime? =
        parseClock(course.beginTime)
            ?: PeriodTimes.of(course.beginSection)?.startTime?.let(::parseClock)

    private fun endTimeOf(course: Course, startTime: LocalTime): LocalTime =
        parseClock(course.endTime)
            ?: PeriodTimes.of(course.endSection)?.endTime?.let(::parseClock)
            // 连下课时间都没有时，按一节课 45 分钟估（北信科一节就是 45 分钟）
            ?: startTime.plusMinutes(45)

    /** "08:00" / "8:00" / "0800" 都认；认不出返回 null，不要瞎猜 */
    internal fun parseClock(raw: String): LocalTime? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val colon = text.indexOf(':')
        val (h, m) = if (colon > 0) {
            text.substring(0, colon).toIntOrNull() to text.substring(colon + 1).take(2).toIntOrNull()
        } else if (text.length in 3..4 && text.all { it.isDigit() }) {
            text.dropLast(2).toIntOrNull() to text.takeLast(2).toIntOrNull()
        } else {
            return null
        }

        if (h == null || m == null) return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }

    /** "2026-10-19" → LocalDate；手工解析避免 DateTimeFormatter 的严格模式坑 */
    internal fun parseIsoDate(raw: String): LocalDate? {
        val parts = raw.trim().split("-")
        if (parts.size != 3) return null
        val y = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val d = parts[2].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(y, m, d) }.getOrNull()
    }

    private fun toEpochMs(date: LocalDate, time: LocalTime, zone: ZoneId): Long =
        LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli()
}
