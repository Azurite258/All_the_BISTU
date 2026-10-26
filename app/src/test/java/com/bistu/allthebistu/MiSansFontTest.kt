package com.bistu.allthebistu

import androidx.compose.ui.text.font.FontWeight
import com.bistu.allthebistu.ui.theme.isMiSansFileName
import com.bistu.allthebistu.ui.theme.isVariableFontFileName
import com.bistu.allthebistu.ui.theme.pickMiSansFonts
import com.bistu.allthebistu.ui.theme.weightOfFontFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MiSans 字体探测。
 *
 * 这套逻辑的风险点不在「找不找得到」，而在「找错」：
 *  - 把非字体文件当字体（`MiSans-Regular.xml` 是系统里的字体描述，不是字形）→ 加载必崩
 *  - 把 `SemiBold` 认成 `Bold` → 卡片标题和页面大标题变成同一档，Hierarchy 塌掉
 *  - 有可变字体却去用静态文件 → 少了中间字重，Medium 会退化成假加粗
 * 这三个都在下面的用例里钉死了。
 */
class MiSansFontTest {

    // ── 文件名识别 ──

    @Test
    fun `识别系统里常见的 MiSans 文件名`() {
        listOf(
            "MiSansVF.ttf",
            "MiSans-Regular.ttf",
            "MiSans-Medium.otf",
            "MiSans-Semibold.ttc"
        ).forEach { assertTrue(it, isMiSansFileName(it)) }
    }

    @Test
    fun `大小写不同也算 MiSans`() {
        assertTrue(isMiSansFileName("misansvf.ttf"))
        assertTrue(isMiSansFileName("MISANS-BOLD.TTF"))
    }

    @Test
    fun `非字体文件和别的字体都不算`() {
        assertFalse(isMiSansFileName("MiSans-Regular.xml"))
        assertFalse(isMiSansFileName("MiSans"))
        assertFalse(isMiSansFileName("Roboto-Regular.ttf"))
        assertFalse(isMiSansFileName("NotoSansCJK-Regular.ttc"))
        assertFalse(isMiSansFileName(""))
    }

    // ── 可变字体识别 ──

    @Test
    fun `三种可变字体命名都能认出来`() {
        assertTrue(isVariableFontFileName("MiSansVF.ttf"))
        assertTrue(isVariableFontFileName("MiSans-VF.ttf"))
        assertTrue(isVariableFontFileName("MiSansVariable.ttf"))
    }

    @Test
    fun `静态文件不会被当成可变字体`() {
        assertFalse(isVariableFontFileName("MiSans-Regular.ttf"))
        assertFalse(isVariableFontFileName("MiSans-Bold.ttf"))
    }

    // ── 挑选策略 ──

    @Test
    fun `没有 MiSans 就返回空`() {
        assertEquals(
            emptyList<String>(),
            pickMiSansFonts(listOf("Roboto-Regular.ttf", "NotoSansCJK.ttc"))
        )
        assertEquals(emptyList<String>(), pickMiSansFonts(emptyList()))
    }

    @Test
    fun `可变字体排在第一位`() {
        val picked = pickMiSansFonts(
            listOf(
                "Roboto-Regular.ttf",
                "MiSans-Regular.ttf",
                "MiSansVF.ttf",
                "MiSans-Bold.ttf"
            )
        )
        assertEquals("MiSansVF.ttf", picked.first())
    }

    @Test
    fun `有可变字体也不丢弃静态备胎`() {
        // VF 的 wght 轴在个别定制系统上设不进去，那时候唯一的退路就是静态文件
        val picked = pickMiSansFonts(
            listOf("MiSansVF.ttf", "MiSans-Regular.ttf", "MiSans-Bold.ttf")
        )
        assertTrue(picked.contains("MiSans-Regular.ttf"))
        assertTrue(picked.contains("MiSans-Bold.ttf"))
    }

    @Test
    fun `只有静态字体时按字重从细到粗排`() {
        val picked = pickMiSansFonts(
            listOf(
                "MiSans-Bold.ttf",
                "MiSans-Regular.ttf",
                "MiSans-Medium.ttf",
                "MiSans-Semibold.ttf"
            )
        )
        assertEquals(
            listOf(
                "MiSans-Regular.ttf",
                "MiSans-Medium.ttf",
                "MiSans-Semibold.ttf",
                "MiSans-Bold.ttf"
            ),
            picked
        )
    }

    @Test
    fun `可变字体在前静态按字重在后`() {
        val picked = pickMiSansFonts(
            listOf("MiSans-Bold.ttf", "MiSansVF.ttf", "MiSans-Regular.ttf")
        )
        assertEquals(
            listOf("MiSansVF.ttf", "MiSans-Regular.ttf", "MiSans-Bold.ttf"),
            picked
        )
    }

    @Test
    fun `重复文件只留一份`() {
        val picked = pickMiSansFonts(listOf("MiSans-Regular.ttf", "MiSans-Regular.ttf"))
        assertEquals(listOf("MiSans-Regular.ttf"), picked)
    }

    // ── 字重推断 ──

    @Test
    fun `半粗不会被认成粗`() {
        assertEquals(FontWeight.SemiBold, weightOfFontFileName("MiSans-Semibold.ttf"))
        assertEquals(FontWeight.SemiBold, weightOfFontFileName("MiSans-Demibold.ttf"))
        assertEquals(FontWeight.Bold, weightOfFontFileName("MiSans-Bold.ttf"))
    }

    @Test
    fun `极细不会被认成细`() {
        assertEquals(FontWeight.ExtraLight, weightOfFontFileName("MiSans-ExtraLight.ttf"))
        assertEquals(FontWeight.Light, weightOfFontFileName("MiSans-Light.ttf"))
        assertEquals(FontWeight.Light, weightOfFontFileName("MiSans-SemiLight.ttf"))
    }

    @Test
    fun `四档常用字重`() {
        assertEquals(FontWeight.Normal, weightOfFontFileName("MiSans-Regular.ttf"))
        assertEquals(FontWeight.Medium, weightOfFontFileName("MiSans-Medium.ttf"))
        assertEquals(FontWeight.SemiBold, weightOfFontFileName("MiSans-Semibold.ttf"))
        assertEquals(FontWeight.Bold, weightOfFontFileName("MiSans-Bold.ttf"))
    }

    @Test
    fun `认不出字重时退回常规`() {
        assertEquals(FontWeight.Normal, weightOfFontFileName("MiSansVF.ttf"))
        assertEquals(FontWeight.Normal, weightOfFontFileName("MiSans.ttf"))
    }

    @Test
    fun `四档字重严格递增`() {
        val weights = listOf(
            "MiSans-Regular.ttf",
            "MiSans-Medium.ttf",
            "MiSans-Semibold.ttf",
            "MiSans-Bold.ttf"
        ).map { weightOfFontFileName(it).weight }

        assertEquals(weights.sorted(), weights)
        assertEquals(weights.distinct().size, weights.size)
    }
}
