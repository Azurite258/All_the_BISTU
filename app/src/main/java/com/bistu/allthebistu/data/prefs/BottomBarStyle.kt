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

    /** 默认用光效——这是设计上更想推的那一版 */
    const val DEFAULT = GLASS

    /** 收敛脏数据：只认 FLAT，其余一律按 GLASS */
    fun sanitize(style: Int): Int = if (style == FLAT) FLAT else GLASS

    /** 给界面用的短标签 */
    fun label(style: Int): String = if (sanitize(style) == FLAT) "扁平" else "光效"
}
