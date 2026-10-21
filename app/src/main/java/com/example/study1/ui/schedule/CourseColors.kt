package com.example.study1.ui.schedule

import androidx.compose.ui.graphics.Color
import com.example.study1.data.prefs.CourseColorScheme

/**
 * 课程配色方案。
 *
 * 三种方案：
 *   SCHOOL — 用教务系统返回的原始颜色（浅粉、浅黄、浅蓝等）
 *   SOFT   — 柔和马卡龙色，统一色系，更协调
 *   VIVID  — 高对比度，深色模式下也清晰
 */
object CourseColors {

    /** 柔和马卡龙配色（浅色） */
    private val SOFT_LIGHT = listOf(
        "#FFE0E6", "#FFF0CC", "#D9F0DC", "#D3EAFD",
        "#E8DDF7", "#FFE5D3", "#D6F0F0", "#F7E6D6"
    )

    /** 柔和马卡龙配色（深色） */
    private val SOFT_DARK = listOf(
        "#4A2E36", "#4A4028", "#2E4A34", "#2A3E52",
        "#3E3452", "#4A3628", "#264A4A", "#4A3A28"
    )

    /** 高对比配色（浅色） */
    private val VIVID_LIGHT = listOf(
        "#FF8A9B", "#FFC266", "#66D988", "#66B4FF",
        "#B88AFF", "#FF9E66", "#66D9D9", "#D9A566"
    )

    /** 高对比配色（深色） */
    private val VIVID_DARK = listOf(
        "#B84A5E", "#B88A2E", "#2E8A4A", "#2A6EB8",
        "#7A4AB8", "#B8662E", "#2E8A8A", "#8A6E2E"
    )

    /**
     * 根据方案和主题，计算课程块背景色。
     *
     * @param originalHex 教务系统返回的原始颜色
     * @param courseName  课程名（用于稳定分配柔和/高对比色，同名课程同色）
     * @param scheme      配色方案
     * @param isDark      是否深色模式
     */
    fun backgroundOf(
        originalHex: String,
        courseName: String,
        scheme: Int,
        isDark: Boolean
    ): Color {
        // 用课程名的哈希值选色，保证同名课程颜色一致
        val index = stableIndex(courseName)

        return when (scheme) {
            CourseColorScheme.SOFT -> {
                val palette = if (isDark) SOFT_DARK else SOFT_LIGHT
                parse(palette[index % palette.size])
            }

            CourseColorScheme.VIVID -> {
                val palette = if (isDark) VIVID_DARK else VIVID_LIGHT
                parse(palette[index % palette.size])
            }

            else -> { // SCHOOL
                val base = parse(originalHex)
                if (isDark) darken(base) else base
            }
        }
    }

    /** 把课程名映射到 [0, 1000) 的稳定索引 */
    private fun stableIndex(courseName: String): Int {
        if (courseName.isEmpty()) return 0
        // 简单稳定的哈希：逐字符累加，避免 hashCode 的负数问题
        var h = 0
        courseName.forEach { ch -> h = (h * 31 + ch.code) % 1000 }
        return (h + 1000) % 1000
    }

    /** 课程块上的文字颜色（根据背景亮度自动选择） */
    fun textColorOf(background: Color, isDark: Boolean): Color {
        // 计算相对亮度，决定用黑字还是白字
        val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
        return if (luminance > 0.6) Color(0xFF1A1A1A) else Color(0xFFFFFFFF)
    }

    /** 副文字颜色（教室、时间） */
    fun subTextColorOf(background: Color, isDark: Boolean): Color {
        val luminance = 0.299 * background.red + 0.587 * background.green + 0.114 * background.blue
        return if (luminance > 0.6) Color(0xFF555555) else Color(0xFFDDDDDD)
    }

    /** 深色模式下把浅色调暗 */
    private fun darken(color: Color, factor: Float = 0.35f): Color {
        return Color(
            red = (color.red * factor).coerceIn(0f, 1f),
            green = (color.green * factor).coerceIn(0f, 1f),
            blue = (color.blue * factor).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    private fun parse(hex: String): Color {
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrElse { Color(0xFFE0E0E0) }
    }
}
