package com.bistu.allthebistu.ui.event

import androidx.compose.ui.graphics.Color
import com.bistu.allthebistu.data.event.EventTag

/**
 * 标签配色。
 *
 * 五个标签固定色，不跟主题/风格走：它表达的是「事件类型」，跟界面配色是两回事。
 * 色值挑的是同一个明度段的实色，在浅色和深色背景上都能立住。
 */
object EventTags {

    private val COLORS = listOf(
        Color(0xFF3B82F6), // 课程：蓝
        Color(0xFFF59E0B), // 作业：橙
        Color(0xFFEF4444), // 考试：红
        Color(0xFF10B981), // 生活：绿
        Color(0xFF8B8F98), // 其他：灰
    )

    fun colorOf(tag: Int): Color = COLORS.getOrElse(EventTag.sanitize(tag)) { COLORS.last() }
}
