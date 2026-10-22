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

// ── 尺寸常量（HyperOS 风格：圆角更大、留白更足）──
private val TimeColumnWidth = 42.dp
private val DayColumnWidth = 44.dp

/**
 * 每节课的行高。
 *
 * 从 60dp 调到 56dp（-4dp，约 -7%）：
 * 一屏能多看到约 1 节课，但单节课的课程名/教室仍能完整显示，
 * 不会挤成两行截断。再往下调（如 52dp）就会让 1 节课的课块
 * 装不下「课程名 + 时间 + 教室」三行，不要轻易再减。
 */
private val PeriodRowHeight = 56.dp
private val HeaderHeight = 46.dp

/** 网格总宽度：时间列 + 7 天 */
private val GridTotalWidth = TimeColumnWidth + DayColumnWidth * 7

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
 * 布局说明：
 *  - 网格宽度默认自适应父容器（7 天平分剩余宽度），此时**不应**再套 horizontalScroll，
 *    否则左右滑动会被滚动容器吃掉，无法用于切换周次。
 *  - 若父容器未给出确定的宽度约束，则回退到固定宽度 [GridTotalWidth]。
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

    // 本周 7 天日期：优先用教务系统的教学周，其次用学期起始日推算
    val weekDates = remember(weekNumber, termStartDate, data.termWeeks) {
        val tw = data.termWeekOf(weekNumber)
        if (tw != null) {
            // 教学周只给了起止日期，按周一~周日展开 7 天
            runCatching { DateUtils.weekDates(tw.startDate, 1) }.getOrNull()
        } else if (termStartDate.isNullOrBlank()) {
            null
        } else {
            runCatching { DateUtils.weekDates(termStartDate, weekNumber) }.getOrNull()
        }
    }

    // 自适应宽度：父容器有确定宽度时按比例分配，否则用固定宽度
    BoxWithConstraints(modifier = modifier) {
        val isBounded = constraints.maxWidth != androidx.compose.ui.unit.Constraints.Infinity
        val totalWidth = if (isBounded) maxWidth else GridTotalWidth
        // 7 天平分「总宽 - 时间列」
        val dayWidth = if (isBounded) {
            ((totalWidth - TimeColumnWidth) / 7).coerceAtLeast(24.dp)
        } else {
            DayColumnWidth
        }

        Column(modifier = Modifier.width(totalWidth)) {
            // ══════ 表头：月份 + 星期 + 日期 ══════
            Row(modifier = Modifier.width(totalWidth)) {
                // 左上角：显示月份（无边框无背景）
                Box(
                    modifier = Modifier
                        .size(width = TimeColumnWidth, height = HeaderHeight),
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

                // 7 天：星期 + 日期（今天只加黑，不改变背景）
                DayLabels.forEachIndexed { index, label ->
                    val dayNum = index + 1
                    val isToday = dayNum == todayDayOfWeek
                    val dateText = weekDates?.getOrNull(index)?.let { DateUtils.dayOfMonth(it) }

                    Column(
                        modifier = Modifier
                            .size(width = dayWidth, height = HeaderHeight),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isToday) MaterialTheme.colorScheme.onSurface
                            else timeTextColor
                        )
                        if (dateText != null) {
                            Text(
                                text = dateText,
                                fontSize = 9.sp,
                                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.onSurface
                                else timeTextColor
                            )
                        }
                    }
                }
            }

            // ══════ 主体 ══════
            Row(modifier = Modifier.width(totalWidth)) {

                // ① 左侧节次列：节次号 + 开始/结束时间（无边框，无背景）
                Column {
                    periods.forEach { period ->
                        val pt = PeriodTimes.of(period)
                        Box(
                            modifier = Modifier
                                .size(width = TimeColumnWidth, height = PeriodRowHeight),
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

                // ② 7 天区域（无网格线，纯白底）
                Box {
                    // 背景：纯色，不画网格线，不高亮今天整列
                    Column {
                        periods.forEach { _ ->
                            Row {
                                DayLabels.forEachIndexed { _, _ ->
                                    Box(
                                        modifier = Modifier
                                            .size(width = dayWidth, height = PeriodRowHeight)
                                            .background(cellBg)
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
                            dayColumnWidth = dayWidth,
                            periodRowHeight = PeriodRowHeight
                        )
                    }
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
            .padding(1.5.dp)
            .clip(RoundedCornerShape(8.dp))
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
