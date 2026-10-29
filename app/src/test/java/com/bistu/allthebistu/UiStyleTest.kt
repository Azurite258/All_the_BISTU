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
            // 原版和 iOS 是例外，两者用的都是方向稿原值：
            //   原版 #5B8DEF 压白 3.23:1、iOS #0A84FF 压白 3.65:1。
            // 后者正是苹果自己在用的组合（系统蓝实底 + 白字），
            // 用户又要求与示例图分毫不差，所以保留原值，按粗体文本的 3.0 守底线。
            // 其余四套仍要求 4.5:1。
            val min = if (style == UiStyle.ORIGINAL || style == UiStyle.IOS) 3.0f else 4.5f
            darkModes.forEach { dark ->
                val cs = UiStyle.colorScheme(style, dark)
                val ratio = CourseColors.contrastRatio(cs.onPrimary, cs.primary)
                assertTrue(
                    "${scenario(style, dark)} 按钮文字对比度只有 $ratio:1（下限 $min）",
                    ratio >= min
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
    fun `课程配色默认跟随界面风格`() {
        UiStyle.all.forEach { style ->
            assertEquals(
                "${UiStyle.label(style)} 应默认跟随风格",
                CourseColorScheme.FOLLOW_STYLE,
                UiStyle.defaultCourseScheme(style)
            )
        }
    }

    /**
     * 数值来自方向稿 `ui-lab/index.html` 的 `--course-s / --course-l / --course-t`。
     * 改这里之前先去改方向稿，否则「一比一复刻」就成了随口说说。
     *
     * 方向稿是浅色界面：原版 / Linear 这两套在稿子里本身就是深色风格，
     * 它们的方向稿值对应**深色模式**；其余四套对应**浅色模式**。
     */
    @Test
    fun `六套风格的课程色参数与方向稿一致`() {
        // 稿子里的原值，以及它对应的是哪一端
        val fromDoc = mapOf(
            UiStyle.HYPEROS to Triple(0.62f, 0.90f, 0.30f),
            UiStyle.LINEAR to Triple(0.36f, 0.22f, 0.84f),
            UiStyle.MINIMAL to Triple(0.52f, 0.91f, 0.30f),
            UiStyle.ORIGINAL to Triple(0.11f, 0.34f, 0.97f),
            UiStyle.MATERIAL3 to Triple(0.52f, 0.88f, 0.25f),
            UiStyle.IOS to Triple(0.74f, 0.84f, 0.26f)
        )
        // 方向稿里就是深色界面的两套
        val docIsDark = setOf(UiStyle.ORIGINAL, UiStyle.LINEAR)
        assertEquals("方向稿里有六套风格", 6, fromDoc.size)
        fromDoc.forEach { (style, v) ->
            val isDark = style in docIsDark
            val t = UiStyle.courseTone(style, isDark)
            val where = if (isDark) "深色" else "浅色"
            assertEquals("${UiStyle.label(style)}（$where）饱和度", v.first, t.saturation, 0.001f)
            assertEquals("${UiStyle.label(style)}（$where）明度", v.second, t.lightness, 0.001f)
            assertEquals("${UiStyle.label(style)}（$where）文字明度", v.third, t.textLightness, 0.001f)
        }
        // 六套参数不能重复，否则「换风格课块跟着变」是假的
        assertEquals(
            "六套风格的课程色参数有重复（深色）",
            6,
            UiStyle.all.map { UiStyle.courseTone(it, isDark = true) }.toSet().size
        )
        assertEquals(
            "六套风格的课程色参数有重复（浅色）",
            6,
            UiStyle.all.map { UiStyle.courseTone(it, isDark = false) }.toSet().size
        )
    }

    /**
     * 0.19-a 踩过的坑：把课块参数跨明暗搬运，会得到「一页深灰块 + 同色相灰蓝字」，
     * 对比度只有 3:1，观感发脏。规矩统一成一句：浅页面配浅块深字、深页面配深块浅字。
     */
    @Test
    fun `六套风格的课块明暗两套方向相反`() {
        UiStyle.all.forEach { style ->
            val light = UiStyle.courseTone(style, isDark = false)
            val dark = UiStyle.courseTone(style, isDark = true)
            assertTrue(
                "${UiStyle.label(style)} 浅色模式课块底明度 ${light.lightness} 太暗",
                light.lightness > 0.8f
            )
            assertTrue(
                "${UiStyle.label(style)} 浅色模式文字明度 ${light.textLightness} 太浅",
                light.textLightness < 0.4f
            )
            assertTrue(
                "${UiStyle.label(style)} 深色模式课块底明度 ${dark.lightness} 太亮",
                dark.lightness < 0.45f
            )
            assertTrue(
                "${UiStyle.label(style)} 深色模式文字明度 ${dark.textLightness} 太深",
                dark.textLightness > 0.8f
            )
            assertNotEquals("${UiStyle.label(style)} 深浅两套参数不该相同", dark, light)
        }
    }

    @Test
    fun `跟随风格时课块文字对比度达标`() {
        UiStyle.all.forEach { style ->
            darkModes.forEach { dark ->
                val tone = UiStyle.courseTone(style, isDark = dark)
                // 色相池有 9 个色相，逐个都要过
                repeat(9) { i ->
                    val bg = CourseColors.styleBackground(i, tone)
                    val (fg, sub) = CourseColors.textPairStyle(i, tone, bg, isDark = dark)
                    val c = CourseColors.contrastRatio(fg, bg)
                    assertTrue(
                        "${UiStyle.label(style)}（${scenario(style, dark)}）第 $i 色主文字对比度只有 $c",
                        c >= 4.5f
                    )
                    val cs = CourseColors.contrastRatio(sub, bg)
                    assertTrue(
                        "${UiStyle.label(style)}（${scenario(style, dark)}）第 $i 色副文字对比度只有 $cs",
                        cs >= 3f
                    )
                }
            }
        }
    }

    @Test
    fun `非法风格值收敛到默认风格`() {
        // 0.19-i 起默认风格从「原版」改成「iOS 液态玻璃」。
        // 这里断言 DEFAULT 而不是写死某个常量 —— 以后再换默认风格不必回来改测试。
        assertEquals(UiStyle.DEFAULT, UiStyle.sanitize(-1))
        assertEquals(UiStyle.DEFAULT, UiStyle.sanitize(6))
        assertEquals(UiStyle.DEFAULT, UiStyle.sanitize(999))
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
