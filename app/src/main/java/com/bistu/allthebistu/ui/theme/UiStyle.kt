package com.bistu.allthebistu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bistu.allthebistu.data.prefs.CourseColorScheme

/**
 * 界面风格。
 *
 * 和「主题模式」（跟随系统 / 浅色 / 深色）是两个正交的维度：
 * 主题模式决定明暗，界面风格决定配色、圆角、层次手段。所以每种风格都要有
 * 浅色和深色两份 ColorScheme。
 *
 * 六套风格的**浅色**取值逐项照抄方向稿 `ui-lab/index.html` 的主题令牌
 * （`--bg` / `--surface` / `--fg` / `--fg-2` / `--line` / `--accent` / `--accent-soft` …），
 * 深色是把同一套色相压到深底上重新配的（方向稿只给了浅色）。
 */
object UiStyle {

    /** 原版：深色蓝调，也就是 0.16 及以前的外观。默认值 */
    const val ORIGINAL = 0

    /** HyperOS 澎湃：浅灰底 + 橙色强调 + 26dp 大圆角 */
    const val HYPEROS = 1

    /** Linear：冷调 + 靛蓝 + 10dp 小圆角 + 细边框 */
    const val LINEAR = 2

    /** 极简：近白、单色、无阴影、黑强调 */
    const val MINIMAL = 3

    /** Material 3：Google 基线紫 + tonal 卡片 + 16dp */
    const val MATERIAL3 = 4

    /** iOS 液态玻璃：浅底 + 28dp 大圆角 + 蓝强调 + 悬浮胶囊底栏 */
    const val IOS = 5

    const val DEFAULT = ORIGINAL

    val all = listOf(ORIGINAL, HYPEROS, LINEAR, MINIMAL, MATERIAL3, IOS)

    fun sanitize(value: Int): Int = if (all.contains(value)) value else DEFAULT

    fun label(value: Int): String = when (value) {
        HYPEROS -> "HyperOS 澎湃"
        LINEAR -> "Linear"
        MINIMAL -> "极简浅色"
        MATERIAL3 -> "Material 3"
        IOS -> "iOS 液态玻璃"
        else -> "原版"
    }

    /** 设置页里那句短说明，用户靠它区分风格，不靠名字 */
    fun tagline(value: Int): String = when (value) {
        HYPEROS -> "橙色 · 圆润 · 大圆角"
        LINEAR -> "深色 · 紧凑 · 细边框"
        MINIMAL -> "近白 · 单色 · 无阴影"
        MATERIAL3 -> "紫色 · tonal 卡片"
        IOS -> "半透明 · 悬浮胶囊"
        else -> "深色 · 蓝色强调"
    }

    /**
     * 每种风格配套的课程配色。
     *
     * 现在六套风格都走「跟随风格」——课块颜色由 [courseTone] 的 HSL 参数实时算。
     * 用户显式选了学校 / 莫兰迪 / 多巴胺时才不跟随，见 SettingsStore。
     */
    fun defaultCourseScheme(@Suppress("UNUSED_PARAMETER") style: Int): Int =
        CourseColorScheme.FOLLOW_STYLE

    /**
     * 课程块的颜色参数，**逐项照抄方向稿 `ui-lab/index.html` 的 `--course-l/s/t`**。
     *
     * 背景 = `hsl(h, s, l)`，文字 = `hsl(h, 48%, t)`，描边 = `hsl(h, 40%, t / .13)`。
     * 色相 h 由课程序号从固定色相池里取，见 CourseColors.COURSE_HUES。
     *
     * 注意这里**不区分明暗**：方向稿里每套风格就是一套固定的课块观感，
     * 跟着 themeMode 变会让「切风格」的辨识度被稀释。
     */
    fun courseTone(style: Int): CourseTone = when (style) {
        HYPEROS -> CourseTone(saturation = 0.62f, lightness = 0.90f, textLightness = 0.30f)
        LINEAR -> CourseTone(saturation = 0.36f, lightness = 0.22f, textLightness = 0.84f)
        MINIMAL -> CourseTone(saturation = 0.52f, lightness = 0.91f, textLightness = 0.30f)
        MATERIAL3 -> CourseTone(saturation = 0.52f, lightness = 0.88f, textLightness = 0.25f)
        IOS -> CourseTone(saturation = 0.74f, lightness = 0.84f, textLightness = 0.26f)
        else -> CourseTone(saturation = 0.11f, lightness = 0.34f, textLightness = 0.97f)
    }

    /**
     * 强调色的「填充版」`--accent`：用于按钮底、指示块、进度条。
     * 直接当文字色会掉到 3.x:1（`#FF6A00` 压白只有 2.87:1），文字请用 [accentText]。
     */
    fun accent(style: Int, isDark: Boolean): Color = colorScheme(style, isDark).primary

    /** 强调色的「文字版」`--accent-text`：小字和图标专用，保证 ≥4.5:1 */
    fun accentText(style: Int, isDark: Boolean): Color = when (style) {
        HYPEROS -> if (isDark) Color(0xFFFFB067) else Color(0xFFB34700)
        LINEAR -> if (isDark) Color(0xFF7C87FF) else Color(0xFF4F58CC)
        MINIMAL -> if (isDark) Color(0xFFEDEDED) else Color(0xFF000000)
        MATERIAL3 -> if (isDark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
        IOS -> if (isDark) Color(0xFF4DA3FF) else Color(0xFF0A63D8)
        else -> if (isDark) Color(0xFF7FA8FF) else Color(0xFF2A6FE0)
    }

    /** 强调色的淡底 `--accent-soft`：选中态、圆钮底色 */
    fun accentSoft(style: Int, isDark: Boolean): Color = when (style) {
        HYPEROS -> if (isDark) Color(0xFF3A2410) else Color(0xFFFFF0E5)
        LINEAR -> if (isDark) Color(0xFF2A2B44) else Color(0xFFE4E6FB)
        MINIMAL -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFEDEDED)
        MATERIAL3 -> if (isDark) Color(0xFF3B2E4F) else Color(0xFFEADDFF)
        IOS -> if (isDark) Color(0x380A84FF) else Color(0x210A84FF)
        else -> if (isDark) Color(0xFF22304F) else Color(0xFFE3EFFF)
    }

    /** 课表「今天」那一列的高亮底色 */
    fun todayBg(style: Int, isDark: Boolean): Color = when (style) {
        HYPEROS -> if (isDark) Color(0xFF3A2410) else Color(0xFFFFE9D9)
        LINEAR -> if (isDark) Color(0xFF23263F) else Color(0xFFE7E9FB)
        MINIMAL -> if (isDark) Color(0xFF2A2A2A) else Color(0xFFEDEDED)
        MATERIAL3 -> if (isDark) Color(0xFF3B2E4F) else Color(0xFFEADDFF)
        IOS -> if (isDark) Color(0xFF10243D) else Color(0xFFDDEAFF)
        else -> if (isDark) Color(0xFF2A2618) else Color(0xFFFFF8E1)
    }

    /** 课表「今天」那一列的文字色 */
    fun todayText(style: Int, isDark: Boolean): Color = when (style) {
        HYPEROS -> if (isDark) Color(0xFFFFB067) else Color(0xFFB34700)
        LINEAR -> if (isDark) Color(0xFF9AA3FF) else Color(0xFF4F58CC)
        MINIMAL -> if (isDark) Color(0xFFEDEDED) else Color(0xFF111111)
        MATERIAL3 -> if (isDark) Color(0xFFD0BCFF) else Color(0xFF5C3F9E)
        IOS -> if (isDark) Color(0xFF6FB4FF) else Color(0xFF0A63D8)
        else -> if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
    }

    /** 形态令牌（圆角 / 间距 / 字号 / 底栏 / 卡片分层），见 [UiMetrics] */
    fun metrics(style: Int): UiMetrics = metricsOf(sanitize(style))

    /**
     * 设置页里的三色点：底色 / 强调 / 正文。
     *
     * 光写风格名用户看不出差别，颜色比文字快——这是选界面唯一有效的视觉线索。
     */
    fun swatch(style: Int, isDark: Boolean): List<Color> {
        val cs = colorScheme(style, isDark)
        return listOf(cs.background, cs.primary, cs.onBackground)
    }

    internal fun colorScheme(style: Int, isDark: Boolean) = when (style) {
        HYPEROS -> if (isDark) HyperOsDark else HyperOsLight
        LINEAR -> if (isDark) LinearDark else LinearLight
        MINIMAL -> if (isDark) MinimalDark else MinimalLight
        MATERIAL3 -> if (isDark) M3Dark else M3Light
        IOS -> if (isDark) IosDark else IosLight
        else -> if (isDark) OriginalDark else OriginalLight
    }

    internal fun shapes(style: Int): Shapes = when (style) {
        HYPEROS -> Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(22.dp),
            extraLarge = RoundedCornerShape(26.dp)
        )
        LINEAR -> Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(10.dp),
            extraLarge = RoundedCornerShape(14.dp)
        )
        MINIMAL -> Shapes(
            extraSmall = RoundedCornerShape(5.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp)
        )
        MATERIAL3 -> Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(22.dp)
        )
        IOS -> Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        )
        else -> HyperShapes
    }
}

/**
 * 课程块的一组 HSL 参数。
 *
 * @param saturation 背景饱和度 0..1（方向稿 `--course-s`）
 * @param lightness  背景明度 0..1（`--course-l`）
 * @param textLightness 文字明度 0..1（`--course-t`），文字饱和度固定 48%
 */
data class CourseTone(
    val saturation: Float,
    val lightness: Float,
    val textLightness: Float
)

/**
 * 当前界面风格。放在 CompositionLocal 里，是因为课表网格这样的深层组件
 * 需要知道「今天高亮用什么色」，而它拿不到设置对象。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.DEFAULT }

// ═══════════════════════════════════════════════════════════════
// 十二份配色（六套 × 浅/深）
//
// 浅色逐项照抄方向稿的 CSS 变量，映射关系：
//   --bg → background        --surface → surface      --surface-2 → surfaceVariant
//   --fg → onBackground/onSurface   --fg-2 → onSurfaceVariant
//   --line → outline         --accent → primary       --accent-soft → primaryContainer
//   --accent-text → onPrimaryContainer   --accent-fg → onPrimary
// 极简的卡片用的是 `--surface-2`（比页面更白），所以这里把 surface 直接设成 #FFFFFF。
// ═══════════════════════════════════════════════════════════════

/** 原版（方向稿是深色主题，浅色版按同一色相提亮） */
internal val OriginalLight = lightColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3EFFF),
    onPrimaryContainer = Color(0xFF2A6FE0),
    secondary = Color(0xFF2A6FE0),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF101114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101114),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF63676E),
    outline = Color(0xFFE2E5EC),
    outlineVariant = Color(0xFFEAEDF2),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF)
)

internal val OriginalDark = darkColorScheme(
    primary = Color(0xFF5B8DEF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF22304F),
    onPrimaryContainer = Color(0xFF7FA8FF),
    secondary = Color(0xFF7FA8FF),
    onSecondary = Color(0xFF0E0E10),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1B1B1D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF242427),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFF2E2E33),
    outlineVariant = Color(0xFF26262A),
    error = Color(0xFFFF6B60),
    onError = Color(0xFF2A0503)
)

/** HyperOS 澎湃：橙色强调。按钮上压深色字——白字压 #FF6A00 只有 2.87:1，不合格 */
internal val HyperOsLight = lightColorScheme(
    primary = Color(0xFFFF6A00),
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFFFFF0E5),
    onPrimaryContainer = Color(0xFFB34700),
    secondary = Color(0xFFD95500),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF2F3F7),
    onBackground = Color(0xFF101114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101114),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF63676E),
    outline = Color(0xFFE6E8EF),
    outlineVariant = Color(0xFFEDEFF3),
    error = Color(0xFFD93025),
    onError = Color(0xFFFFFFFF)
)

internal val HyperOsDark = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF2A1400),
    primaryContainer = Color(0xFF3A2410),
    onPrimaryContainer = Color(0xFFFFB067),
    secondary = Color(0xFFFFB067),
    onSecondary = Color(0xFF2A1400),
    background = Color(0xFF0E0E10),
    onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF1B1B1D),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF26262A),
    onSurfaceVariant = Color(0xFFA0A3AA),
    outline = Color(0xFF33333A),
    outlineVariant = Color(0xFF2A2A30),
    error = Color(0xFFFF6B60),
    onError = Color(0xFF2A0503)
)

/** Linear：冷调深色系，靛蓝强调，小圆角（浅色版按同一色相提亮） */
internal val LinearLight = lightColorScheme(
    primary = Color(0xFF6B77FF),
    // 白字压 #6B77FF 只有 3.68:1；方向稿里 Linear 从不用白字压实底
    // （选中态是淡底 + 强调色字），所以实底按钮改用深靛蓝字
    onPrimary = Color(0xFF10123A),
    primaryContainer = Color(0xFFE4E6FB),
    onPrimaryContainer = Color(0xFF4F58CC),
    secondary = Color(0xFF4F58CC),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14151F),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5C6070),
    outline = Color(0xFFE1E4EA),
    outlineVariant = Color(0xFFEAEDF1),
    error = Color(0xFFD93036),
    onError = Color(0xFFFFFFFF)
)

internal val LinearDark = darkColorScheme(
    primary = Color(0xFF6B77FF),
    onPrimary = Color(0xFF10123A),
    primaryContainer = Color(0xFF2A2B44),
    onPrimaryContainer = Color(0xFF7C87FF),
    secondary = Color(0xFF7C87FF),
    onSecondary = Color(0xFF10123A),
    background = Color(0xFF191A24),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1F202D),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF272A3A),
    onSurfaceVariant = Color(0xFF9B9EAB),
    outline = Color(0xFF38394C),
    outlineVariant = Color(0xFF2E3040),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0A0A)
)

/** 极简：页面 #F5F5F5、卡片 #FFFFFF、单色黑强调，靠描边与色阶分层 */
internal val MinimalLight = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF2E3034),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF2E3034),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF2E3034),
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = Color(0xFF707070),
    outline = Color(0xFFD9D9D9),
    outlineVariant = Color(0xFFE4E4E4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

internal val MinimalDark = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2A2A2A),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF111111),
    background = Color(0xFF0C0C0C),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF191919),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFF9A9A9A),
    outline = Color(0xFF2E2E2E),
    outlineVariant = Color(0xFF262626),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0A0A)
)

/** Material 3 基线：紫 + tonal 卡片（靠色阶分层，无阴影无描边） */
internal val M3Light = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF6750A4),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFF3EDF7),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFFFFBFE),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE7E0EC),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

internal val M3Dark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF3B2E4F),
    onPrimaryContainer = Color(0xFFD0BCFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF211F26),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF2B2830),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF49454F),
    outlineVariant = Color(0xFF3A363F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

/**
 * iOS 液态玻璃。
 *
 * `--accent` 是 #0A84FF，但**文字**必须用 `--accent-text` #0A63D8：
 * 前者压白只有 3.65:1，当按钮文字或图标都不合格。
 * surface 是半透明白（方向稿 `rgba(255,255,255,.55)`），让底下的彩色光晕透上来。
 */
internal val IosLight = lightColorScheme(
    primary = Color(0xFF0A84FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0x210A84FF),
    onPrimaryContainer = Color(0xFF0A63D8),
    secondary = Color(0xFF0A63D8),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFEDEFF5),
    onBackground = Color(0xFF0B0B0F),
    surface = Color(0x8CFFFFFF),
    onSurface = Color(0xFF0B0B0F),
    surfaceVariant = Color(0x61FFFFFF),
    onSurfaceVariant = Color(0xFF585E66),
    outline = Color(0x1A0B0B0F),
    outlineVariant = Color(0x120B0B0F),
    error = Color(0xFFD70015),
    onError = Color(0xFFFFFFFF)
)

internal val IosDark = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00224A),
    primaryContainer = Color(0x380A84FF),
    onPrimaryContainer = Color(0xFF4DA3FF),
    secondary = Color(0xFF6FB4FF),
    onSecondary = Color(0xFF00224A),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF2A2A2E),
    onSurfaceVariant = Color(0xFFA5A8B0),
    outline = Color(0xFF38383E),
    outlineVariant = Color(0xFF2E2E33),
    error = Color(0xFFFF6961),
    onError = Color(0xFF3A0503)
)
