package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.ui.study.ClassState
import com.bistu.allthebistu.ui.study.durationText
import com.bistu.allthebistu.ui.study.minutesOf
import com.bistu.allthebistu.ui.study.stateOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学习页「今日日程」的状态判定。
 *
 * 这里最容易出错的是 NEXT：如果实现成「所有还没开始的课都算即将开始」，
 * 一整天会挂满标签，反而看不出下一步该上哪节。
 */
class ClassStateTest {

    private fun course(
        name: String,
        begin: String,
        end: String,
        beginSection: Int = 1
    ) = Course(
        name = name,
        code = "C1",
        credit = "3",
        teacher = "教师A",
        room = "教室1",
        campus = "",
        weeks = setOf(1),
        dayOfWeek = 5,
        beginSection = beginSection,
        endSection = beginSection + 1,
        beginTime = begin,
        endTime = end,
        colorHex = "#FFFFFF"
    )

    private val a = course("A", "08:00", "09:35", 1)
    private val b = course("B", "13:30", "15:05", 6)
    private val c = course("C", "18:30", "21:45", 11)
    private val day = listOf(a, b, c)

    @Test
    fun `时间字符串转分钟`() {
        assertEquals(480, minutesOf("08:00"))
        assertEquals(825, minutesOf("13:45"))
        assertNull(minutesOf(""))
        assertNull(minutesOf("abc"))
        assertNull(minutesOf("25:00"))
    }

    @Test
    fun `已经在上的课是进行中`() {
        // 08:40 落在 A 的 08:00-09:35 之间
        assertEquals(ClassState.RUNNING, stateOf(a, day, minutesOf("08:40")!!))
    }

    @Test
    fun `上完的课是已结束`() {
        assertEquals(ClassState.DONE, stateOf(a, day, minutesOf("12:00")!!))
    }

    @Test
    fun `只把最近的一节标成即将开始`() {
        val now = minutesOf("10:00")!!   // A 已结束，B 还没开始
        assertEquals(ClassState.NEXT, stateOf(b, day, now))
        assertEquals("后面的课不该也标即将开始", ClassState.UPCOMING, stateOf(c, day, now))
    }

    @Test
    fun `全部结束之后不再有下一节`() {
        val now = minutesOf("23:00")!!
        assertEquals(ClassState.DONE, stateOf(c, day, now))
    }

    @Test
    fun `时长文案超过一小时换成小时加分钟`() {
        assertEquals("5 分钟", durationText(5))
        assertEquals("1 小时", durationText(60))
        assertEquals("3 小时 57 分钟", durationText(237))
        assertEquals("马上就要", durationText(0))
    }
}
