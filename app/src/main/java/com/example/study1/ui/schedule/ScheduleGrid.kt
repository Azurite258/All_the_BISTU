package com.example.study1.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study1.data.Course
import com.example.study1.data.ScheduleData

// ── 尺寸常量（优化后）──
private val TimeColumnWidth = 28.dp      // 左侧节次列宽（收窄）
private val DayColumnWidth = 54.dp       // 每天列宽
private val PeriodRowHeight = 52.dp      // 每节课行高（收窄）
private val HeaderHeight = 30.dp         // 表头高度
private val BlockPadding = 1.dp

private val BorderColor = Color(0xFFDDDDDD)
private val HeaderBg = Color(0xFFF7F7F7)
private val TimeTextColor = Color(0xFF999999)
private val TodayBg = Color(0xFFFFF8E1)

private val DayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 课表网格（优化版）
 *
 * 关键改进：
 * 1. 节次列独立在最左侧，课程块从节次列右边开始，不会遮挡
 * 2. 课程块文字自适应：单节显示课程名，多节加显示教室
 * 3. 支持高亮今天
 */
@Composable
fun ScheduleGrid(
    data: ScheduleData,
    weekNumber: Int,
    todayDayOfWeek: Int = -1,   // 1-7，-1 表示不标注
    modifier: Modifier = Modifier
) {
    val coursesInWeek = data.courses.filter { it.isInWeek(weekNumber) }

    // 节次范围：至少 12 节，有更晚的课就扩展
    val maxSection = maxOf(12, coursesInWeek.maxOfOrNull { it.endSection } ?: 12)
    val periods = 1..maxSection

    // 总宽度
    val totalWidth = TimeColumnWidth + DayColumnWidth * 7

    Column(modifier = modifier) {
        // ══════ 表头 ══════
        Row(modifier = Modifier.width(totalWidth)) {
            // 左上角
            Box(
                modifier = Modifier
                    .size(width = TimeColumnWidth, height = HeaderHeight)
                    .background(HeaderBg)
                    .border(0.5.dp, BorderColor)
            )
            // 星期
            DayLabels.forEachIndexed { index, label ->
                val dayNum = index + 1
                val isToday = dayNum == todayDayOfWeek
                Box(
                    modifier = Modifier
                        .size(width = DayColumnWidth, height = HeaderHeight)
                        .background(if (isToday) TodayBg else HeaderBg)
                        .border(0.5.dp, BorderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) Color(0xFFE65100) else Color.Unspecified
                    )
                }
            }
        }

        // ══════ 主体：网格 + 课程 ══════
        Row(modifier = Modifier.width(totalWidth)) {

            // ① 左侧节次列（独立一列，永不被遮挡）
            Column {
                periods.forEach { period ->
                    Box(
                        modifier = Modifier
                            .size(width = TimeColumnWidth, height = PeriodRowHeight)
                            .background(HeaderBg)
                            .border(0.5.dp, BorderColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = period.toString(),
                            fontSize = 11.sp,
                            color = TimeTextColor
                        )
                    }
                }
            }

            // ② 右侧 7 天区域（网格背景 + 课程块叠加）
            Box {
                // 背景网格
                Column {
                    periods.forEach { period ->
                        Row {
                            DayLabels.forEachIndexed { index, _ ->
                                val dayNum = index + 1
                                val isToday = dayNum == todayDayOfWeek
                                Box(
                                    modifier = Modifier
                                        .size(width = DayColumnWidth, height = PeriodRowHeight)
                                        .background(if (isToday) TodayBg else Color.Transparent)
                                        .border(0.5.dp, BorderColor)
                                )
                            }
                        }
                    }
                }

                // 课程块（从节次列右边开始，x 不再加 TimeColumnWidth）
                coursesInWeek.forEach { course ->
                    CourseBlock(
                        course = course,
                        dayColumnWidth = DayColumnWidth,
                        periodRowHeight = PeriodRowHeight,
                        headerOffset = 0.dp
                    )
                }
            }
        }
    }
}

/**
 * 单个课程块。
 *
 * 定位：
 *   x = (星期-1) × 每天列宽
 *   y = (开始节次-1) × 行高
 *   height = 节数 × 行高
 */
@Composable
private fun CourseBlock(
    course: Course,
    dayColumnWidth: Dp,
    periodRowHeight: Dp,
    headerOffset: Dp
) {
    val bgColor = remember(course.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(course.colorHex)) }
            .getOrElse { Color(0xFFE3F2FD) }
    }

    val x = dayColumnWidth * (course.dayOfWeek - 1)
    val y = headerOffset + periodRowHeight * (course.beginSection - 1)
    val blockHeight = periodRowHeight * course.sectionCount

    // 根据占的节数决定显示多少内容
    val showRoom = course.sectionCount >= 2
    val showTime = course.sectionCount >= 3

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = dayColumnWidth, height = blockHeight)
            .padding(BlockPadding)
            .clip(RoundedCornerShape(3.dp))
            .background(bgColor)
            .border(0.5.dp, bgColor.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // 课程名（始终显示）
            Text(
                text = course.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 11.sp,
                maxLines = if (showRoom) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )

            // 教室（占 2 节以上显示）
            if (showRoom) {
                Text(
                    text = course.room,
                    fontSize = 8.sp,
                    color = Color(0xFF555555),
                    lineHeight = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 时间（占 3 节以上显示）
            if (showTime) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = course.beginTime,
                    fontSize = 7.sp,
                    color = Color(0xFF888888),
                    lineHeight = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}
