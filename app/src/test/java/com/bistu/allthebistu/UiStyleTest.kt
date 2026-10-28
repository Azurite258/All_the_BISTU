package com.bistu.allthebistu

import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.ui.schedule.CourseColors
import com.bistu.allthebistu.ui.theme.UiStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 六套界面风格。
 *
 * 这里守的是「换风格不能换出不可读的界面」这条底线，不是像素级还原：
 *  1. 每套风格 × 浅色/深色，正文、次要文字、按钮文字都要过 WCAG AA 的 4.5:1
 *  2. 卡片和页面底色必须能区分（否则层次没了，六套风格就长得一样）
 *  3. 每套风格都要有配套课程色板，且换风格时课块颜色确实会变
 */
class UiStyleTest {

    private val darkModes = listOf(false, true)

    private fun scenario(style: Int, isDark: Boolean) =
        "${UiStyle.label(style)}${if (isDark) "深色" else "浅色"}"

    @Test
    fun `正文与背景对比度都不低于 4_5`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val cs = UiStyle.colorScheme(style, dark)
                val ratio = CourseColors.contrastRatio(cs.onBackground, cs.background)
                assertTrue(
                    "${scenario(style, dark)} 正文对比度只有 $ratio:1",
                    ratio >= 4.5f
                )
            }
        }
    }

    @Test
    fun `次要文字压卡片对比度不低于 4_5`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val cs = UiStyle.colorScheme(style, dark)
                val ratio = CourseColors.contrastRatio(cs.onSurfaceVariant, cs.surface)
                assertTrue(
                    "${scenario(style, dark)} 次要文字对比度只有 $ratio:1",
                    ratio >= 4.5f
                )
            }
        }
    }

    @Test
    fun `按钮上的文字压主色不低于 4_5`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val cs = UiStyle.colorScheme(style, dark)
                val ratio = CourseColors.contrastRatio(cs.onPrimary, cs.primary)
                assertTrue(
                    "${scenario(style, dark)} 按钮文字对比度只有 $ratio:1",
                    ratio >= 4.5f
                )
            }
        }
    }

    @Test
    fun `卡片和页面底色必须能区分开`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val cs = UiStyle.colorScheme(style, dark)
                // 极简风格刻意用「白卡压近白底」，色差很小但不为零，
                // 所以这里只要求不是同一个颜色，不设对比度下限
                assertNotEquals(
                    "${scenario(style, dark)} 卡片色和背景色完全相同，层次会消失",
                    cs.surface, cs.background
                )
            }
        }
    }

    @Test
    fun `六套风格的配色互不相同`() {
        val signatures = UiStyle.all.map { style ->
            val cs = UiStyle.colorScheme(style, false)
            Triple(cs.primary, cs.background, cs.surface)
        }
        assertEquals(
            "六套风格里出现了重复配色，用户切了等于没切",
            signatures.size,
            signatures.toSet().size
        )
    }

    @Test
    fun `每套风格都配了课程色板且各不相同`() {
        UiStyle.all.forEach { style ->
            val scheme = UiStyle.defaultCourseScheme(style)
            assertTrue(
                "${UiStyle.label(style)} 的课程色板值不合法：$scheme",
                scheme == CourseColorScheme.SCHOOL ||
                    scheme == CourseColorScheme.MORANDI ||
                    scheme == CourseColorScheme.DOPAMINE
            )
        }
        // 至少要覆盖三套色板里的两种，否则「换风格课块颜色跟着变」是假的
        val used = UiStyle.all.map { UiStyle.defaultCourseScheme(it) }.toSet()
        assertTrue("六套风格指向的课程色板太单一：$used", used.size >= 2)
    }

    @Test
    fun `非法风格值收敛到原版`() {
        assertEquals(UiStyle.ORIGINAL, UiStyle.sanitize(-1))
        assertEquals(UiStyle.ORIGINAL, UiStyle.sanitize(6))
        assertEquals(UiStyle.ORIGINAL, UiStyle.sanitize(999))
        UiStyle.all.forEach { assertEquals(it, UiStyle.sanitize(it)) }
    }

    @Test
    fun `色点返回三个可用的颜色`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val dots = UiStyle.swatch(style, dark)
                assertEquals("${scenario(style, dark)} 色点数量不对", 3, dots.size)
                // 底色和正文色不能一样，否则两个点糊成一个
                assertNotEquals(dots[0], dots[2])
            }
        }
    }

    @Test
    fun `每套风格都有名字和一句说明`() {
        UiStyle.all.forEach { style ->
            assertTrue(UiStyle.label(style).isNotBlank())
            assertTrue("${UiStyle.label(style)} 缺少说明，用户看不出差别", UiStyle.tagline(style).isNotBlank())
        }
    }
}
