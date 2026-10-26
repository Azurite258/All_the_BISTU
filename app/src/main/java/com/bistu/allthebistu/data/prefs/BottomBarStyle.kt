package com.bistu.allthebistu.data.prefs

/**
 * 底部导航栏的视觉风格。
 *
 * 两套是**并行维护**的，不是「旧的被新的取代」：
 *  - [FLAT] 不透明实心胶囊。和卡片同色、只有阴影分开边界，够用、省电、对比清晰
 *  - [GLASS] 液态玻璃。半透明 + 厚度渐变 + 镜面边缘，更有质感但依赖背景色
 *
 * 之所以做成可切换而不是直接换成玻璃：浅色模式下玻璃体必须比背景暗一档才看得出，
 * 这会让底栏看起来「有色」。有人喜欢、有人觉得脏，这种审美分歧不该由代码替用户决定。
 */
object BottomBarStyle {
    /** 扁平：不透明实心胶囊，选中块是淡色底 */
    const val FLAT = 0

    /** 光效：液态玻璃，见 [com.bistu.allthebistu.ui.components.liquidGlass] */
    const val GLASS = 1

    /**
     * 默认用扁平。
     *
     * 0.14 之前默认是光效，现在反过来：玻璃体必须比背景暗一档才看得出来，
     * 浅色模式下整条底栏会泛灰，压到课表卡片上像脏了一块；
     * 扁平是不透明实心，对比明确、不吃背景色，也不挑省电模式。
     *
     * **只影响没手动选过的人**：SettingsStore 只在用户点选时写盘，
     * 所以老设备里没这个键的会直接读到新默认值，手动选过光效的保持光效。
     */
    const val DEFAULT = FLAT

    /**
     * 收敛脏数据：只认 GLASS，其余（含越界值、历史残留）一律按默认的 FLAT。
     *
     * 顺序是「认特例、剩下的走默认」而不是「认 FLAT、剩下的走 GLASS」——
     * 默认值改了之后，后一种写法会把脏数据静默抬成旧默认（光效），
     * 于是「明明默认是扁平，却有几台机器显示玻璃」这种查不出原因的怪状。
     */
    fun sanitize(style: Int): Int = if (style == GLASS) GLASS else FLAT

    /** 给界面用的短标签 */
    fun label(style: Int): String = if (sanitize(style) == FLAT) "扁平" else "光效"
}
