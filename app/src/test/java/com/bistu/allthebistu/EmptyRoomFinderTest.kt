package com.bistu.allthebistu

import com.bistu.allthebistu.data.classroom.EmptyRoomFinder
import com.bistu.allthebistu.data.classroom.EmptyRoomFinder.RoomLesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空教室反查。
 *
 * 这个功能的数据源是「教室课表」（每间教室什么时候有课），
 * 「哪些教室空着」要靠差集算出来——算错了用户走到教室门口才发现有人上课，
 * 比不显示更糟。所以边界逐条钉死：区间相邻、周次不符、单双周、没排课的教室。
 */
class EmptyRoomFinderTest {

    private fun lesson(
        room: String,
        building: String = "文理A",
        day: Int = 3,
        begin: Int,
        end: Int,
        weeks: Set<Int> = (1..16).toSet(),
    ) = RoomLesson(room, building, day, begin, end, weeks)

    // ── 区间相交 ──────────────────────────────────────────

    @Test
    fun `完全重合算占用`() {
        val l = lesson("A101", begin = 3, end = 4)
        assertTrue(l.occupies(week = 5, day = 3, from = 3, to = 4))
    }

    @Test
    fun `包含关系算占用`() {
        val l = lesson("A101", begin = 1, end = 4)
        assertTrue(l.occupies(week = 5, day = 3, from = 2, to = 3))
    }

    @Test
    fun `查询区间更宽也算占用`() {
        val l = lesson("A101", begin = 3, end = 4)
        assertTrue(l.occupies(week = 5, day = 3, from = 1, to = 8))
    }

    @Test
    fun `相邻节次不算占用`() {
        // 课程占 1-2，查 3-4：中间没有重叠，教室是空的
        val l = lesson("A101", begin = 1, end = 2)
        assertFalse(l.occupies(week = 5, day = 3, from = 3, to = 4))
    }

    @Test
    fun `反向相邻也不算占用`() {
        val l = lesson("A101", begin = 5, end = 6)
        assertFalse(l.occupies(week = 5, day = 3, from = 3, to = 4))
    }

    @Test
    fun `跨节次的大课会挡住中间任意一小段`() {
        val l = lesson("A101", begin = 3, end = 8)
        assertTrue(l.occupies(week = 5, day = 3, from = 5, to = 6))
    }

    // ── 星期 / 周次 ───────────────────────────────────────

    @Test
    fun `别的星期不影响`() {
        val l = lesson("A101", day = 2, begin = 3, end = 4)
        assertFalse(l.occupies(week = 5, day = 3, from = 3, to = 4))
    }

    @Test
    fun `不在这周的课不算占用`() {
        val l = lesson("A101", begin = 3, end = 4, weeks = setOf(1, 2, 3))
        assertFalse(l.occupies(week = 9, day = 3, from = 3, to = 4))
    }

    @Test
    fun `这周的课算占用`() {
        val l = lesson("A101", begin = 3, end = 4, weeks = setOf(1, 2, 3))
        assertTrue(l.occupies(week = 2, day = 3, from = 3, to = 4))
    }

    // ── 差集 ─────────────────────────────────────────────

    @Test
    fun `空教室 = 全部教室 减去 这个时段有课的`() {
        val lessons = listOf(
            lesson("A101", begin = 3, end = 4),
            lesson("A102", begin = 5, end = 6),   // 不冲突
        )
        val all = listOf("A101", "A102", "A103")

        val empty = EmptyRoomFinder.findEmpty(
            lessons = lessons, allRooms = all,
            week = 5, dayOfWeek = 3, fromSection = 3, toSection = 4
        )

        assertEquals(listOf("A102", "A103"), empty)
    }

    @Test
    fun `一节课都没排的教室必须出现在结果里`() {
        // 课表里根本没有 A103 的记录，只从 lessons 取名字就会漏掉它
        val empty = EmptyRoomFinder.findEmpty(
            lessons = listOf(lesson("A101", begin = 1, end = 2)),
            allRooms = listOf("A101", "A103"),
            week = 5, dayOfWeek = 3, fromSection = 1, toSection = 2
        )
        assertEquals(listOf("A103"), empty)
    }

    @Test
    fun `全都有课时结果为空`() {
        val empty = EmptyRoomFinder.findEmpty(
            lessons = listOf(
                lesson("A101", begin = 3, end = 4),
                lesson("A102", begin = 3, end = 4),
            ),
            allRooms = listOf("A101", "A102"),
            week = 5, dayOfWeek = 3, fromSection = 3, toSection = 4
        )
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `起始节大于结束节时返回空而不是崩`() {
        val empty = EmptyRoomFinder.findEmpty(
            lessons = emptyList(), allRooms = listOf("A101"),
            week = 5, dayOfWeek = 3, fromSection = 6, toSection = 3
        )
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `教室名去重且保持稳定排序`() {
        val empty = EmptyRoomFinder.findEmpty(
            lessons = emptyList(),
            allRooms = listOf("A-10", "A-9", "A-10", "A-2"),
            week = 5, dayOfWeek = 3, fromSection = 1, toSection = 2
        )
        assertEquals(listOf("A-2", "A-9", "A-10"), empty)
    }

    // ── 排序 ─────────────────────────────────────────────

    @Test
    fun `教室号按数值排序而不是字典序`() {
        // 字典序会给出 A-10 < A-9，那是错的
        val sorted = listOf("A-101", "A-9", "A-10", "A-100")
            .sortedWith(EmptyRoomFinder::compareRooms)
        assertEquals(listOf("A-9", "A-10", "A-100", "A-101"), sorted)
    }

    @Test
    fun `带楼栋前缀的教室名也能排`() {
        val sorted = listOf("文理A-102", "文理A-2", "信息楼B-11")
            .sortedWith(EmptyRoomFinder::compareRooms)
        assertEquals(listOf("信息楼B-11", "文理A-2", "文理A-102"), sorted)
    }

    // ── 周次解析 ─────────────────────────────────────────

    @Test
    fun `连续周次`() {
        assertEquals((1..16).toSet(), EmptyRoomFinder.parseWeeks("1-16"))
    }

    @Test
    fun `带周的写法`() {
        assertEquals((1..8).toSet(), EmptyRoomFinder.parseWeeks("1-8周"))
    }

    @Test
    fun `跳段周次`() {
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 11, 12, 13, 14, 15, 16),
            EmptyRoomFinder.parseWeeks("1-8,10-16"))
    }

    @Test
    fun `单周只留奇数周`() {
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), EmptyRoomFinder.parseWeeks("1-16(单)"))
    }

    @Test
    fun `双周只留偶数周`() {
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), EmptyRoomFinder.parseWeeks("1-16(双)"))
    }

    @Test
    fun `中文括号和逗号也能认`() {
        assertEquals((1..4).toSet() + setOf(6, 7),
            EmptyRoomFinder.parseWeeks("1-4，6-7周"))
    }

    @Test
    fun `单个周次`() {
        assertEquals(setOf(5), EmptyRoomFinder.parseWeeks("5"))
    }

    @Test
    fun `空串解析成空集合而不是每周`() {
        // 宁可少算一间空教室，也不能把有课的教室说成空的
        assertTrue(EmptyRoomFinder.parseWeeks("").isEmpty())
    }

    @Test
    fun `乱码解析成空集合而不是崩`() {
        assertTrue(EmptyRoomFinder.parseWeeks("第几周来着").isEmpty())
    }

    @Test
    fun `带讲授后缀的周次文本也能认`() {
        // 真实数据："3-12周[讲授]/某某[主讲]"
        assertEquals((3..12).toSet(), EmptyRoomFinder.parseWeeks("3-12周[讲授]/某某[主讲]"))
    }

    // ── 位图周次（这套系统的真实格式）─────────────────────
    //
    // 实测 `getMyScheduleDetail` 的 week 字段是 "001111111111" 这种位图：
    // 下标 = 周次，1 = 当周有课。以前只认 "1-16周" 文本，
    // 会把位图读成「第 1111111111 周」，于是所有教室都被判成空的。

    @Test
    fun `位图周次`() {
        assertEquals((3..12).toSet(), EmptyRoomFinder.parseWeeks("001111111111"))
    }

    @Test
    fun `长位图`() {
        assertEquals(setOf(1, 2), EmptyRoomFinder.parseWeeks("1100000000000000000"))
    }

    @Test
    fun `七位位图`() {
        assertEquals((1..7).toSet(), EmptyRoomFinder.parseWeeks("1111111"))
    }

    @Test
    fun `单个数字不会被误判成位图`() {
        assertEquals(setOf(1), EmptyRoomFinder.parseWeeks("1"))
    }

    @Test
    fun `全零位图表示这学期不上`() {
        assertTrue(EmptyRoomFinder.parseWeeks("000000000000").isEmpty())
    }

    @Test
    fun `位图周次按周命中`() {
        val l = lesson("A101", begin = 3, end = 4, weeks = EmptyRoomFinder.parseWeeks("001111111111"))
        assertFalse(l.occupies(week = 2, day = 3, from = 3, to = 4))
        assertTrue(l.occupies(week = 3, day = 3, from = 3, to = 4))
        assertTrue(l.occupies(week = 12, day = 3, from = 3, to = 4))
        assertFalse(l.occupies(week = 13, day = 3, from = 3, to = 4))
    }
}
