package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.data.prefs.FontLevel
import com.bistu.allthebistu.ui.theme.AppWallpaper
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.LocalUiStyle
import com.bistu.allthebistu.ui.theme.UiStyle

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
/**
 * 课表网格。
 *
 * 外面这层只做一件事：把全局字体缩放**关掉**。
 *
 * 课程块里的字号是 [CourseTypography] 按真实列宽反算出来的，目的是保证
 * 「课名一行正好放 5/4/3 个字」。如果它再吃一遍全局 fontScale（我的页面里
 * 那个字体档位），字会被二次放大，「大」档下一条 7 列的行会直接装不下课名。
 * 所以课表里固定 fontScale = 1，只保留 dp 密度。
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
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = 1f)
    ) {
        ScheduleGridBody(
            data = data,
            weekNumber = weekNumber,
            todayDayOfWeek = todayDayOfWeek,
            colorScheme = colorScheme,
            isDark = isDark,
            termStartDate = termStartDate,
            fontLevel = fontLevel,
            paletteIndex = paletteIndex,
            onCourseClick = onCourseClick,
            modifier = modifier
        )
    }
}

@Composable
private fun ScheduleGridBody(
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
    // 表格的骨架色跟随界面风格，不再写死灰阶：
    // 六套风格里极简是纯黑白、M3 是紫色调、iOS 偏冷蓝，写死的灰边在它们下面会很扎眼。
    val uiStyle = LocalUiStyle.current
    val borderColor = MaterialTheme.colorScheme.outline
    val headerBg = MaterialTheme.colorScheme.surfaceVariant
    val timeTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val periodTextColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (isDark) 0.78f else 0.62f
    )
    val todayBg = UiStyle.todayBg(uiStyle, isDark)
    val todayTextColor = UiStyle.todayText(uiStyle, isDark)
    // 有壁纸时格子透明，让下面那层半透明卡片把壁纸透上来；
    // 无壁纸时仍是实色 surface，和以前完全一致。
    // 格子不铺底色才能透出下层的壁纸 / iOS 光晕；铺了实色的话，
    // 上面那张半透明卡片就白透了 —— 看着还是实心白板。
    val cellBg = if (AppWallpaper.enabled || LocalUiMetrics.current.glowBackdrop) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.surface
    }

    // 未排课的课程（只有学分、没有节次）不能画进网格，否则会跑到画布外面
    val coursesInWeek = data.courses.filter { it.isInWeek(weekNumber) && it.isArranged }

    // 节次范围：至少到 14 节（覆盖晚上的课）
    val maxSection = maxOf(14, coursesInWeek.maxOfOrNull { it.endSection } ?: 14)

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

        // 行数：**全部画满**，不再按可视高度截断。
        //
        // 之前只画「卡片放得下的那几行」（通常 8~9 行），第 9 节以后的课在界面上
        // 根本不存在，往上滑也滑不出来 —— 这是 bug，不是设计。现在固定画满
        // maxSection（至少 14 节），超出的部分靠外层 Box(verticalScroll) 滚出来；
        // 卡片照旧用圆角裁切，滑到底能看到最后一节。
        val periods = 1..maxSection
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
                        // 「10」与「月」同属一个 Column，靠 horizontalAlignment 居中对齐，
                        // 两者的中轴天然重合。**横向偏移必须加在 Column 上、或者干脆不加**——
                        // 只给「10」加 offset 会把「月」甩到左边去（0.19-g 就是这个毛病）。
                        // 这里横向不偏移，让它和下方节次数字（1 / 2 / 08:00）落在同一条竖轴上。
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.offset(y = 4.dp)
                        ) {
                            Text(
                                text = monthLabel,
                                fontSize = HeaderMonthSize,
                                lineHeight = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = timeTextColor
                            )
                            Text(
                                text = "月",
                                fontSize = 9.sp,
                                lineHeight = 10.sp,
                                color = timeTextColor,
                                modifier = Modifier.offset(y = (-1).dp)
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
                            .size(width = dayWidth, height = HeaderHeight)
                            // 星期这一整排也往下挪 2dp，和「10 月」一起离开卡片上沿
                            .offset(y = 2.dp),
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
                                    color = periodTextColor
                                )
                                if (pt != null) {
                                    Text(
                                        text = pt.startTime,
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

            // 现在整张表比卡片高、靠滚动看晚课，滑到底时给最后一行留一点余量，
            // 免得第 14 节正好贴在卡片下沿上
            Spacer(Modifier.height(PeriodRowHeight * 0.35f))
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
    val uiStyleNow = LocalUiStyle.current
    val courseIndex = CourseColors.indexOf(course.name, paletteIndex)
    val bgColor = remember(course.colorHex, course.name, colorScheme, isDark, paletteIndex, uiStyleNow) {
        CourseColors.backgroundOf(
            originalHex = course.colorHex,
            courseName = course.name,
            scheme = colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex,
            style = uiStyleNow
        )
    }
    // 跟随风格时文字也走同色相的 HSL（方向稿的 hsl(h, 48%, t)），
    // 其余配色方案继续用按亮度挑黑白的旧逻辑
    val (titleColor, subColor) = remember(bgColor, isDark, colorScheme, uiStyleNow, courseIndex) {
        if (colorScheme == CourseColorScheme.FOLLOW_STYLE) {
            CourseColors.textPairStyle(courseIndex, UiStyle.courseTone(uiStyleNow, isDark), bgColor, isDark)
        } else {
            CourseColors.textColorOf(bgColor, isDark) to CourseColors.subTextColorOf(bgColor, isDark)
        }
    }

    val x = dayColumnWidth * (course.dayOfWeek - 1)
    val y = periodRowHeight * (course.beginSection - 1)
    val blockHeight = periodRowHeight * course.sectionCount

    val sections = course.sectionCount
    // 教室为空时不画教室行：否则会渲染出一个孤零零的 "@"（示例数据里体育课就没排教室）
    val showRoom = sections >= 2 && course.room.isNotBlank()
    val showTime = sections >= 2

    val blockShape = RoundedCornerShape(LocalUiMetrics.current.courseRadius)
    // 方向稿里每个课块都有一圈 1px 描边（iOS 是白边），少了它课块会显得「贴在纸上」
    val blockBorder = remember(colorScheme, uiStyleNow, courseIndex, isDark) {
        if (colorScheme == CourseColorScheme.FOLLOW_STYLE) {
            CourseColors.styleBorderColor(courseIndex, UiStyle.courseTone(uiStyleNow, isDark), uiStyleNow)
        } else {
            Color.Transparent
        }
    }

    Box(
        modifier = Modifier
            .offset(x = x, y = y)
            .size(width = dayColumnWidth, height = blockHeight)
            .padding(1.5.dp)
            .clip(blockShape)
            .background(bgColor)
            .then(
                if (blockBorder != Color.Transparent) Modifier.border(1.dp, blockBorder, blockShape)
                else Modifier
            )
            // clickable 放在 clip 之后，水波纹才会被圆角裁掉
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
    ) {
        // 圆角会咬掉四角的字：半径 12~14dp 时，左上角弧线在 x=3dp 处能占到 5dp 高，
        // 字压在描边上看得很明显。所以顶部留 6dp、左右各留 4/3dp，
        // 让首行首字整个落在弧线以内（再大就会把四字课程名挤成三行）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 3.dp, top = 6.dp, bottom = 2.dp)
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
