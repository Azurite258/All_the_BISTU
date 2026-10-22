package com.example.study1.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 底部导航的四个板块。
 *
 * 划分依据：38 项功能可以干净地归到这四类，没有重叠。
 */
enum class MainTab(
    val label: String,
    val icon: ImageVector
) {
    /** 课表：默认首页，最高频 */
    SCHEDULE("课表", Icons.Default.DateRange),

    /** 校园：All the Bistu 的核心（一卡通、电费、图书馆、空教室） */
    CAMPUS("校园", Icons.Default.Star),

    /** 学习：考试、统计、笔记、AI 问答 */
    STUDY("学习", Icons.Default.CheckCircle),

    /** 我的：账号、设置、关于 */
    PROFILE("我的", Icons.Default.Person)
}
