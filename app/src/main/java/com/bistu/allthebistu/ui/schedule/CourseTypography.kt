package com.bistu.allthebistu.ui.schedule

import com.bistu.allthebistu.data.prefs.FontLevel

/**
 * 课表字号换算。
 *
 * 为什么不是写死三个 sp 值：
 * 同一档字号在不同宽度的手机上，每行能放的汉字数量是不一样的
 * （列宽 = (屏宽 - 页面边距 - 时间列) / 7）。用户要的是
 * 「一行 5/4/3 个字」这个结果，而不是某个具体数字，
 * 所以这里按实际列宽反算字号，换机型也能保持承诺的换行效果。
 *
 * 中文全角字符的宽度约等于 1em，也就是「字号 = 一个字的宽度」，
 * 因此「可用宽度 ÷ 每行字数」就是字号。
 */
object CourseTypography {

    /** 字号下限：再小就看不清了 */
    const val MIN_TITLE_SP = 7f

    /** 字号上限：再大一行连 3 个字都放不下 */
    const val MAX_TITLE_SP = 16f

    /**
     * 课程块里文字的实际可用宽度 = 列宽 - 这一圈内缩。
     *
     * 三层叠加，少算任何一层都会导致「差半个字」：
     *  - 课程块外层的 1.5dp 留白（左右各一）
     *  - 课程块内层的 2dp 内边距（左右各一）
     * 合计约 7dp。
     */
    const val BLOCK_TEXT_INSET_DP = 7f

    /** 时间列宽度，需与 ScheduleGrid 里的 TimeColumnWidth 一致 */
    const val TIME_COLUMN_DP = 42f

    /** 页面左右边距，需与 HyperSpacing.pageHorizontal 一致 */
    const val PAGE_HORIZONTAL_DP = 16f

    /** 汉字实际渲染宽度略小于 em，留一点余量避免最后一个字被挤下去 */
    private const val SAFETY = 0.96f

    /** 每个挡位下课程名一行大概能放几个汉字 */
    fun charsPerLine(fontLevel: Int): Int = when (FontLevel.sanitize(fontLevel)) {
        FontLevel.SMALL -> 5
        FontLevel.LARGE -> 3
        else -> 4
    }

    /**
     * 按列宽反算课程名字号（sp）。
     *
     * @param columnWidthDp 单列宽度（含课程块内边距）
     * @param charsPerLine 目标每行字数
     */
    fun titleSizeSp(columnWidthDp: Float, charsPerLine: Int): Float {
        val usable = (columnWidthDp - BLOCK_TEXT_INSET_DP).coerceAtLeast(1f)
        val raw = usable * SAFETY / charsPerLine.coerceAtLeast(1)
        return raw.coerceIn(MIN_TITLE_SP, MAX_TITLE_SP)
    }

    /**
     * 算出一列的宽度：屏宽 - 页面左右边距 - 时间列，再平分给 7 天。
     * 用于设置页里的预览，保证预览和课表里的真实尺寸一致。
     */
    fun columnWidthDp(
        screenWidthDp: Float,
        pageHorizontalDp: Float = PAGE_HORIZONTAL_DP
    ): Float = ((screenWidthDp - pageHorizontalDp * 2 - TIME_COLUMN_DP) / 7f)
        .coerceAtLeast(24f)
}
