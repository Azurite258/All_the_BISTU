package com.bistu.allthebistu

import com.bistu.allthebistu.data.prefs.FontLevel
import com.bistu.allthebistu.ui.schedule.CourseTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表字号换算。
 *
 * 这里的核心不变量是：按某档字号排出来的课程名，
 * 一行必须真的放得下承诺的字数（5/4/3），
 * 否则设置里写「一行 5 个字」就成了假话。
 */
class CourseTypographyTest {

    /** 常见手机宽度，列宽 = (411 - 16*2 - 42) / 7 ≈ 48.1dp */
    private val columnWidth = CourseTypography.columnWidthDp(411f)

    @Test
    fun `三档对应的每行字数`() {
        assertEquals(5, CourseTypography.charsPerLine(FontLevel.SMALL))
        assertEquals(4, CourseTypography.charsPerLine(FontLevel.MEDIUM))
        assertEquals(3, CourseTypography.charsPerLine(FontLevel.LARGE))
    }

    @Test
    fun `每档字号都放得下承诺的字数`() {
        listOf(
            FontLevel.SMALL to 5,
            FontLevel.MEDIUM to 4,
            FontLevel.LARGE to 3
        ).forEach { (level, chars) ->
            val sp = CourseTypography.titleSizeSp(columnWidth, chars)
            val usable = columnWidth - CourseTypography.BLOCK_TEXT_INSET_DP
            assertTrue(
                "挡位 $level 一行 $chars 个字放不下：$sp sp × $chars > $usable dp",
                sp * chars <= usable
            )
        }
    }

    @Test
    fun `小档比中档小中档比大档小`() {
        val small = CourseTypography.titleSizeSp(columnWidth, 5)
        val medium = CourseTypography.titleSizeSp(columnWidth, 4)
        val large = CourseTypography.titleSizeSp(columnWidth, 3)
        assertTrue(small < medium)
        assertTrue(medium < large)
    }

    @Test
    fun `字号有上下限防止算出离谱的值`() {
        assertEquals(
            CourseTypography.MAX_TITLE_SP,
            CourseTypography.titleSizeSp(200f, 1),
            0.001f
        )
        assertEquals(
            CourseTypography.MIN_TITLE_SP,
            CourseTypography.titleSizeSp(8f, 5),
            0.001f
        )
    }

    @Test
    fun `脏数据不会把排版搞崩`() {
        assertEquals(FontLevel.SMALL, FontLevel.sanitize(-3))
        assertEquals(FontLevel.LARGE, FontLevel.sanitize(99))
        assertEquals(FontLevel.MEDIUM, FontLevel.sanitize(1))
    }

    @Test
    fun `列宽兜底不小于最小值`() {
        assertTrue(CourseTypography.columnWidthDp(200f) >= 24f)
    }
}
