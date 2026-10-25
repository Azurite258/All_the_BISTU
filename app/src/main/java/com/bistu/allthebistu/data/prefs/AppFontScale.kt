package com.bistu.allthebistu.data.prefs

/**
 * 把「字体档位」翻译成整个 App 的 fontScale 倍率。
 *
 * 和 [FontLevel] 是一对：FontLevel 表达的是**排版结果**（课名一行放几个字），
 * 这里表达的是**缩放系数**（喂给 CompositionLocal 的 LocalDensity）。
 *
 * 数值刻意保守（0.94 / 1.0 / 1.12）：Material3 有大量固定 dp 高度的容器
 * （按钮、列表行、底部导航），字体放大超过 1.12 之后「字撑破容器」会变成常态。
 * 想让字更大应该改布局，而不是继续加大这个系数。
 */
object AppFontScale {

    fun of(level: Int): Float = when (FontLevel.sanitize(level)) {
        FontLevel.SMALL -> 0.94f
        FontLevel.LARGE -> 1.12f
        else -> 1.0f
    }
}
