package com.bistu.allthebistu.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 底部导航的四个板块。
 *
 * **枚举顺序 = 底部导航的显示顺序**（FloatingBottomBar 直接遍历 entries），
 * 调整位置改这里即可，不要去改 UI。
 *
 * 划分思路：
 *  - 课表：高频入口，默认首页
 *  - 学习：与「学业」相关的都收在这里——今日日程/时间线、考试、统计、笔记
 *    （日程这类东西放在课表页会让课表越来越臃肿，也不符合「学习」这个场景）
 *  - 生活：一卡通、图书馆、空教室这些校园生活服务
 *  - 我的：账号与设置
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector
) {
    /** 课表：默认首页，最高频 */
    SCHEDULE("课表", Icons.Default.DateRange),

    /** 学习：书（学业场景），取代原来的对勾 */
    STUDY("学习", BookIcon),

    /** 生活：小房子（校园生活服务），取代原来的星星 */
    LIFE("生活", Icons.Default.Home),

    /** 我的：账号、设置、关于 */
    PROFILE("我的", Icons.Default.Person)
}

/**
 * 「学习」用的书本图标（Material Symbols 的 menu_book，填充型）。
 *
 * 为什么不用现成的：
 *  - `material-icons-core` 里没有书本类图标
 *  - `material-icons-extended` 体积太大，debug 不混淆会让 APK 膨胀几十 MB
 * 所以直接把 24x24 的 path 内联进来，零依赖、体积可忽略。
 */
private val BookIcon: ImageVector = ImageVector.Builder(
    name = "MenuBook",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).addPath(
    pathData = PathParser().parsePathString(
        "M21,5c-1.11,-0.35 -2.33,-0.5 -3.5,-0.5 -1.95,0 -4.05,0.4 -5.5,1.5 " +
            "-1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6v14.65c0,0.25 0.25,0.5 0.5,0.5 " +
            "0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5 " +
            "1.35,-0.85 3.8,-1.5 5.5,-1.5 1.65,0 3.35,0.3 4.75,1.05 0.1,0.05 0.15,0.05 0.25,0.05 " +
            "0.25,0 0.5,-0.25 0.5,-0.5V6c-0.6,-0.45 -1.25,-0.75 -2,-1zM21,18.5c-1.1,-0.35 " +
            "-2.3,-0.5 -3.5,-0.5 -1.7,0 -4.15,0.65 -5.5,1.5V8c1.35,-0.85 3.8,-1.5 5.5,-1.5 " +
            "1.2,0 2.4,0.15 3.5,0.5v11.5z"
    ).toNodes(),
    fill = SolidColor(Color.Black)
).build()
