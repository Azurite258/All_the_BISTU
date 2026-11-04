package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ai.PlanDraft
import com.bistu.allthebistu.data.ai.StudyPlanner
import com.bistu.allthebistu.data.event.EventTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 学习计划的纯逻辑测试。
 *
 * 这一层不碰网络、不碰 Compose，所以能全离线跑。重点测**边界**：
 * 模型返回的 JSON 是外部输入，永远不能假设它规整。
 */
class StudyPlannerTest {

    // ── 时刻解析 ──

    @Test
    fun `parseClock 支持 HH mm 与带秒`() {
        assertEquals(480, StudyPlanner.parseClock("08:00"))
        assertEquals(480, StudyPlanner.parseClock("8:00"))
        assertEquals(1140, StudyPlanner.parseClock("19:00:00"))
        assertEquals(0, StudyPlanner.parseClock("00:00"))
    }

    @Test
    fun `parseClock 拒绝非法值`() {
        assertNull(StudyPlanner.parseClock(null))
        assertNull(StudyPlanner.parseClock(""))
        assertNull(StudyPlanner.parseClock("八点"))
        assertNull(StudyPlanner.parseClock("25:00"))
        assertNull(StudyPlanner.parseClock("12:61"))
    }

    // ── 回复解析 ──

    @Test
    fun `解析标准 JSON`() {
        val text = """
            {"items":[
              {"day":0,"start":"19:00","end":"20:30","title":"复习高数","tag":"作业","note":""},
              {"day":1,"start":"09:00","end":"10:00","title":"写英语作文","tag":"考试","note":"注意字数"}
            ]}
        """.trimIndent()

        val plan = StudyPlanner.parse(text, TODAY)

        assertEquals(2, plan.size)
        assertEquals("复习高数", plan[0].title)
        assertEquals("19:00", plan[0].startClock)
        assertEquals(90, plan[0].durationMinutes)
        assertEquals(EventTag.HOMEWORK, plan[0].tag)
        assertEquals(EventTag.EXAM, plan[1].tag)
        assertEquals("注意字数", plan[1].note)
    }

    @Test
    fun `解析带 markdown 围栏和前后废话的回复`() {
        val text = """
            好的，这是按你的课表排的计划：
            ```json
            {"items":[{"day":0,"start":"19:00","end":"20:00","title":"高数","tag":"作业"}]}
            ```
            希望对你有帮助。
        """.trimIndent()

        val plan = StudyPlanner.parse(text, TODAY)

        assertEquals(1, plan.size)
        assertEquals("高数", plan[0].title)
    }

    @Test
    fun `坏一条不影响其余条目`() {
        val text = """
            {"items":[
              {"day":9,"start":"09:00","end":"10:00","title":"越界","tag":"作业"},
              {"day":0,"start":"25:00","end":"26:00","title":"时间非法","tag":"作业"},
              {"day":0,"start":"20:00","end":"19:00","title":"结束早于开始","tag":"作业"},
              {"day":0,"start":"19:00","end":"20:00","title":"","tag":"作业"},
              {"day":0,"start":"19:00","end":"20:00","title":"这条应该留下","tag":"作业"}
            ]}
        """.trimIndent()

        val plan = StudyPlanner.parse(text, TODAY)

        assertEquals(1, plan.size)
        assertEquals("这条应该留下", plan[0].title)
    }

    @Test
    fun `未知标签回落为其他`() {
        val text = """{"items":[{"day":0,"start":"19:00","end":"20:00","title":"跑步","tag":"运动"}]}"""
        val plan = StudyPlanner.parse(text, TODAY)
        assertEquals(EventTag.OTHER, plan[0].tag)
    }

    @Test
    fun `完全不是 JSON 时返回空而不崩`() {
        assertEquals(0, StudyPlanner.parse("抱歉，我无法完成这个请求。", TODAY).size)
        assertEquals(0, StudyPlanner.parse("", TODAY).size)
    }

    @Test
    fun `day 决定的是哪一天`() {
        val text = """{"items":[{"day":2,"start":"09:00","end":"10:00","title":"后天的事","tag":"其他"}]}"""
        val plan = StudyPlanner.parse(text, TODAY)
        val expected = StudyPlanner.startOfDay(TODAY, 2) + 9 * 60 * 60_000L
        assertEquals(expected, plan[0].startAt)
    }

    // ── 空闲时段 ──

    @Test
    fun `有课的时段被排除`() {
        val courses = listOf(
            course("高等数学", dow = 3, begin = "08:00", end = "09:35"),
            course("大学物理", dow = 3, begin = "13:30", end = "15:05")
        )

        // 周三 10:00 起算，看今天还剩哪些整块空档
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val today = slots.filter { it.dayOffset == 0 }

        assertTrue(today.isNotEmpty())
        // 10:00 之后的课在 13:30 才开始，所以 10:30-13:30 这段应该是空的
        assertTrue(today.any { it.startMinutes == 630 && it.endMinutes == 810 })

        // 任何一个空档都不能和上课区间重叠（08:00-09:35 / 13:30-15:05）
        val busy = listOf(480 to 575, 810 to 905)
        today.forEach { slot ->
            busy.forEach { (busyStart, busyEnd) ->
                assertTrue(
                    "空档 ${slot.startClock}-${slot.endClock} 与上课时间重叠",
                    slot.endMinutes <= busyStart || slot.startMinutes >= busyEnd
                )
            }
        }
    }

    @Test
    fun `今天不从已经过去的时间开始排`() {
        val slots = StudyPlanner.freeSlots(
            courses = emptyList(),
            week = 8,
            todayDow = 3,
            nowMinutes = 20 * 60 + 10      // 晚上 20:10
        )
        val today = slots.filter { it.dayOffset == 0 }

        // 20:10 + 30 分钟，取整到 20:30；到 22:00 只剩 90 分钟，够一小时
        assertTrue(today.isNotEmpty())
        assertEquals(20 * 60 + 30, today.first().startMinutes)
        assertEquals(22 * 60, today.first().endMinutes)
    }

    @Test
    fun `不到一小时的空档不排`() {
        // 课从 08:00 上到 21:30，只剩 21:30-22:00 这半小时
        val courses = listOf(course("满课", dow = 1, begin = "08:00", end = "21:30"))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `不是本教学周的课不占空档`() {
        val courses = listOf(course("只在第 3 周", dow = 1, begin = "08:00", end = "21:30", weeks = setOf(3)))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1)
        // 第 8 周没有这门课，周一一整天(08:00-22:00)都是空的
        assertEquals(1, slots.size)
        assertEquals(8 * 60, slots[0].startMinutes)
    }

    // ── 示例计划（没配 Key 时给用户看效果） ──

    @Test
    fun `示例计划落在真实空档里且不超过四条`() {
        val courses = listOf(course("高等数学", dow = 3, begin = "08:00", end = "09:35"))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val plan = StudyPlanner.samplePlan(courses, slots, TODAY)

        assertTrue(plan.isNotEmpty())
        assertTrue(plan.size <= 4)
        plan.forEach { draft ->
            assertNotNull(draft.title)
            assertTrue(draft.title.isNotBlank())
            assertTrue(draft.durationMinutes in 45..120)
            assertTrue(draft.startAt >= TODAY)
        }
    }

    @Test
    fun `没有空档时示例计划为空`() {
        assertTrue(StudyPlanner.samplePlan(emptyList(), emptyList(), TODAY).isEmpty())
    }

    @Test
    fun `提示词里带上了周次课表和空档`() {
        val courses = listOf(course("高等数学", dow = 3, begin = "08:00", end = "09:35"))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val prompt = StudyPlanner.userPrompt(
            courses = courses,
            week = 8,
            today = TODAY,
            todayDow = 3,
            slots = slots,
            goal = "把作业写完"
        )

        assertTrue(prompt.contains("第 8 周"))
        assertTrue(prompt.contains("高等数学"))
        assertTrue(prompt.contains("周三"))
        assertTrue(prompt.contains("可以安排的空闲时段"))
    }

    // ══════════════════════════════════════════════════════════

    private fun course(
        name: String,
        dow: Int,
        begin: String,
        end: String,
        weeks: Set<Int> = setOf(8)
    ) = Course(
        name = name,
        code = name,
        credit = "2",
        teacher = "",
        room = "",
        campus = "",
        weeks = weeks,
        dayOfWeek = dow,
        beginSection = 1,
        endSection = 2,
        beginTime = begin,
        endTime = end,
        colorHex = "#FFFFFF"
    )

    companion object {
        /** 固定在 2026-11-04（周三）10:00，避免测试跟着运行时刻变 */
        private val TODAY: Long = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
