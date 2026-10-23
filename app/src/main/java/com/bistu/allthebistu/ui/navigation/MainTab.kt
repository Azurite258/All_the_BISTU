package com.bistu.allthebistu.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.vector.ImageVector

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
 *  - 生活：一卡通、电费、图书馆、空教室这些校园生活服务
 *  - 我的：账号与设置
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector
) {
    /** 课表：默认首页，最高频 */
    SCHEDULE("课表", Icons.Default.DateRange),

    /** 学习：今日日程与时间线、考试安排、统计、笔记 */
    STUDY("学习", Icons.Default.CheckCircle),

    /** 生活：一卡通、电费、图书馆、空教室等校园生活服务 */
    LIFE("生活", Icons.Default.Star),

    /** 我的：账号、设置、关于 */
    PROFILE("我的", Icons.Default.Person)
}
