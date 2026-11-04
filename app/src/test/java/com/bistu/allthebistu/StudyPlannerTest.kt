package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ai.StudyPlanner
import com.bistu.allthebistu.data.event.EventTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 学习计划的纯逻辑测试。
 *
 * 这一层不碰网络、不碰 Compose，所以能全离线跑。重点测**边界**：
 * 模型返回的 JSON 是外部输入，永远不能假设它规整；时长切成段时也不能越界。
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
              {"day":0,"start":"19:00","end":"20:30","title":"复习高数","tag":"作业","note":"先做例题"},
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
            好的，这是按你的时间排的计划：
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

    // ── 提示词 ──

    @Test
    fun `短时提示词里带上了时间窗和内容`() {
        val prompt = StudyPlanner.shortUserPrompt("高数、英语单词", START_1400, 180)
        assertTrue(prompt.contains("高数、英语单词"))
        assertTrue(prompt.contains("14:00"))
        assertTrue(prompt.contains("17:00"))
        assertTrue(prompt.contains("180 分钟"))
    }

    @Test
    fun `多天提示词里带上了课表和跨度`() {
        val courses = listOf(course("高等数学", dow = 3, begin = "08:00", end = "09:35"))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val prompt = StudyPlanner.multiDayUserPrompt(
            content = "线性代数 前三章",
            days = 3,
            courses = courses,
            week = 8,
            today = TODAY,
            todayDow = 3,
            slots = slots
        )

        assertTrue(prompt.contains("线性代数 前三章"))
        assertTrue(prompt.contains("跨度 3 天"))
        assertTrue(prompt.contains("高等数学"))
        // 课表按天列，并且告诉模型当天几节 —— 它据此决定这天排几段
        assertTrue(prompt.contains("2 节课"))
        assertTrue(prompt.contains("没课，整天都可以安排"))
        assertTrue(prompt.contains("可以安排的空闲时段"))
    }

    @Test
    fun `多天提示词给每天标出课业强度`() {
        val courses = listOf(
            course("高数", dow = 3, begin = "08:00", end = "09:35"),
            course("英语", dow = 3, begin = "10:00", end = "11:35"),
            course("物理", dow = 3, begin = "13:30", end = "15:05")
        )
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val prompt = StudyPlanner.multiDayUserPrompt(
            content = "线代", days = 2, courses = courses, week = 8,
            today = TODAY, todayDow = 3, slots = slots
        )

        // 三门各 2 节 = 6 节，今天必须被标成课多的一天
        assertTrue(prompt.contains("6 节课"))
        // 明天（周四）没课，要明确说「整天都可以安排」
        assertTrue(prompt.contains("没课，整天都可以安排"))
    }

    @Test
    fun `个人需求会被拼进两套提示词的末尾`() {
        val note = "晚上 10 点后不学，周三有社团"
        val short = StudyPlanner.shortUserPrompt("高数", START_1400, 120, note)
        assertTrue(short.contains("额外要求"))
        assertTrue(short.contains("周三有社团"))

        val courses = listOf(course("高数", dow = 3, begin = "08:00", end = "09:35"))
        val multi = StudyPlanner.multiDayUserPrompt(
            content = "线代", days = 3, courses = courses, week = 8,
            today = TODAY, todayDow = 3,
            slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600),
            note = note
        )
        assertTrue(multi.contains("周三有社团"))
        // 必须排在最后：先给约束和课表，最后才是「优先满足」的偏好
        assertTrue(multi.trimEnd().endsWith("周三有社团"))
    }

    @Test
    fun `没填个人需求时提示词里不会出现空的要求段`() {
        val short = StudyPlanner.shortUserPrompt("高数", START_1400, 120)
        assertTrue(!short.contains("额外要求"))

        val multi = StudyPlanner.multiDayUserPrompt(
            content = "线代", days = 2, courses = emptyList(), week = 8,
            today = TODAY, todayDow = 3, slots = emptyList()
        )
        assertTrue(!multi.contains("额外要求"))
    }

    // ── 空闲时段 ──

    @Test
    fun `有课的时段被排除`() {
        val courses = listOf(
            course("高等数学", dow = 3, begin = "08:00", end = "09:35"),
            course("大学物理", dow = 3, begin = "13:30", end = "15:05")
        )

        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 3, nowMinutes = 600)
        val today = slots.filter { it.dayOffset == 0 }

        assertTrue(today.isNotEmpty())
        // 10:00 之后的课在 13:30 才开始，所以 10:30-13:30 这段应该是空的
        assertTrue(today.any { it.startMinutes == 630 && it.endMinutes == 810 })

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

        assertTrue(today.isNotEmpty())
        assertEquals(20 * 60 + 30, today.first().startMinutes)
        assertEquals(22 * 60 + 30, today.first().endMinutes)
    }

    @Test
    fun `不到一小时的空档不排`() {
        // 课从 08:00 上到 22:00，只剩半小时
        val courses = listOf(course("满课", dow = 1, begin = "08:00", end = "22:00"))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1)
        assertTrue(slots.isEmpty())
    }

    @Test
    fun `不是本教学周的课不占空档`() {
        val courses = listOf(course("只在第 3 周", dow = 1, begin = "08:00", end = "22:00", weeks = setOf(3)))
        val slots = StudyPlanner.freeSlots(courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1)
        assertEquals(1, slots.size)
        assertEquals(8 * 60, slots[0].startMinutes)
    }

    @Test
    fun `多日模式放低门槛后两节课之间的 45 分钟也能排`() {
        // 08:00-10:00 上完，10:45 再上，中间空 45 分钟
        val courses = listOf(
            course("早课", dow = 1, begin = "08:00", end = "10:00"),
            course("第二节", dow = 1, begin = "10:45", end = "12:00")
        )

        val strict = StudyPlanner.freeSlots(
            courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1
        )
        assertTrue("默认 60 分钟门槛应把 45 分钟空档砍掉", strict.none { it.length == 45 })

        val relaxed = StudyPlanner.freeSlots(
            courses, week = 8, todayDow = 1, nowMinutes = 0, horizonDays = 1,
            minSlot = StudyPlanner.MULTI_MIN_SLOT
        )
        assertTrue("多日模式应保留 45 分钟空档", relaxed.any { it.startMinutes == 600 && it.endMinutes == 645 })
    }

    // ── 示例计划（没配 Key 时给用户看效果） ──

    @Test
    fun `短时示例不越界且切成多段`() {
        val plan = StudyPlanner.sampleShortPlan("高数、英语单词、线代", START_1400, 180)

        assertTrue(plan.size >= 2)
        val end = START_1400 + 180 * 60_000L
        plan.forEach { draft ->
            assertTrue("起点越界: ${draft.title}", draft.startAt >= START_1400)
            assertTrue("终点越界: ${draft.title}", draft.endAt <= end)
            assertTrue(draft.durationMinutes >= 15)
            assertTrue(draft.title.isNotBlank())
        }
        // 最后一段是回顾 —— 和提示词里对模型的要求一致
        assertEquals(EventTag.EXAM, plan.last().tag)
        assertTrue(plan.last().title.contains("回顾"))
    }

    @Test
    fun `短时示例按内容拆出不同主题`() {
        val plan = StudyPlanner.sampleShortPlan("高数、英语", START_1400, 300)
        val titles = plan.joinToString { it.title }
        assertTrue(titles.contains("高数"))
        assertTrue(titles.contains("英语"))
    }

    @Test
    fun `多天示例随天数裁剪复习节点`() {
        val three = StudyPlanner.sampleMultiDayPlan("线代", days = 3, startMillis = TODAY)
        // 3 天只有「当天学 + 次日复习」两个节点，第 4 天和第 7 天的节点应被裁掉
        assertEquals(2, three.size)

        val seven = StudyPlanner.sampleMultiDayPlan("线代", days = 7, startMillis = TODAY)
        assertEquals(4, seven.size)
        assertTrue(seven.last().title.contains("回顾"))
        assertTrue(seven.last().note.contains("第 4 遍"))
    }

    @Test
    fun `空内容也能排出一条兜底计划`() {
        val plan = StudyPlanner.sampleShortPlan("", START_1400, 120)
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.title.isNotBlank() })
    }

    @Test
    fun `时间窗短时不硬凑回顾段`() {
        // 45 分钟：用户自己选的窗口，只够一整段，不能切出「学习 50 分 + 回顾 20 分」
        val plan = StudyPlanner.sampleShortPlan("高数", START_1400, 45)
        assertEquals(1, plan.size)
        assertEquals(START_1400, plan[0].startAt)
        assertEquals(START_1400 + 45 * 60_000L, plan[0].endAt)

        // 100 分钟以上才值得留回顾
        val withReview = StudyPlanner.sampleShortPlan("高数", START_1400, 100)
        assertTrue(withReview.last().title.contains("回顾"))
        assertEquals(START_1400 + 100 * 60_000L, withReview.last().endAt)
    }

    @Test
    fun `窗口短到切不动时也返回一整段`() {
        val plan = StudyPlanner.sampleShortPlan("高数", START_1400, 15)
        assertEquals(1, plan.size)
        assertEquals(15, plan[0].durationMinutes)
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

        /** 同一天的 14:00，短时模式常用起点 */
        private val START_1400: Long = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 4, 14, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
