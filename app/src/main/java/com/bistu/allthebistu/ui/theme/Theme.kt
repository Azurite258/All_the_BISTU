package com.bistu.allthebistu.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import com.bistu.allthebistu.data.prefs.ThemeMode

/**
 * 应用主题。
 *
 * 两个正交维度：
 * - [themeMode] 决定明暗（跟随系统 / 浅 / 深）
 * - [uiStyle] 决定配色、圆角、层次（六套风格，见 [UiStyle]）
 *
 * 六套风格共用同一套页面结构，只换令牌——换风格不改布局，
 * 这样切换时才不会出现某一套独有的错位。
 *
 * @param themeMode 0=跟随系统 1=浅色 2=深色
 * @param uiStyle 界面风格，见 [UiStyle]；非法值收敛到原版
 * @param useDynamicColor 是否用 Material You 动态取色
 *        （默认关闭：动态取色会把风格配色整个盖掉，六套风格就没意义了）
 */
@Composable
fun Study1Theme(
    themeMode: Int = ThemeMode.SYSTEM,
    uiStyle: Int = UiStyle.DEFAULT,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    val style = UiStyle.sanitize(uiStyle)

    val colorScheme = if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        UiStyle.colorScheme(style, darkTheme)
    }

    CompositionLocalProvider(
        LocalUiStyle provides style,
        LocalUiMetrics provides UiStyle.metrics(style)
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = UiStyle.shapes(style),
            content = content
        )
    }
}
