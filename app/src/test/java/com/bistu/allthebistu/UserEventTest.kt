package com.bistu.allthebistu

import com.bistu.allthebistu.data.event.EventTag
import com.bistu.allthebistu.data.event.Repeat
import com.bistu.allthebistu.data.event.UserEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 自定义日程的日期推导。
 *
 * 重点在 [UserEvent.occursOn]：重复规则一旦算错，用户会在「今天」看到
 * 昨天/下周的日程，或者该出现的没出现，而这种错误在界面上很难一眼看出来。
 */
class UserEventTest {

    /** 造一个指定年月日时分的时刻 */
    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun event(start: Long, repeat: Int = Repeat.NONE) = UserEvent(
        id = "t",
        title = "测试",
        startAt = start,
        endAt = start + 60 * 60_000L,
        repeat = repeat
    )

    @Test
    fun `永不：只落在开始那天`() {
        val e = event(at(2026, 10, 31, 9, 0))
        assertTrue(e.occursOn(at(2026, 10, 31, 20, 0)))
        assertFalse(e.occursOn(at(2026, 11, 1, 9, 0)))
        assertFalse(e.occursOn(at(2026, 10, 30, 9, 0)))
    }

    @Test
    fun `每天：当天及以后都命中`() {
        val e = event(at(2026, 10, 31, 9, 0), Repeat.DAILY)
        assertTrue(e.occursOn(at(2026, 10, 31, 23, 0)))
        assertTrue(e.occursOn(at(2027, 1, 5, 8, 0)))
        assertFalse(e.occursOn(at(2026, 10, 30, 8, 0)))
    }

    @Test
    fun `每周：只命中同一个星期几`() {
        // 2026-10-31 是周六
        val e = event(at(2026, 10, 31, 9, 0), Repeat.WEEKLY)
        assertTrue(e.occursOn(at(2026, 11, 7, 8, 0)))   // 下周六
        assertFalse(e.occursOn(at(2026, 11, 8, 8, 0)))  // 周日
        assertFalse(e.occursOn(at(2026, 10, 24, 8, 0))) // 开始之前
    }

    @Test
    fun `工作日：周末不出现`() {
        // 2026-10-30 是周五
        val e = event(at(2026, 10, 30, 9, 0), Repeat.WEEKDAY)
        assertTrue(e.occursOn(at(2026, 11, 2, 9, 0)))   // 下周一
        assertFalse(e.occursOn(at(2026, 10, 31, 9, 0))) // 周六
        assertFalse(e.occursOn(at(2026, 11, 1, 9, 0)))  // 周日
    }

    @Test
    fun `时刻格式化补零`() {
        assertEquals("08:05", UserEvent.clockOf(at(2026, 10, 31, 8, 5)))
        assertEquals("21:45", UserEvent.clockOf(at(2026, 10, 31, 21, 45)))
        assertEquals(8 * 60 + 5, UserEvent.minutesOf(at(2026, 10, 31, 8, 5)))
    }

    @Test
    fun `换日期保留时分`() {
        val target = at(2026, 10, 31, 14, 30)
        val picked = at(2026, 11, 5, 0, 0)
        val moved = UserEvent.withDateOf(target, picked)
        assertEquals("11-05 14:30", format(moved))
    }

    @Test
    fun `重复与标签越界时回落到默认值`() {
        assertEquals(0, Repeat.sanitize(-3))
        assertEquals(Repeat.WEEKDAY, Repeat.sanitize(99))
        assertEquals(EventTag.OTHER, EventTag.sanitize(99))
        assertEquals("永不", Repeat.nameOf(-1))
        assertEquals("其他", EventTag.nameOf(42))
    }

    private fun format(millis: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date(millis))
}
