package com.bistu.allthebistu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.bistu.allthebistu.data.prefs.ThemeMode

/**
 * HyperOS 风格配色方案。
 *
 * 设计要点：
 * - 浅色：浅灰底 + 纯白卡片，靠色差区分层次（不用阴影）
 * - 深色：纯黑底 + 深灰卡片（OLED 省电）
 * - 主色：HyperOS 蓝
 */
private val HyperLightColorScheme = lightColorScheme(
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

private val HyperDarkColorScheme = darkColorScheme(
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

/**
 * 应用主题（HyperOS 风格）。
 *
 * @param themeMode 0=跟随系统 1=浅色 2=深色
 * @param useDynamicColor 是否用 Material You 动态取色
 *        （默认关闭，因为动态取色会覆盖 HyperOS 配色，破坏风格统一）
 */
@Composable
fun Study1Theme(
    themeMode: Int = ThemeMode.SYSTEM,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    val colorScheme = when {
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> HyperDarkColorScheme
        else -> HyperLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = HyperShapes,
        content = content
    )
}
