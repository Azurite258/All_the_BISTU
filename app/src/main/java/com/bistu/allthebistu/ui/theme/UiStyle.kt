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
 * 六套风格共用同一套页面结构，**只换令牌**。这条是「让用户自选界面」能不能成立的判据：
 * 如果换风格需要改布局，那用户换一次就会遇到一次布局 Bug。
 */
object UiStyle {

    /** 原版：深色蓝调，也就是 0.16 及以前的外观。默认值，保证老用户不受影响 */
    const val ORIGINAL = 0

    /** HyperOS 澎湃：浅灰底 + 橙色强调 + 26dp 大圆角 */
    const val HYPEROS = 1

    /** Linear：冷调深色 + 靛蓝 + 10dp 小圆角 + 细边框 */
    const val LINEAR = 2

    /** 极简：近白、单色、无阴影、黑强调 */
    const val MINIMAL = 3

    /** Material 3：Google 基线紫 + tonal 卡片 + 16dp */
    const val MATERIAL3 = 4

    /** iOS 液态玻璃：浅底 + 28dp 大圆角 + 蓝强调，配合悬浮胶囊底栏 */
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
     * 换风格时课块默认跟着换成这一套；用户手动改过就按风格记住，见 SettingsStore。
     */
    fun defaultCourseScheme(style: Int): Int = when (style) {
        HYPEROS, MINIMAL, MATERIAL3 -> CourseColorScheme.MORANDI
        LINEAR, IOS -> CourseColorScheme.DOPAMINE
        else -> CourseColorScheme.SCHOOL
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

    /** 该风格下卡片是否靠阴影分层（极简 / M3 / iOS 不用阴影） */
    fun usesShadow(style: Int): Boolean = style == ORIGINAL || style == HYPEROS

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
 * 当前界面风格。放在 CompositionLocal 里，是因为课表网格这样的深层组件
 * 需要知道「今天高亮用什么色」，而它拿不到设置对象。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.DEFAULT }

// ═══════════════════════════════════════════════════════════════
// 六套配色（浅 / 深各一份）
// ═══════════════════════════════════════════════════════════════

/** 原版：沿用 0.16 的 HyperOS 配色，切过来外观完全不变 */
internal val OriginalLight = lightColorScheme(
    primary = HyperPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EFFF),
    onPrimaryContainer = HyperPrimaryDark,
    secondary = HyperPrimaryDark,
    onSecondary = Color.White,
    background = HyperBackground,
    onBackground = HyperOnBackground,
    surface = HyperSurface,
    onSurface = HyperOnBackground,
    surfaceVariant = HyperSurfaceVariant,
    onSurfaceVariant = HyperOnSurfaceVariant,
    outline = HyperOutline,
    outlineVariant = HyperOutline,
    error = HyperRed,
    onError = Color.White
)

internal val OriginalDark = darkColorScheme(
    primary = HyperPrimaryDarkMode,
    onPrimary = Color(0xFF00325C),
    primaryContainer = Color(0xFF1A3A5C),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = HyperPrimaryDarkMode,
    onSecondary = Color(0xFF00325C),
    background = HyperBackgroundDark,
    onBackground = HyperOnBackgroundDark,
    surface = HyperSurfaceDark,
    onSurface = HyperOnBackgroundDark,
    surfaceVariant = HyperSurfaceVariantDark,
    onSurfaceVariant = HyperOnSurfaceVariantDark,
    outline = HyperOutlineDark,
    outlineVariant = HyperOutlineDark,
    error = HyperRed,
    onError = Color.White
)

/** HyperOS 澎湃：橙色强调。按钮上压深色字——白字压 #FF6A00 只有 2.87:1，不合格 */
internal val HyperOsLight = lightColorScheme(
    primary = Color(0xFFFF6A00),
    onPrimary = Color(0xFF1A1206),
    primaryContainer = Color(0xFFFFE8D6),
    onPrimaryContainer = Color(0xFF7A3300),
    secondary = Color(0xFFD95500),
    onSecondary = Color.White,
    background = Color(0xFFF2F3F7),
    onBackground = Color(0xFF101114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101114),
    surfaceVariant = Color(0xFFEDEFF3),
    onSurfaceVariant = Color(0xFF63676E),
    outline = Color(0xFFDDE0E6),
    outlineVariant = Color(0xFFE6E8ED),
    error = Color(0xFFD93025),
    onError = Color.White
)

internal val HyperOsDark = darkColorScheme(
    primary = Color(0xFFFF8A3D),
    onPrimary = Color(0xFF2A1400),
    primaryContainer = Color(0xFF4A2400),
    onPrimaryContainer = Color(0xFFFFD9BC),
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

/** Linear：冷调深色系，靛蓝强调，小圆角 */
internal val LinearLight = lightColorScheme(
    primary = Color(0xFF4F58CC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E6FB),
    onPrimaryContainer = Color(0xFF232878),
    secondary = Color(0xFF5C63D6),
    onSecondary = Color.White,
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF14151F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14151F),
    surfaceVariant = Color(0xFFEEF0F4),
    onSurfaceVariant = Color(0xFF5C6070),
    outline = Color(0xFFE1E4EA),
    outlineVariant = Color(0xFFEAEDF1),
    error = Color(0xFFD93036),
    onError = Color.White
)

internal val LinearDark = darkColorScheme(
    primary = Color(0xFF8E97FF),
    onPrimary = Color(0xFF10123A),
    primaryContainer = Color(0xFF262A55),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFF7C87FF),
    onSecondary = Color(0xFF10123A),
    background = Color(0xFF0F1017),
    onBackground = Color(0xFFE9EAF2),
    surface = Color(0xFF1A1B26),
    onSurface = Color(0xFFE9EAF2),
    surfaceVariant = Color(0xFF242634),
    onSurfaceVariant = Color(0xFF9DA1B4),
    outline = Color(0xFF2E3040),
    outlineVariant = Color(0xFF272938),
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0A0A)
)

/** 极简：近白底、单色、无阴影。强调色就是黑，靠字重和留白建立层次 */
internal val MinimalLight = lightColorScheme(
    primary = Color(0xFF111111),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEDED),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF6B6B6B),
    outline = Color(0xFFE8E8E8),
    outlineVariant = Color(0xFFF0F0F0),
    error = Color(0xFFB3261E),
    onError = Color.White
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

/** Material 3 基线：紫 + tonal 卡片（靠色阶分层，不靠阴影） */
internal val M3Light = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFF3EDF7),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFCAC4D0),
    outlineVariant = Color(0xFFE7E0EC),
    error = Color(0xFFB3261E),
    onError = Color.White
)

internal val M3Dark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF211F26),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF49454F),
    outlineVariant = Color(0xFF3A363F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

/**
 * iOS 液态玻璃。
 *
 * primary 取 #0A6FE0 而不是 iOS 常用的 #0A84FF：后者压白只有 3.65:1，
 * 拿来做按钮文字或图标都不合格。#0A6FE0 压白 4.9:1，视觉上仍然是那个蓝。
 */
internal val IosLight = lightColorScheme(
    primary = Color(0xFF0A6FE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F0FF),
    onPrimaryContainer = Color(0xFF0A3D7A),
    secondary = Color(0xFF0A84FF),
    onSecondary = Color.White,
    background = Color(0xFFEDEFF5),
    onBackground = Color(0xFF0B0B0F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B0B0F),
    surfaceVariant = Color(0xFFE9EBF2),
    onSurfaceVariant = Color(0xFF585E66),
    outline = Color(0xFFD8DCE6),
    outlineVariant = Color(0xFFE4E7EE),
    error = Color(0xFFD70015),
    onError = Color.White
)

internal val IosDark = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF00224A),
    primaryContainer = Color(0xFF10305A),
    onPrimaryContainer = Color(0xFFD0E4FF),
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
