package com.bistu.allthebistu

import com.bistu.allthebistu.ui.schedule.formatWeeks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课程详情弹窗里的周次文本格式化。
 *
 * 课程块上的周次是「一堆数字」，直接丢给用户会变成
 * 「3、4、5、6、7、8、9、10、11、12 周」，太长且不好读；
 * 这里验证压缩成「3-12 周」这类区间的逻辑。
 */
class WeekFormatTest {

    @Test
    fun `连续周次压缩成区间`() {
        assertEquals("3-12 周", formatWeeks((3..12).toSet()))
    }

    @Test
    fun `单周不写成区间`() {
        assertEquals("7 周", formatWeeks(setOf(7)))
    }

    @Test
    fun `多段区间用顿号连接`() {
        assertEquals("3-5、7、9-11 周", formatWeeks(setOf(3, 4, 5, 7, 9, 10, 11)))
    }

    @Test
    fun `乱序输入也能正确排序`() {
        assertEquals("1-3、5 周", formatWeeks(setOf(5, 2, 1, 3)))
    }

    @Test
    fun `空集合返回未安排`() {
        assertEquals("未安排", formatWeeks(emptySet()))
    }

    @Test
    fun `单双周跳号不会被错误合并`() {
        assertEquals("1-15 周", formatWeeks((1..15).toSet()))
        assertEquals("1、3、5 周", formatWeeks(setOf(1, 3, 5)))
    }
}
