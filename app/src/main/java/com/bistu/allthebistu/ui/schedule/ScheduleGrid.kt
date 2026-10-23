package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.data.prefs.FontLevel

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

/**
 * 字号规范（表头与节次列，固定不随设置变化）。
 *
 * 随「字体大小」设置变化的是课程块里的字，换算逻辑见
 * [CourseTypography]：按实际列宽反算，保证每行能放 5/4/3 个汉字。
 *
 * 纵向预算：行高 56dp 时，2 节的课块（112dp）要装下
 * 3 行课程名 + 时间 + 教室；最大档（约 14sp）时
 * 3×18 + 11 + 13 ≈ 78dp，仍在 105dp 可用高度内。
 */
private val HeaderWeekdaySize = 13.sp
private val HeaderDateSize = 10.sp
private val HeaderMonthSize = 12.sp

private val PeriodNumberSize = 14.sp
private val PeriodTimeSize = 8.sp

/** 课程块内三种字号的相对比例：课名 : 教室 : 时间 = 1 : 0.9 : 0.8 */
private const val ROOM_RATIO = 0.9f
private const val TIME_RATIO = 0.8f

/** 中文行距给到 1.3 倍，字号多大都不该挤在一起 */
private const val TITLE_LINE_HEIGHT_RATIO = 1.3f
private const val ROOM_LINE_HEIGHT_RATIO = 1.25f

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
 * @param fontLevel 课程块字体挡位：0=一行5字 1=一行4字 2=一行3字
 * @param paletteIndex 课程名 → 色板索引，保证同屏课程颜色互不相同
 * @param onCourseClick 点击课程块的回调；为 null 时课程块不可点击
 */
@Composable
fun ScheduleGrid(
    data: ScheduleData,
    weekNumber: Int,
    todayDayOfWeek: Int = -1,
    colorScheme: Int = CourseColorScheme.SCHOOL,
    isDark: Boolean = false,
    termStartDate: String? = null,
    fontLevel: Int = FontLevel.MEDIUM,
    paletteIndex: Map<String, Int> = emptyMap(),
    onCourseClick: ((Course) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isDark) DarkBorder else LightBorder
    val headerBg = if (isDark) DarkHeaderBg else LightHeaderBg
    val timeTextColor = if (isDark) DarkTimeText else LightTimeText
    val todayBg = if (isDark) DarkTodayBg else LightTodayBg
    val todayTextColor = if (isDark) DarkTodayText else LightTodayText
    val cellBg = MaterialTheme.colorScheme.surface

    // 未排课的课程（只有学分、没有节次）不能画进网格，否则会跑到画布外面
    val coursesInWeek = data.courses.filter { it.isInWeek(weekNumber) && it.isArranged }

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

        // 课程块字号：按真实列宽反算，保证这个挡位下一行正好放得下目标字数
        val charsPerLine = CourseTypography.charsPerLine(fontLevel)
        val titleSp = CourseTypography.titleSizeSp(dayWidth.value, charsPerLine)
        val blockText = CourseBlockText.of(titleSp)

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
                                fontSize = HeaderMonthSize,
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
                            fontSize = HeaderWeekdaySize,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                            color = if (isToday) MaterialTheme.colorScheme.onSurface
                            else timeTextColor
                        )
                        if (dateText != null) {
                            Text(
                                text = dateText,
                                fontSize = HeaderDateSize,
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
                                    fontSize = PeriodNumberSize,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFCCCCCC) else Color(0xFF666666)
                                )
                                if (pt != null) {
                                    Text(
                                        text = pt.startTime,
                                        fontSize = PeriodTimeSize,
                                        lineHeight = 10.sp,
                                        color = timeTextColor,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        text = pt.endTime,
                                        fontSize = PeriodTimeSize,
                                        lineHeight = 10.sp,
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
                            periodRowHeight = PeriodRowHeight,
                            text = blockText,
                            paletteIndex = paletteIndex[course.name],
                            onClick = onCourseClick?.let { click -> { click(course) } }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 课程块里三种字的字号，由课程名字号按固定比例推导，
 * 保证切换挡位时三者的相对大小不变。
 */
private data class CourseBlockText(
    val title: TextUnit,
    val titleLineHeight: TextUnit,
    val room: TextUnit,
    val roomLineHeight: TextUnit,
    val time: TextUnit
) {
    companion object {
        fun of(titleSp: Float): CourseBlockText = CourseBlockText(
            title = titleSp.sp,
            titleLineHeight = (titleSp * TITLE_LINE_HEIGHT_RATIO).sp,
            room = (titleSp * ROOM_RATIO).sp,
            roomLineHeight = (titleSp * ROOM_RATIO * ROOM_LINE_HEIGHT_RATIO).sp,
            time = (titleSp * TIME_RATIO).sp
        )
    }
}

@Composable
private fun CourseBlock(
    course: Course,
    colorScheme: Int,
    isDark: Boolean,
    dayColumnWidth: Dp,
    periodRowHeight: Dp,
    text: CourseBlockText,
    paletteIndex: Int? = null,
    onClick: (() -> Unit)? = null
) {
    val bgColor = remember(course.colorHex, course.name, colorScheme, isDark, paletteIndex) {
        CourseColors.backgroundOf(
            originalHex = course.colorHex,
            courseName = course.name,
            scheme = colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex
        )
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
    // 教室为空时不画教室行：否则会渲染出一个孤零零的 "@"（示例数据里体育课就没排教室）
    val showRoom = sections >= 2 && course.room.isNotBlank()
    val showTime = sections >= 2

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = dayColumnWidth, height = blockHeight)
            .padding(1.5.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            // clickable 放在 clip 之后，水波纹才会被圆角裁掉
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 2.dp, vertical = 2.dp)
        ) {
            // 课程名
            Text(
                text = course.name,
                fontSize = text.title,
                fontWeight = FontWeight.Medium,
                lineHeight = text.titleLineHeight,
                color = titleColor,
                maxLines = if (sections >= 3) 4 else if (showRoom) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )

            // 时间（如 08:00）
            if (showTime) {
                Text(
                    text = course.beginTime,
                    fontSize = text.time,
                    // 行高必须跟着字号走，写死会在最大档把字裁掉半截
                    lineHeight = text.time * 1.25f,
                    color = subColor.copy(alpha = 0.8f),
                    maxLines = 1
                )
            }

            // 教室
            if (showRoom) {
                Text(
                    text = "@${course.room}",
                    fontSize = text.room,
                    lineHeight = text.roomLineHeight,
                    color = subColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
