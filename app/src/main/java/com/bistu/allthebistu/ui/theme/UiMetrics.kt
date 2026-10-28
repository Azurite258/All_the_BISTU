package com.bistu.allthebistu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 界面风格的**形态**令牌。
 *
 * 只换 ColorScheme 是不够的：六套风格彼此的区别，一半来自颜色，另一半来自
 * 圆角、间距、字号、卡片是靠阴影还是靠边框。这几项如果不跟着换，
 * 用户切完只会觉得「变了个色」，界面还是原来那一套。
 *
 * 这里的每个值都对应方向稿里的一个 CSS 变量（`--r-card` / `--cardpad` / `--gap` / `--title`…），
 * 改的时候对着 `ui-lab/index.html` 比，别凭手感。
 */
data class UiMetrics(
    /** 卡片圆角：26 / 10 / 12 / 16 / 28 / 20，六套风格最直观的差别 */
    val cardRadius: Dp,
    /** 卡片内部元素圆角（按钮、内嵌小块） */
    val innerRadius: Dp,
    /** 课程块圆角。比卡片小一档，窄条上大圆角会变成「试管」 */
    val courseRadius: Dp,
    /** 页面左右内边距 */
    val pagePadding: Dp,
    /** 卡片内部 padding */
    val cardPadding: Dp,
    /** 卡片之间的纵向间距 */
    val cardGap: Dp,
    /** 页面大标题字号 */
    val titleSize: TextUnit,
    /** 设置列表每行高度：太挤和太散是两种气质 */
    val rowHeight: Dp,
    /** 底栏圆角。等于高度的一半 = 胶囊 */
    val dockRadius: Dp,
    val dockHeight: Dp,
    /** 底栏离屏幕左右和底部的距离。iOS 靠这个「浮」起来 */
    val dockMargin: Dp,
    /** 卡片靠阴影分层（原版 / HyperOS）还是靠边框（其余四套） */
    val useShadow: Boolean,
    val useBorder: Boolean,
) {
    /**
     * 内容区底部要给底栏让出的空间。
     *
     * 之前是写死 88dp 的，但底栏实际占 `dockHeight + 2 × dockMargin`
     * （原版 66+24=90），88 比它还小 —— 课表卡的下边缘会钻进底栏底下。
     * 各套风格的底栏高度和边距都不一样，所以这个值必须算，不能写死。
     */
    val contentBottom: Dp get() = dockHeight + dockMargin * 2 + 8.dp
}

/**
 * 当前风格的形态令牌。
 *
 * 默认值故意取「原版」——任何拿不到 CompositionLocal 的预览/测试里，
 * 渲染出来的仍然是与 0.16 一致的界面，不会突然变样。
 */
val LocalUiMetrics = staticCompositionLocalOf { UiStyle.metrics(UiStyle.ORIGINAL) }

/** 六套风格的形态取值。数字与 `ui-lab/PRODUCT.md` 的表格一一对应 */
internal fun metricsOf(style: Int): UiMetrics = when (style) {
    UiStyle.HYPEROS -> UiMetrics(
        cardRadius = 26.dp,
        innerRadius = 18.dp,
        courseRadius = 14.dp,
        pagePadding = 18.dp,
        cardPadding = 16.dp,
        cardGap = 12.dp,
        titleSize = 29.sp,
        rowHeight = 44.dp,
        dockRadius = 34.dp,
        dockHeight = 68.dp,
        dockMargin = 12.dp,
        useShadow = true,
        useBorder = false
    )

    UiStyle.LINEAR -> UiMetrics(
        cardRadius = 10.dp,
        innerRadius = 7.dp,
        courseRadius = 6.dp,
        pagePadding = 16.dp,
        cardPadding = 14.dp,
        cardGap = 9.dp,
        titleSize = 24.sp,
        rowHeight = 38.dp,
        dockRadius = 12.dp,
        dockHeight = 60.dp,
        dockMargin = 12.dp,
        useShadow = false,
        useBorder = true
    )

    UiStyle.MINIMAL -> UiMetrics(
        cardRadius = 12.dp,
        innerRadius = 8.dp,
        courseRadius = 8.dp,
        pagePadding = 18.dp,
        cardPadding = 16.dp,
        cardGap = 10.dp,
        titleSize = 23.sp,
        rowHeight = 40.dp,
        dockRadius = 14.dp,
        dockHeight = 62.dp,
        dockMargin = 12.dp,
        useShadow = false,
        useBorder = true
    )

    UiStyle.MATERIAL3 -> UiMetrics(
        cardRadius = 16.dp,
        innerRadius = 12.dp,
        courseRadius = 10.dp,
        pagePadding = 20.dp,
        cardPadding = 16.dp,
        cardGap = 12.dp,
        titleSize = 24.sp,
        rowHeight = 42.dp,
        dockRadius = 30.dp,
        dockHeight = 64.dp,
        dockMargin = 10.dp,
        useShadow = false,
        useBorder = true
    )

    UiStyle.IOS -> UiMetrics(
        cardRadius = 28.dp,
        innerRadius = 18.dp,
        courseRadius = 12.dp,
        pagePadding = 18.dp,
        cardPadding = 16.dp,
        cardGap = 14.dp,
        titleSize = 31.sp,
        rowHeight = 44.dp,
        dockRadius = 32.dp,
        dockHeight = 64.dp,
        dockMargin = 14.dp,
        useShadow = false,
        useBorder = true
    )

    else -> UiMetrics(
        cardRadius = 20.dp,
        innerRadius = 12.dp,
        courseRadius = 12.dp,
        pagePadding = 16.dp,
        cardPadding = 14.dp,
        cardGap = 10.dp,
        titleSize = 28.sp,
        rowHeight = 38.dp,
        dockRadius = 33.dp,
        dockHeight = 66.dp,
        dockMargin = 12.dp,
        useShadow = false,
        useBorder = false
    )
}
