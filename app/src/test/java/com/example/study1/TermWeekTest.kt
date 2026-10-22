package com.example.study1

import com.example.study1.data.Course
import com.example.study1.data.ScheduleData
import com.example.study1.data.TermWeek
import com.example.study1.ui.schedule.DateUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 教学周相关逻辑测试。
 *
 * 重点验证 [DateUtils.currentWeekOfTermWeeks]：
 * 它替代了原来写死的 TERM_START_DATE，必须能正确处理
 * 周内、周首尾、开学前、学期后等各种日期。
 */
class TermWeekTest {

    private val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    /** 构造一个从 2026-09-07 开始的学期 */
    private fun makeTermWeeks(count: Int = 18): List<TermWeek> {
        val base = fmt.parse("2026-09-07")!!
        return (1..count).map { w ->
            val startCal = Calendar.getInstance().apply {
                time = base
                add(Calendar.DAY_OF_MONTH, (w - 1) * 7)
            }
            val endCal = startCal.clone() as Calendar
            endCal.add(Calendar.DAY_OF_MONTH, 6)
            TermWeek(
                weekNumber = w,
                startDate = fmt.format(startCal.time),
                endDate = fmt.format(endCal.time)
            )
        }
    }

    private fun course(weeks: Set<Int>) = Course(
        name = "测试课", code = "T1", credit = "2", teacher = "张",
        room = "A101", campus = "主校区",
        weeks = weeks, dayOfWeek = 1,
        beginSection = 1, endSection = 2,
        beginTime = "08:00", endTime = "09:35",
        colorHex = "#FFF0CC"
    )

    private fun scheduleData(courses: List<Course>, termWeeks: List<TermWeek>) = ScheduleData(
        termCode = "2026-2027-1", termName = "2026-2027-1",
        studentName = "", studentId = "", major = "", className = "", campus = "",
        courses = courses, termWeeks = termWeeks
    )

    // ── 当前周计算 ──

    @Test
    fun `week 1 first day`() {
        assertEquals(1, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-09-07"))
    }

    @Test
    fun `week 1 last day is still week 1`() {
        assertEquals(1, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-09-13"))
    }

    @Test
    fun `week 2 first day`() {
        assertEquals(2, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-09-14"))
    }

    @Test
    fun `2026-10-21 is week 7`() {
        // 交接文档记载：10/21 是第 7 周周三
        assertEquals(7, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-10-21"))
    }

    @Test
    fun `2026-10-22 is week 7 thursday`() {
        assertEquals(7, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-10-22"))
    }

    @Test
    fun `date before term returns week 1`() {
        assertEquals(1, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2026-08-01"))
    }

    @Test
    fun `date after term returns last week`() {
        assertEquals(18, DateUtils.currentWeekOfTermWeeks(makeTermWeeks(), "2027-03-01"))
    }

    @Test
    fun `empty term weeks returns null`() {
        assertNull(DateUtils.currentWeekOfTermWeeks(emptyList(), "2026-10-21"))
    }

    // ── 显示范围 ──

    @Test
    fun `displayWeekRange prefers termWeeks over course range`() {
        // 课程只到 13 周，但教学周有 18 周 → 应显示 1..18
        val data = scheduleData(listOf(course((1..13).toSet())), makeTermWeeks(18))
        assertEquals(1, data.displayWeekRange.first)
        assertEquals(18, data.displayWeekRange.last)
        // weekRange 仍只反映课程覆盖范围
        assertEquals(13, data.weekRange.last)
    }

    @Test
    fun `displayWeekRange falls back when no term weeks`() {
        val data = scheduleData(listOf(course(setOf(3, 4, 5))), emptyList())
        assertEquals(3, data.displayWeekRange.first)
        assertEquals(5, data.displayWeekRange.last)
    }

    // ── 数据完整性 ──

    @Test
    fun `week 7 dates match known schedule`() {
        val w7 = makeTermWeeks().first { it.weekNumber == 7 }
        assertEquals("2026-10-19", w7.startDate)
        assertEquals("2026-10-25", w7.endDate)
    }

    @Test
    fun `termWeekOf finds by number`() {
        val data = scheduleData(emptyList(), makeTermWeeks(18))
        assertEquals("2026-10-19", data.termWeekOf(7)?.startDate)
        assertNull(data.termWeekOf(99))
    }
}
