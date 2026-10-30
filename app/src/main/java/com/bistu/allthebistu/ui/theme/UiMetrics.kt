package com.bistu.allthebistu.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 卡片的分层手段。
 *
 * 方向稿里六套风格用的是三种不同的分层方式，混用就会「看着像但又不像」：
 *  - SHADOW：`box-shadow: var(--shadow)`，没有描边（原版 / HyperOS）
 *  - BORDER：`border: 1px solid var(--line)`，没有阴影（Linear）
 *  - TONAL：既无阴影也无描边，靠 surface 与 background 的色阶分层（Material 3）
 *  - BORDER_ON_SURFACE2：描边 + 卡片用 surface-2（极简，卡片比页面更白）
 *  - GLASS：近白描边 + 顶部内高光 + 底部弱反光（iOS 液态玻璃）
 */
object CardStyle {
    const val SHADOW = 0
    const val BORDER = 1
    const val TONAL = 2
    const val BORDER_ON_SURFACE2 = 3
    const val GLASS = 4
}

/**
 * 底栏选中项的形态。
 *
 * 方向稿六套各不相同，这里必须分开：
 *  - NONE：只把文字/图标染成强调色，没有底色块（HyperOS）
 *  - SOLID_ACCENT：整块强调色实底 + 白字（原版）
 *  - SOFT_ACCENT：accent-soft 淡底 + 强调色字（Linear / M3）
 *  - GREY：灰底 #EDEDED + 近黑字（极简）
 *  - GLASS_WHITE：白色半透明胶囊 + 强调色字 + 内高光（iOS）
 */
object DockPill {
    const val NONE = 0
    const val SOLID_ACCENT = 1
    const val SOFT_ACCENT = 2
    const val GREY = 3
    const val GLASS_WHITE = 4
}

/**
 * 界面风格的**形态**令牌。
 *
 * 每个数字都对应方向稿 `ui-lab/index.html` 里的一个 CSS 变量：
 *
 * | 风格 | r-card | r-inner | r-course | pad | cardpad | gap | title | dock 高 |
 * |---|---|---|---|---|---|---|---|---|
 * | 原版 | 20 | 14 | 14 | 20 | 16 | 12 | 30 | 60 |
 * | HyperOS | 26 | 18 | 12 | 20 | 16 | 12 | 29 | 60 |
 * | Linear | 10 | 7 | 6 | 18 | 13 | 9 | 24 | 60 |
 * | 极简 | 12 | 8 | 8 | 18 | 14 | 10 | 23 | 60 |
 * | M3 | 16 | 12 | 12 | 20 | 16 | 12 | 24 | 60 |
 * | iOS | 28 | 18 | 12 | 18 | 16 | 14 | 31 | 64 |
 *
 * 底栏圆角 = `--r-card`，只有 iOS 是 `999px` 胶囊 —— 这一条之前做错了（六套都当成了胶囊）。
 */
data class UiMetrics(
    /** 卡片圆角 `--r-card` */
    val cardRadius: Dp,
    /** 卡片内部元素圆角 `--r-inner` */
    val innerRadius: Dp,
    /** 课程块圆角 `--r-course`。比卡片小一档，窄条上大圆角会变成「试管」 */
    val courseRadius: Dp,
    /** 页面左右内边距 `--pad` */
    val pagePadding: Dp,
    /** 卡片内 padding `--cardpad` */
    val cardPadding: Dp,
    /** 卡片间距 `--gap` */
    val cardGap: Dp,
    /** 页面大标题字号 `--title` */
    val titleSize: TextUnit,
    /** 设置列表每行高度（方向稿 `.row` 是 padding 11px 0，这里折成固定行高） */
    val rowHeight: Dp,
    /** 底栏圆角：等于 `--r-card`，iOS 是全胶囊 */
    val dockRadius: Dp,
    val dockHeight: Dp,
    /** 底栏左右外边距：`margin: 0 12px 14px` 里那个 12（iOS 14） */
    val dockMarginH: Dp,
    /** 底栏底部外边距，六套都是 14px */
    val dockMarginBottom: Dp,
    /** 顶栏圆形按钮直径：36px，iOS 38px */
    val iconButtonSize: Dp,
    /** 周次切换圆钮直径：30px */
    val weekNavSize: Dp,
    /**
     * 卡片阴影高度。
     *
     * CSS 的 `box-shadow` 是两层（贴边 1px + 大范围柔和），Compose 只有一个 elevation，
     * 所以取「大范围那层」的量级来近似：HyperOS 的 0 10px 30px → 14dp，iOS 的 0 10px 26px → 12dp，
     * 原版的 0 8px 24px → 10dp。Linear / 极简 / M3 在方向稿里没有阴影，这里是 0。
     */
    val cardElevation: Dp,
    /** 卡片分层方式，见 [CardStyle] */
    val cardStyle: Int,
    /** 底栏选中块形态，见 [DockPill] */
    val dockPill: Int,
    /** 底栏是否液态玻璃材质 */
    val dockGlass: Boolean,
    /** 页面背景是否铺彩色光晕（只有 iOS） */
    val glowBackdrop: Boolean,
    /**
     * 底栏之外还要额外让出的底部高度。
     *
     * 未登录时那条「还没登录」引导条是浮在所有页面之上的一层，
     * 它压在课表卡的最后一两行上、也压住各页滚到底的内容。
     * MainActivity 量到它的真实高度后写进这里，课表卡就会整体缩短到它上方，
     * 而不是被它盖住半截。平时是 0。
     */
    val extraBottom: Dp = 0.dp,
) {
    /**
     * 内容区底部要给底栏让出的空间。
     *
     * 底栏占 `dockHeight + dockMarginBottom`，剩下的按 [cardGap] 让出来。
     *
     * **这里必须用 cardGap，不能写死 8dp**：课表卡上方到周次卡的间距就是 cardGap，
     * 底部只让 8dp 的话，卡片上下两边的空隙不一样宽（18/20 两套能差 6dp），
     * 看着就是「卡片贴在底栏上」。
     */
    val contentBottom: Dp get() = dockHeight + dockMarginBottom + cardGap + extraBottom

    /** 卡片是否需要阴影 */
    val usesShadow: Boolean get() = cardStyle == CardStyle.SHADOW || cardStyle == CardStyle.GLASS

    /** 卡片是否需要描边 */
    val usesBorder: Boolean
        get() = cardStyle == CardStyle.BORDER ||
                cardStyle == CardStyle.BORDER_ON_SURFACE2 ||
                cardStyle == CardStyle.GLASS
}

/**
 * 当前风格的形态令牌。
 *
 * 默认值故意取「原版」——任何拿不到 CompositionLocal 的预览/测试里，
 * 渲染出来的仍然是与 0.16 一致的界面，不会突然变样。
 */
val LocalUiMetrics = staticCompositionLocalOf { UiStyle.metrics(UiStyle.ORIGINAL) }

/** 六套风格的形态取值，数字逐项对应方向稿的 CSS 变量 */
internal fun metricsOf(style: Int): UiMetrics = when (style) {
    UiStyle.HYPEROS -> UiMetrics(
        cardRadius = 26.dp,
        innerRadius = 18.dp,
        courseRadius = 12.dp,
        pagePadding = 20.dp,
        cardPadding = 16.dp,
        cardGap = 12.dp,
        titleSize = 29.sp,
        rowHeight = 38.dp,
        dockRadius = 26.dp,
        dockHeight = 60.dp,
        dockMarginH = 12.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 36.dp,
        weekNavSize = 30.dp,
        cardElevation = 14.dp,
        cardStyle = CardStyle.SHADOW,
        // 方向稿里 HyperOS 的选中项只靠文字变色，没有底块；
        // 但实际用起来「只有一个橙色图标」太素，用户明确要求补上淡橙药丸。
        // 用 accent-soft 而不是实底，和澎湃本体的选中态一致。
        dockPill = DockPill.SOFT_ACCENT,
        // 澎湃 / 原版两套统一走「扁平」底栏，不再有玻璃（光效）版本：
        // 这两套没有彩色光晕可透，玻璃体只能靠压深一档来分离，
        // 浅色下整条底栏泛灰、压在卡片上像脏了一块，用户明确要求去掉。
        dockGlass = false,
        glowBackdrop = false
    )

    UiStyle.LINEAR -> UiMetrics(
        cardRadius = 10.dp,
        innerRadius = 7.dp,
        courseRadius = 6.dp,
        pagePadding = 18.dp,
        cardPadding = 13.dp,
        cardGap = 9.dp,
        titleSize = 24.sp,
        rowHeight = 38.dp,
        dockRadius = 10.dp,
        dockHeight = 60.dp,
        dockMarginH = 12.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 36.dp,
        weekNavSize = 30.dp,
        cardElevation = 0.dp,
        cardStyle = CardStyle.BORDER,
        dockPill = DockPill.SOFT_ACCENT,
        dockGlass = false,
        glowBackdrop = false
    )

    UiStyle.MINIMAL -> UiMetrics(
        cardRadius = 12.dp,
        innerRadius = 8.dp,
        courseRadius = 8.dp,
        pagePadding = 18.dp,
        cardPadding = 14.dp,
        cardGap = 10.dp,
        titleSize = 23.sp,
        rowHeight = 38.dp,
        dockRadius = 12.dp,
        dockHeight = 60.dp,
        dockMarginH = 12.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 36.dp,
        weekNavSize = 30.dp,
        cardElevation = 0.dp,
        cardStyle = CardStyle.BORDER_ON_SURFACE2,
        dockPill = DockPill.GREY,
        dockGlass = false,
        glowBackdrop = false
    )

    UiStyle.MATERIAL3 -> UiMetrics(
        cardRadius = 16.dp,
        innerRadius = 12.dp,
        courseRadius = 12.dp,
        pagePadding = 20.dp,
        cardPadding = 16.dp,
        cardGap = 12.dp,
        titleSize = 24.sp,
        rowHeight = 38.dp,
        dockRadius = 16.dp,
        dockHeight = 60.dp,
        dockMarginH = 12.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 36.dp,
        weekNavSize = 30.dp,
        cardElevation = 0.dp,
        cardStyle = CardStyle.TONAL,
        dockPill = DockPill.SOFT_ACCENT,
        dockGlass = false,
        glowBackdrop = false
    )

    UiStyle.IOS -> UiMetrics(
        cardRadius = 28.dp,
        innerRadius = 18.dp,
        courseRadius = 12.dp,
        pagePadding = 18.dp,
        cardPadding = 16.dp,
        cardGap = 14.dp,
        titleSize = 31.sp,
        rowHeight = 38.dp,
        // 999px → 全胶囊，用高度一半即可，随高度自适应
        dockRadius = 32.dp,
        dockHeight = 64.dp,
        dockMarginH = 14.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 38.dp,
        weekNavSize = 30.dp,
        cardElevation = 12.dp,
        cardStyle = CardStyle.GLASS,
        dockPill = DockPill.GLASS_WHITE,
        dockGlass = true,
        glowBackdrop = true
    )

    else -> UiMetrics(
        cardRadius = 20.dp,
        innerRadius = 14.dp,
        courseRadius = 14.dp,
        pagePadding = 20.dp,
        cardPadding = 16.dp,
        cardGap = 12.dp,
        titleSize = 30.sp,
        rowHeight = 38.dp,
        dockRadius = 20.dp,
        dockHeight = 60.dp,
        dockMarginH = 12.dp,
        dockMarginBottom = 14.dp,
        iconButtonSize = 36.dp,
        weekNavSize = 30.dp,
        cardElevation = 10.dp,
        cardStyle = CardStyle.SHADOW,
        dockPill = DockPill.SOLID_ACCENT,
        // 原版同样固定扁平，见上面对 HyperOS 的说明
        dockGlass = false,
        glowBackdrop = false
    )
}
