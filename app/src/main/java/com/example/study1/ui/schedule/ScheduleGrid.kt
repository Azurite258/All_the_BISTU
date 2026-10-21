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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study1.data.Course
import com.example.study1.data.ScheduleData
import com.example.study1.data.prefs.CourseColorScheme

// ── 尺寸常量（加了时间后左列需要更宽）──
private val TimeColumnWidth = 46.dp
private val DayColumnWidth = 52.dp
private val PeriodRowHeight = 60.dp
private val HeaderHeight = 46.dp
private val BlockPadding = 1.dp

private val LightBorder = Color(0xFFE5E5E5)
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
 * 课表网格（含节次时间）。
 *
 * @param weekNumber 当前周次（用于计算日期）
 * @param termStartDate 学期第 1 周周一的日期，格式 "yyyy-MM-dd"；为空则不显示日期
 * @param colorScheme 配色方案：0=学校 1=柔和 2=高对比
 * @param isDark 是否深色模式
 */
@Composable
fun ScheduleGrid(
    data: ScheduleData,
    weekNumber: Int,
    todayDayOfWeek: Int = -1,
    colorScheme: Int = CourseColorScheme.SCHOOL,
    isDark: Boolean = false,
    termStartDate: String? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isDark) DarkBorder else LightBorder
    val headerBg = if (isDark) DarkHeaderBg else LightHeaderBg
    val timeTextColor = if (isDark) DarkTimeText else LightTimeText
    val todayBg = if (isDark) DarkTodayBg else LightTodayBg
    val todayTextColor = if (isDark) DarkTodayText else LightTodayText
    val cellBg = MaterialTheme.colorScheme.surface

    val coursesInWeek = data.courses.filter { it.isInWeek(weekNumber) }

    // 节次范围：至少到 14 节（覆盖晚上的课）
    val maxSection = maxOf(14, coursesInWeek.maxOfOrNull { it.endSection } ?: 14)
    val periods = 1..maxSection
    val totalWidth = TimeColumnWidth + DayColumnWidth * 7

    // 本周周一的日期（用于表头显示日期）
    val weekDates = remember(weekNumber, termStartDate) {
        if (termStartDate.isNullOrBlank()) null
        else runCatching { DateUtils.weekDates(termStartDate, weekNumber) }.getOrNull()
    }

    Column(modifier = modifier) {
        // ══════ 表头：月份 + 星期 + 日期 ══════
        Row(modifier = Modifier.width(totalWidth)) {
            // 左上角：显示月份
            Box(
                modifier = Modifier
                    .size(width = TimeColumnWidth, height = HeaderHeight)
                    .background(headerBg)
                    .border(0.5.dp, borderColor),
                contentAlignment = Alignment.Center
            ) {
                val monthLabel = weekDates?.firstOrNull()?.let { DateUtils.monthOf(it) }
                if (monthLabel != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = monthLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = timeTextColor
                        )
                        Text(
                            text = "月",
                            fontSize = 9.sp,
                            color = timeTextColor
                        )
                    }
                }
            }

            // 7 天：星期 + 日期
            DayLabels.forEachIndexed { index, label ->
                val dayNum = index + 1
                val isToday = dayNum == todayDayOfWeek
                val dateText = weekDates?.getOrNull(index)?.let { DateUtils.dayOfMonth(it) }

                Column(
                    modifier = Modifier
                        .size(width = DayColumnWidth, height = HeaderHeight)
                        .background(if (isToday) todayBg else headerBg)
                        .border(0.5.dp, borderColor),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) todayTextColor else MaterialTheme.colorScheme.onSurface
                    )
                    if (dateText != null) {
                        Text(
                            text = dateText,
                            fontSize = 9.sp,
                            color = if (isToday) todayTextColor else timeTextColor
                        )
                    }
                }
            }
        }

        // ══════ 主体 ══════
        Row(modifier = Modifier.width(totalWidth)) {

            // ① 左侧节次列：节次号 + 开始/结束时间
            Column {
                periods.forEach { period ->
                    val pt = PeriodTimes.of(period)
                    Box(
                        modifier = Modifier
                            .size(width = TimeColumnWidth, height = PeriodRowHeight)
                            .background(headerBg)
                            .border(0.5.dp, borderColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = period.toString(),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isDark) Color(0xFFCCCCCC) else Color(0xFF666666)
                            )
                            if (pt != null) {
                                Text(
                                    text = pt.startTime,
                                    fontSize = 7.5.sp,
                                    lineHeight = 9.sp,
                                    color = timeTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = pt.endTime,
                                    fontSize = 7.5.sp,
                                    lineHeight = 9.sp,
                                    color = timeTextColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
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
                                        .background(if (isToday) todayBg else cellBg)
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

    val sections = course.sectionCount
    val showRoom = sections >= 2
    val showTime = sections >= 2

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = dayColumnWidth, height = blockHeight)
            .padding(BlockPadding)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // 课程名
            Text(
                text = course.name,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 11.sp,
                color = titleColor,
                maxLines = if (sections >= 3) 4 else if (showRoom) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )

            // 时间（如 08:00）
            if (showTime) {
                Text(
                    text = course.beginTime,
                    fontSize = 7.5.sp,
                    lineHeight = 9.sp,
                    color = subColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            // 教室
            if (showRoom) {
                Text(
                    text = "@${course.room}",
                    fontSize = 8.sp,
                    lineHeight = 10.sp,
                    color = subColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
