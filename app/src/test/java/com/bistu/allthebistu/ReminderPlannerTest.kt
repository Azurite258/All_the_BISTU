package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.TermWeek
import com.bistu.allthebistu.data.reminder.ReminderPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 提醒时刻计算。
 *
 * 这是整个提醒功能里唯一「算错了用户也发现不了，只会觉得 App 不准」的地方，
 * 所以每一条边界都单独钉一个用例：提前量上下限、已经过掉的时刻、窗口边界、
 * 缺时间的兜底、以及「没有教学周历就不猜」这条硬规则。
 *
 * 全部用固定的 Asia/Shanghai，避免跑测试的机器时区不同得出不同结论。
 */
class ReminderPlannerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 第 8 周：2026-10-19(周一) ~ 2026-10-25(周日) */
    private val week8 = TermWeek(8, "2026-10-19", "2026-10-25")
    /** 第 9 周：2026-10-26 ~ 2026-11-01 */
    private val week9 = TermWeek(9, "2026-10-26", "2026-11-01")

    private fun ms(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun course(
        name: String = "数据结构",
        dayOfWeek: Int = 1,
        beginSection: Int = 1,
        endSection: Int = 2,
        beginTime: String = "08:00",
        endTime: String = "09:35",
        weeks: Set<Int> = setOf(8),
        room: String = "A206",
        code: String = "CS101"
    ) = Course(
        name = name,
        code = code,
        credit = "4",
        teacher = "张老师",
        room = room,
        campus = "沙河",
        weeks = weeks,
        dayOfWeek = dayOfWeek,
        beginSection = beginSection,
        endSection = endSection,
        beginTime = beginTime,
        endTime = endTime,
        colorHex = "#FFFFFF"
    )

    private fun schedule(courses: List<Course>, weeks: List<TermWeek>) = ScheduleData(
        termCode = "2026-2027-1",
        termName = "2026-2027 第一学期",
        studentName = "测试",
        studentId = "2024001",
        major = "计算机",
        className = "计科2401",
        campus = "沙河",
        courses = courses,
        termWeeks = weeks
    )

    // ── 基础换算 ──

    @Test
    fun `提前十五分钟`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(1, plans.size)
        assertEquals(ms(2026, 10, 19, 8, 0), plans[0].startAt)
        assertEquals(ms(2026, 10, 19, 7, 45), plans[0].triggerAt)
        assertEquals(ms(2026, 10, 19, 9, 35), plans[0].endAt)
        assertEquals(8, plans[0].weekNumber)
        assertEquals("A206", plans[0].room)
    }

    @Test
    fun `提前零分钟就是上课时刻`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 0,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(plans[0].startAt, plans[0].triggerAt)
    }

    @Test
    fun `提前四小时`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 240,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(ms(2026, 10, 19, 4, 0), plans[0].triggerAt)
    }

    // ── 窗口与补发 ──

    @Test
    fun `窗口外的课不排`() {
        // 第 9 周周一 8:00 的课，从 10-18 起算 8 天窗口刚好差一点够不到
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8, week9)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            windowDays = 7,
            zone = zone
        )
        assertEquals(1, plans.size)
        assertEquals(ms(2026, 10, 19, 7, 45), plans[0].triggerAt)
    }

    @Test
    fun `窗口把下一周的课也带进来`() {
        // 从 10-25 起算 8 天窗口，能覆盖到第 9 周周一 7:45 的那次提醒
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course(weeks = setOf(8, 9))), listOf(week8, week9)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 25, 0, 0),
            windowDays = 8,
            zone = zone
        )
        assertEquals(1, plans.size)
        assertEquals(9, plans[0].weekNumber)
        assertEquals(ms(2026, 10, 26, 7, 45), plans[0].triggerAt)
    }

    @Test
    fun `已经过掉的提醒不补发`() {
        // 8:00 上课、提前 15 分钟，到 8:10 才重排 —— 这条必须丢掉
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 19, 8, 10),
            zone = zone
        )
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `还没到提醒点就正常排上`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 19, 7, 40),
            zone = zone
        )
        assertEquals(1, plans.size)
        assertEquals(ms(2026, 10, 19, 7, 45), plans[0].triggerAt)
    }

    // ── 数据缺失与脏数据 ──

    @Test
    fun `没有教学周历就一条都不排`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), emptyList()),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `未排课的课程不参与提醒`() {
        val bad = course(dayOfWeek = 0, beginSection = 0, endSection = 0, beginTime = "", endTime = "")
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(bad), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertTrue(plans.isEmpty())
    }

    @Test
    fun `教务没给时间时按节次表兜底`() {
        val noTime = course(beginTime = "", endTime = "", beginSection = 1, endSection = 2)
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(noTime), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(1, plans.size)
        // PeriodTimes 第 1 节 = 08:00
        assertEquals(ms(2026, 10, 19, 8, 0), plans[0].startAt)
    }

    @Test
    fun `周次不匹配的周不排`() {
        val onlyWeek9 = course(weeks = setOf(9))
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(onlyWeek9), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertTrue(plans.isEmpty())
    }

    // ── 多门课 ──

    @Test
    fun `同一天同一时间的两门课都要提醒`() {
        val a = course(name = "数据结构", code = "CS101", room = "A206")
        val b = course(name = "高等数学", code = "MA101", room = "B301")
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(a, b), listOf(week8)),
            advanceMinutes = 15,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(2, plans.size)
        assertEquals(setOf("数据结构", "高等数学"), plans.map { it.courseName }.toSet())
    }

    @Test
    fun `结果按触发时间升序`() {
        val late = course(name = "晚课", code = "EVE", beginTime = "18:30", endTime = "20:05", beginSection = 11, endSection = 12)
        val early = course(name = "早课", code = "MOR", beginTime = "08:00", endTime = "09:35")
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(late, early), listOf(week8)),
            advanceMinutes = 10,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        assertEquals(listOf("早课", "晚课"), plans.map { it.courseName })
    }

    @Test
    fun `同一门课不同教室算两条独立课程`() {
        val a = course(room = "WLC-305", weeks = setOf(8))
        val b = course(room = "WLC-204", weeks = setOf(9))
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(a, b), listOf(week8, week9)),
            advanceMinutes = 0,
            fromMs = ms(2026, 10, 18, 0, 0),
            windowDays = 20,
            zone = zone
        )
        assertEquals(2, plans.size)
        assertEquals(setOf("WLC-305", "WLC-204"), plans.map { it.room }.toSet())
    }

    // ── 提前量清洗 ──

    @Test
    fun `提前量按五取整`() {
        assertEquals(15, ReminderPlanner.sanitizeAdvance(15))
        assertEquals(15, ReminderPlanner.sanitizeAdvance(19))
        assertEquals(20, ReminderPlanner.sanitizeAdvance(20))
        assertEquals(0, ReminderPlanner.sanitizeAdvance(4))
    }

    @Test
    fun `提前量夹在零到二百四之间`() {
        assertEquals(0, ReminderPlanner.sanitizeAdvance(-30))
        assertEquals(0, ReminderPlanner.sanitizeAdvance(Int.MIN_VALUE))
        assertEquals(240, ReminderPlanner.sanitizeAdvance(240))
        assertEquals(240, ReminderPlanner.sanitizeAdvance(999))
        assertEquals(240, ReminderPlanner.sanitizeAdvance(Int.MAX_VALUE))
    }

    @Test
    fun `脏提前量参与计算时同样被收敛`() {
        val plans = ReminderPlanner.plan(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 1000,
            fromMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        // 1000 → 240 分钟
        assertEquals(ms(2026, 10, 19, 4, 0), plans[0].triggerAt)
    }

    // ── 时间解析 ──

    @Test
    fun `常见时间写法都认`() {
        assertEquals(LocalTime.of(8, 0), ReminderPlanner.parseClock("08:00"))
        assertEquals(LocalTime.of(8, 5), ReminderPlanner.parseClock("8:05"))
        assertEquals(LocalTime.of(13, 30), ReminderPlanner.parseClock("13:30"))
        assertEquals(LocalTime.of(8, 0), ReminderPlanner.parseClock("0800"))
        assertEquals(LocalTime.of(18, 30), ReminderPlanner.parseClock("1830"))
    }

    @Test
    fun `认不出的时间返回 null 而不是瞎猜`() {
        assertNull(ReminderPlanner.parseClock(""))
        assertNull(ReminderPlanner.parseClock("上午"))
        assertNull(ReminderPlanner.parseClock("25:00"))
        assertNull(ReminderPlanner.parseClock("12:99"))
        assertNull(ReminderPlanner.parseClock("8"))
    }

    @Test
    fun `日期解析拒绝非法日期`() {
        assertEquals(2026, ReminderPlanner.parseIsoDate("2026-10-19")?.year)
        assertNull(ReminderPlanner.parseIsoDate("2026-13-01"))
        assertNull(ReminderPlanner.parseIsoDate("2026/10/19"))
        assertNull(ReminderPlanner.parseIsoDate(""))
    }

    // ── 整学期（系统日历用） ──

    @Test
    fun `整学期计划不受窗口限制`() {
        val plans = ReminderPlanner.planWholeTerm(
            data = schedule(listOf(course(weeks = setOf(8, 9, 10))), listOf(week8, week9)),
            advanceMinutes = 15,
            nowMs = ms(2026, 10, 18, 0, 0),
            zone = zone
        )
        // week8 / week9 各一节（week10 不在 termWeeks 里）
        assertEquals(2, plans.size)
        assertEquals(listOf(8, 9), plans.map { it.weekNumber })
    }

    @Test
    fun `整学期计划跳过已经结束的课`() {
        val plans = ReminderPlanner.planWholeTerm(
            data = schedule(listOf(course()), listOf(week8)),
            advanceMinutes = 15,
            // 周一 10:00，课 8:00-9:35 已经上完
            nowMs = ms(2026, 10, 19, 10, 0),
            zone = zone
        )
        assertTrue(plans.isEmpty())
    }

    // ── 双滚轮（时 / 分）的换算 ──
    // 两个轮子各自独立滚动，拼回分钟时很容易在「4 小时 30 分」这类越界组合上出错，
    // 一旦错了用户会觉得「轮子转了但没生效」

    @Test
    fun `提前量拆成时和分`() {
        assertEquals(0 to 0, ReminderPlanner.splitAdvance(0))
        assertEquals(0 to 5, ReminderPlanner.splitAdvance(5))
        assertEquals(2 to 30, ReminderPlanner.splitAdvance(150))
        assertEquals(4 to 0, ReminderPlanner.splitAdvance(240))
    }

    @Test
    fun `拆分前先清洗脏数据`() {
        // 负数
        assertEquals(0 to 0, ReminderPlanner.splitAdvance(-30))
        // 不整档：152 → 150，不能让轮子停在「2 小时 32 分」但实际存 150
        assertEquals(2 to 30, ReminderPlanner.splitAdvance(152))
        // 超上限：247 → 245 → 夹到 240
        assertEquals(4 to 0, ReminderPlanner.splitAdvance(247))
    }

    @Test
    fun `时和分拼回分钟`() {
        assertEquals(5, ReminderPlanner.joinAdvance(0, 5))
        assertEquals(150, ReminderPlanner.joinAdvance(2, 30))
        assertEquals(240, ReminderPlanner.joinAdvance(4, 0))
    }

    @Test
    fun `超过上限的组合被夹成四小时`() {
        assertEquals(240, ReminderPlanner.joinAdvance(4, 30))
        assertEquals(240, ReminderPlanner.joinAdvance(9, 55))
        // 夹完之后分钟轮要回到 00，否则界面会显示「4 小时 30 分」但存的是 240
        assertEquals(4 to 0, ReminderPlanner.splitAdvance(ReminderPlanner.joinAdvance(4, 30)))
    }

    @Test
    fun `小时和分钟都为负时归零`() {
        assertEquals(0, ReminderPlanner.joinAdvance(-1, -5))
    }

    @Test
    fun `拆合是互逆的`() {
        listOf(0, 5, 15, 30, 60, 95, 150, 235, 240).forEach { minutes ->
            val (h, m) = ReminderPlanner.splitAdvance(minutes)
            assertEquals(minutes, ReminderPlanner.joinAdvance(h, m))
        }
    }
}
