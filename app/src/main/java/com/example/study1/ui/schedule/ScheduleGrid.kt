package com.example.study1.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
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
import com.example.study1.data.prefs.CourseColorScheme

// ── 尺寸常量 ──
private val TimeColumnWidth = 28.dp
private val DayColumnWidth = 54.dp
private val PeriodRowHeight = 52.dp
private val HeaderHeight = 30.dp
private val BlockPadding = 1.dp

private val LightBorder = Color(0xFFDDDDDD)
private val DarkBorder = Color(0xFF333333)
private val LightHeaderBg = Color(0xFFF7F7F7)
private val DarkHeaderBg = Color(0xFF232323)
private val LightTimeText = Color(0xFF999999)
private val DarkTimeText = Color(0xFF777777)
private val LightTodayBg = Color(0xFFFFF8E1)
private val DarkTodayBg = Color(0xFF2A2618)
private val LightTodayText = Color(0xFFE65100)
private val DarkTodayText = Color(0xFFFFB74D)

private val DayLabels = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 课表网格。
 *
 * @param colorScheme 配色方案：0=学校 1=柔和 2=高对比
 * @param isDark 是否深色模式（影响边框、表头、课程块颜色）
 */
@Composable
fun ScheduleGrid(
    data: ScheduleData,
    weekNumber: Int,
    todayDayOfWeek: Int = -1,
    colorScheme: Int = CourseColorScheme.SCHOOL,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isDark) DarkBorder else LightBorder
    val headerBg = if (isDark) DarkHeaderBg else LightHeaderBg
    val timeTextColor = if (isDark) DarkTimeText else LightTimeText
    val todayBg = if (isDark) DarkTodayBg else LightTodayBg
    val todayTextColor = if (isDark) DarkTodayText else LightTodayText

    val coursesInWeek = data.courses.filter { it.isInWeek(weekNumber) }

    val maxSection = maxOf(12, coursesInWeek.maxOfOrNull { it.endSection } ?: 12)
    val periods = 1..maxSection
    val totalWidth = TimeColumnWidth + DayColumnWidth * 7

    Column(modifier = modifier) {
        // ══════ 表头 ══════
        Row(modifier = Modifier.width(totalWidth)) {
            Box(
                modifier = Modifier
                    .size(width = TimeColumnWidth, height = HeaderHeight)
                    .background(headerBg)
                    .border(0.5.dp, borderColor)
            )
            DayLabels.forEachIndexed { index, label ->
                val dayNum = index + 1
                val isToday = dayNum == todayDayOfWeek
                Box(
                    modifier = Modifier
                        .size(width = DayColumnWidth, height = HeaderHeight)
                        .background(if (isToday) todayBg else headerBg)
                        .border(0.5.dp, borderColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) todayTextColor else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // ══════ 主体 ══════
        Row(modifier = Modifier.width(totalWidth)) {

            // ① 节次列
            Column {
                periods.forEach { period ->
                    Box(
                        modifier = Modifier
                            .size(width = TimeColumnWidth, height = PeriodRowHeight)
                            .background(headerBg)
                            .border(0.5.dp, borderColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = period.toString(),
                            fontSize = 11.sp,
                            color = timeTextColor
                        )
                    }
                }
            }

            // ② 7 天区域
            Box {
                // 背景网格
                Column {
                    periods.forEach { _ ->
                        Row {
                            DayLabels.forEachIndexed { index, _ ->
                                val dayNum = index + 1
                                val isToday = dayNum == todayDayOfWeek
                                Box(
                                    modifier = Modifier
                                        .size(width = DayColumnWidth, height = PeriodRowHeight)
                                        .background(
                                            if (isToday) todayBg
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .border(0.5.dp, borderColor)
                                )
                            }
                        }
                    }
                }

                // 课程块
                coursesInWeek.forEach { course ->
                    CourseBlock(
                        course = course,
                        colorScheme = colorScheme,
                        isDark = isDark,
                        dayColumnWidth = DayColumnWidth,
                        periodRowHeight = PeriodRowHeight
                    )
                }
            }
        }
    }
}

@Composable
private fun CourseBlock(
    course: Course,
    colorScheme: Int,
    isDark: Boolean,
    dayColumnWidth: Dp,
    periodRowHeight: Dp
) {
    val bgColor = remember(course.colorHex, course.name, colorScheme, isDark) {
        CourseColors.backgroundOf(course.colorHex, course.name, colorScheme, isDark)
    }
    val titleColor = remember(bgColor, isDark) {
        CourseColors.textColorOf(bgColor, isDark)
    }
    val subColor = remember(bgColor, isDark) {
        CourseColors.subTextColorOf(bgColor, isDark)
    }

    val x = dayColumnWidth * (course.dayOfWeek - 1)
    val y = periodRowHeight * (course.beginSection - 1)
    val blockHeight = periodRowHeight * course.sectionCount

    val showRoom = course.sectionCount >= 2
    val showTime = course.sectionCount >= 3

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = dayColumnWidth, height = blockHeight)
            .padding(BlockPadding)
            .clip(RoundedCornerShape(3.dp))
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            Text(
                text = course.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 11.sp,
                color = titleColor,
                maxLines = if (showRoom) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )

            if (showRoom) {
                Text(
                    text = course.room,
                    fontSize = 8.sp,
                    color = subColor,
                    lineHeight = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (showTime) {
                Spacer(Modifier.height(1.dp))
                Text(
                    text = course.beginTime,
                    fontSize = 7.sp,
                    color = subColor.copy(alpha = 0.75f),
                    lineHeight = 9.sp,
                    maxLines = 1
                )
            }
        }
    }
}
