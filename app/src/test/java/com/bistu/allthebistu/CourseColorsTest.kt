package com.bistu.allthebistu

import androidx.compose.ui.graphics.Color
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.ui.schedule.CourseColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 课表配色。
 *
 * 这里守的是两条体验底线，不是像素级还原：
 *  1. 同一套色板里 8 个颜色两两看得出区别（否则两门课撞色就没法认）
 *  2. 课程块上的字和底色对比度够（WCAG AA 的 4.5:1）
 */
class CourseColorsTest {

    private val newSchemes = listOf(CourseColorScheme.MORANDI, CourseColorScheme.DOPAMINE)

    /** RGB 欧氏距离，够用且不引入额外依赖 */
    private fun distance(a: Color, b: Color): Float {
        val dr = (a.red - b.red) * 255f
        val dg = (a.green - b.green) * 255f
        val db = (a.blue - b.blue) * 255f
        return sqrt(dr * dr + dg * dg + db * db)
    }

    @Test
    fun `莫兰迪与多巴胺各有 8 色且两两可分辨`() {
        newSchemes.forEach { scheme ->
            listOf(false, true).forEach { isDark ->
                val colors = CourseColors.sampleSwatches(scheme, isDark, count = 8)
                assertEquals("scheme=$scheme 应取到完整色板", 8, colors.size)

                for (i in colors.indices) {
                    for (j in i + 1 until colors.size) {
                        val d = distance(colors[i], colors[j])
                        assertTrue(
                            "scheme=$scheme isDark=$isDark 第 $i 与第 $j 个颜色太接近（距离 $d）",
                            d >= 18f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `课程块文字对比度不低于 4_5`() {
        newSchemes.forEach { scheme ->
            listOf(false, true).forEach { isDark ->
                CourseColors.sampleSwatches(scheme, isDark, count = 8).forEachIndexed { i, bg ->
                    val text = CourseColors.textColorOf(bg, isDark)
                    val ratio = CourseColors.contrastRatio(bg, text)
                    assertTrue(
                        "scheme=$scheme isDark=$isDark 第 $i 块对比度只有 $ratio",
                        ratio >= 4.5f
                    )
                }
            }
        }
    }

    @Test
    fun `同一门课在任何时候都是同一个颜色`() {
        listOf(CourseColorScheme.MORANDI, CourseColorScheme.DOPAMINE).forEach { scheme ->
            val first = CourseColors.backgroundOf("#FFFFFF", "高等数学(1)", scheme, false)
            val again = CourseColors.backgroundOf("#FFFFFF", "高等数学(1)", scheme, false)
            assertEquals(first, again)
        }
    }

    @Test
    fun `不同课程能分到不同颜色`() {
        val names = listOf(
            "高等数学(1)", "基础英语", "大学体育(1)", "人工智能导论",
            "现代前沿传感技术", "思想道德与法治", "C语言编程实战A", "大学生心理健康"
        )
        val index = CourseColors.paletteIndexFor(names)
        assertEquals("索引表要覆盖全部课程", 8, index.size)

        newSchemes.forEach { scheme ->
            val colors = names.map {
                CourseColors.backgroundOf("#CCCCCC", it, scheme, false, index[it])
            }
            assertEquals(
                "scheme=$scheme 8 门课应当拿到 8 种颜色",
                8, colors.distinct().size
            )
        }
    }

    @Test
    fun `同一门课在两次分配中索引一致`() {
        val a = CourseColors.paletteIndexFor(listOf("课A", "课B", "课C"))
        val b = CourseColors.paletteIndexFor(listOf("课C", "课A", "课B"))
        assertEquals(a, b)
    }

    @Test
    fun `学校配色仍然使用教务系统给的颜色`() {
        val c = CourseColors.backgroundOf("#FFE0E6", "任意课", CourseColorScheme.SCHOOL, false)
        assertEquals(0xFF, (c.red * 255).toInt())
        assertEquals(0xE0, (c.green * 255).toInt())
        assertEquals(0xE6, (c.blue * 255).toInt())
    }
}
