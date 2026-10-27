package com.bistu.allthebistu.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.Source
import com.bistu.allthebistu.data.StudyReport
import com.bistu.allthebistu.ui.schedule.CourseColors
import com.bistu.allthebistu.ui.schedule.DateUtils
import com.bistu.allthebistu.ui.schedule.ScheduleUiState
import com.bistu.allthebistu.ui.schedule.TERM_START_DATE as TERM_START_OF_TERM
import com.bistu.allthebistu.ui.theme.HyperSpacing
import java.util.Calendar

/**
 * 学习板块。
 *
 * 这里承接「和学业有关、但不属于课表页面」的东西。
 * 第一版先做最实用的：**今日日程 + 时间线**——
 * 早上打开就能看到今天几点有课、现在上到哪一节、下一节还有多久，
 * 不用再去课表页里找今天那一列。
 *
 * 自定义日程（#9）落地后，会和时间线并排出现在「今日日程」里。
 */
@Composable
fun StudyScreen(
    uiState: ScheduleUiState,
    colorScheme: Int,
    isDark: Boolean,
    report: StudyReport? = null,
    modifier: Modifier = Modifier
) {
    val data = uiState.loadResult?.data
    val source = uiState.loadResult?.source

    // 今天是第几周：和课表页用同一套兜底逻辑，否则两边会显示出不同的周次
    val week = remember(data) {
        data?.let {
            DateUtils.displayWeek(
                termWeeks = it.termWeeks,
                termStartDate = TERM_START_OF_TERM,
                weekRange = it.displayWeekRange
            )
        } ?: 1
    }
    val todayDow = DateUtils.todayDayOfWeek()

    val todayCourses = remember(data, week) {
        data?.courses
            ?.filter { it.isInWeek(week) && it.dayOfWeek == todayDow }
            ?.sortedBy { it.beginSection }
            .orEmpty()
    }

    // 学期总周数：教学周给多少算多少，没有教学周就用课程覆盖的最后一周
    val totalWeeks = remember(data) { data?.displayWeekRange?.last ?: 20 }

    // 已选学分：同一门课可能被拆成多个课块（换教室/换周次），按课程码去重后再加
    val semesterCredit = remember(data) { semesterCreditOf(data?.courses.orEmpty()) }
    val courseCount = remember(data) { distinctCourseCount(data?.courses.orEmpty()) }
    // 只有学分、没有上课时间的课（线上课 / 安全教育 / 职业规划…）
    val unarrangedCount = remember(data) {
        data?.courses?.distinctBy { it.code.ifBlank { it.name } }?.count { !it.isArranged } ?: 0
    }

    // 和课表页共用同一套「课程名 → 色板索引」，两边的颜色才对得上
    val paletteIndex = remember(data) {
        CourseColors.paletteIndexFor(data?.courses?.map { it.name }.orEmpty())
    }

    val nowMinutes = remember { nowMinutes() }
    var showErkeSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(
                start = HyperSpacing.pageHorizontal,
                end = HyperSpacing.pageHorizontal,
                top = 16.dp
            )
    ) {
        Text(
            text = "学习",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "第 $week 周 · ${weekdayLabel(todayDow)} ${todayLabel()}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        when {
            data == null -> HintCard(
                title = "课表还没加载出来",
                body = "去「课表」下拉刷新一下，今日日程会跟着出现"
            )

            else -> {
                SemesterOverviewCard(
                    week = week,
                    totalWeeks = totalWeeks,
                    credit = semesterCredit,
                    courseCount = courseCount,
                    unarrangedCount = unarrangedCount,
                    report = report
                )

                Spacer(Modifier.height(HyperSpacing.cardGap))

                TodaySummary(courses = todayCourses, nowMinutes = nowMinutes)

                Spacer(Modifier.height(HyperSpacing.cardGap))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "今日日程",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (source == Source.ASSETS) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "示例数据",
                            fontSize = 10.sp,
                            color = Color(0xFFE65100),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x1FE65100))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (todayCourses.isEmpty()) {
                    HintCard(
                        title = "今天没有课",
                        body = "第 $week 周今天是空的，可以安排点自己的事"
                    )
                } else {
                    Column {
                        todayCourses.forEachIndexed { i, course ->
                            TimelineRow(
                                course = course,
                                state = stateOf(course, todayCourses, nowMinutes),
                                colorHex = course.colorHex,
                                paletteIndex = paletteIndex[course.name],
                                colorScheme = colorScheme,
                                isDark = isDark,
                                isLast = i == todayCourses.lastIndex,
                                nowMinutes = nowMinutes
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(HyperSpacing.cardGap))

        // 二课积分：数据在微信公众号里，App 拿不到会话，这里只做入口和规则说明
        ErkeCard(onClick = { showErkeSheet = true })

        // 底部留白要同时躲开「悬浮导航栏」和「未登录提示条」，
        // 只留 100dp 时最后一张卡会被提示条盖住
        Spacer(Modifier.height(180.dp))
    }

    // 弹窗必须写在 Column 外面：写进去会被当成列表里的一个条目参与滚动
    if (showErkeSheet) {
        ErkeSheet(onDismiss = { showErkeSheet = false })
    }
}

/**
 * 学期概览：这学期的整体进度与已选学分。
 *
 * 放在学习页最上面，回答「这学期还剩多久、我这学期上了多少学分」。
 * 注意「已选学分」是从课表里每门课的 credit 字段累加出来的，
 * 含义是**本学期选了哪些课、合计多少学分**，不是「已修学分」——
 * 后者要等成绩接口接通后才能算。
 */
@Composable
private fun SemesterOverviewCard(
    week: Int,
    totalWeeks: Int,
    credit: Double,
    courseCount: Int,
    unarrangedCount: Int,
    report: StudyReport?
) {
    val progress = (week.toFloat() / totalWeeks.coerceAtLeast(1)).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "学期进度",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "第 $week / $totalWeeks 周",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(10.dp))

        // 进度条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (report != null && report.creditsRequired > 0.0) {
                // 有教务的学业数据时，用官方的「已修 / 要求」
                StatBlock(
                    value = formatCredit(report.creditsEarned),
                    unit = "/ ${formatCredit(report.creditsRequired)} 学分",
                    label = "已修 / 要求",
                    modifier = Modifier.weight(1f)
                )
                StatBlock(
                    value = formatCredit(report.creditsSelection),
                    unit = "学分",
                    label = "本学期已选",
                    modifier = Modifier.weight(1f)
                )
            } else {
                StatBlock(
                    value = formatCredit(credit),
                    unit = "学分",
                    label = "本学期已选",
                    modifier = Modifier.weight(1f)
                )
                StatBlock(
                    value = courseCount.toString(),
                    unit = "门",
                    label = "课程",
                    modifier = Modifier.weight(1f)
                )
            }
            StatBlock(
                value = (totalWeeks - week).coerceAtLeast(0).toString(),
                unit = "周",
                label = "距学期结束",
                modifier = Modifier.weight(1f)
            )
        }

        // 有些课没有排上课时间（线上课/安全教育），它们不在课表里但学分要算
        if (unarrangedCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "另有 $unarrangedCount 门未排课的课程，已计入学分但不显示在课表",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatBlock(
    value: String,
    unit: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " $unit",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 本学期已选学分。
 *
 * 课表里同一门课可能被拆成多个课块（换教室、换周次），
 * 所以先按课程码去重再累加，否则学分会被重复计算。
 * 学分字段是字符串且可能为空，解析不出来就按 0 处理。
 */
internal fun semesterCreditOf(courses: List<Course>): Double =
    courses.distinctBy { it.code.ifBlank { it.name } }
        .sumOf { it.credit.toDoubleOrNull() ?: 0.0 }

/** 去重后的课程门数 */
internal fun distinctCourseCount(courses: List<Course>): Int =
    courses.distinctBy { it.code.ifBlank { it.name } }.size

/** 23.5 → "23.5"，23.0 → "23"，避免出现「23.0 学分」这种别扭写法 */
private fun formatCredit(credit: Double): String {
    if (credit <= 0.0) return "—"
    return if (credit % 1.0 == 0.0) credit.toInt().toString()
    else String.format(java.util.Locale.CHINA, "%.1f", credit)
}

/** 今日概览：几节、几门、首末节时间 */
@Composable
private fun TodaySummary(courses: List<Course>, nowMinutes: Int) {
    val first = courses.minByOrNull { it.beginSection }
    val last = courses.maxByOrNull { it.endSection }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = if (courses.isEmpty()) "0" else courses.size.toString(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = " 节课",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }

        if (first != null && last != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${first.beginTime} 开始 · ${last.endTime} 结束",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val running = courses.firstOrNull { stateOf(it, courses, nowMinutes) == ClassState.RUNNING }
        val next = courses.firstOrNull { stateOf(it, courses, nowMinutes) == ClassState.NEXT }
        if (running != null) {
            Spacer(Modifier.height(10.dp))
            StatusLine(
                text = "正在上《${running.name}》 · ${running.room.ifBlank { "教室待定" }}",
                color = Color(0xFF2E7D32)
            )
        } else if (next != null) {
            val mins = (minutesOf(next.beginTime) ?: 0) - nowMinutes
            Spacer(Modifier.height(10.dp))
            StatusLine(
                text = "下一节《${next.name}》 · ${durationText(mins)}后开始",
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = color
    )
}

/** 时间线的一行：左边时间，中间圆点与竖线，右边课程 */
@Composable
private fun TimelineRow(
    course: Course,
    state: ClassState,
    colorHex: String,
    paletteIndex: Int?,
    colorScheme: Int,
    isDark: Boolean,
    isLast: Boolean,
    nowMinutes: Int
) {
    val dotColor = remember(colorHex, course.name, colorScheme, isDark, paletteIndex) {
        CourseColors.backgroundOf(
            originalHex = colorHex,
            courseName = course.name,
            scheme = colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex
        )
    }
    val dimmed = state == ClassState.DONE

    // IntrinsicSize.Min 让轨道列和右侧内容一样高，
    // 竖线用 weight 撑满剩余空间，才不会在两行之间断开一截
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        // 时间列
        Column(
            modifier = Modifier
                .width(52.dp)
                .padding(top = 2.dp)
        ) {
            Text(
                text = course.beginTime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "第${course.beginSection}节",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 轨道：圆点 + 竖线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(20.dp)
                .fillMaxHeight()
                .padding(top = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(if (state == ClassState.RUNNING) 12.dp else 9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (dimmed) dotColor.copy(alpha = 0.35f) else dotColor)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .padding(top = 3.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        // 课程信息
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = course.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.width(8.dp))
                StateChip(state = state, course = course, nowMinutes = nowMinutes)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append("${course.beginTime} – ${course.endTime}")
                    if (course.room.isNotBlank()) append(" · ${course.room}")
                    append(" · ${course.sectionCount} 节")
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StateChip(state: ClassState, course: Course, nowMinutes: Int) {
    val (text, color) = when (state) {
        ClassState.RUNNING -> {
            val left = (minutesOf(course.endTime) ?: nowMinutes) - nowMinutes
            "进行中 · 还剩 ${durationText(left)}" to Color(0xFF2E7D32)
        }
        ClassState.NEXT -> "即将开始" to MaterialTheme.colorScheme.primary
        ClassState.DONE -> "已结束" to MaterialTheme.colorScheme.onSurfaceVariant
        ClassState.UPCOMING -> return
    }
    Text(
        text = text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** 空态 / 提示卡片 */
@Composable
private fun HintCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ───────────────────────────────────────────────────────────
// 状态判定
// ───────────────────────────────────────────────────────────

enum class ClassState { DONE, RUNNING, NEXT, UPCOMING }

/**
 * 一节课的当前状态。
 *
 * NEXT 指「今天最近的一节还没开始的课」，只标一节，
 * 否则后面所有课都会挂上「即将开始」，反而看不出重点。
 */
internal fun stateOf(course: Course, todays: List<Course>, nowMinutes: Int): ClassState {
    val start = minutesOf(course.beginTime) ?: return ClassState.UPCOMING
    val end = minutesOf(course.endTime) ?: (start + 45)
    return when {
        nowMinutes > end -> ClassState.DONE
        nowMinutes >= start -> ClassState.RUNNING
        else -> {
            val next = todays
                .filter { (minutesOf(it.beginTime) ?: Int.MAX_VALUE) > nowMinutes }
                .minByOrNull { minutesOf(it.beginTime) ?: Int.MAX_VALUE }
            if (next?.name == course.name && next.beginSection == course.beginSection) {
                ClassState.NEXT
            } else {
                ClassState.UPCOMING
            }
        }
    }
}

/** "08:00" → 480 */
internal fun minutesOf(hhmm: String): Int? {
    val parts = hhmm.split(":")
    if (parts.size != 2) return null
    val h = parts[0].trim().toIntOrNull() ?: return null
    val m = parts[1].trim().toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 把分钟数说成人话：75 → "1 小时 15 分钟" */
internal fun durationText(minutes: Int): String {
    if (minutes <= 0) return "马上就要"
    return if (minutes >= 60) {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "$h 小时" else "$h 小时 $m 分钟"
    } else {
        "$minutes 分钟"
    }
}

private fun nowMinutes(): Int {
    val c = Calendar.getInstance()
    return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
}

internal fun weekdayLabel(dow: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        .getOrElse(dow - 1) { "周一" }

/** "2026-10-23" → "10-23" */
private fun todayLabel(): String = DateUtils.todayIso().takeLast(5)

/**
 * 二课积分入口卡。
 *
 * 北信科的第二课堂系统挂在微信公众号「勤信青年」里，
 * 走微信授权登录，原生 App 拿不到那个会话——所以这里不做数据抓取，
 * 只负责把「去哪看」和「要攒多少」讲清楚。
 */
@Composable
private fun ErkeCard(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "二课积分",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "查看你的二课积分 →",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "毕业前需累计 80 积分，且五大模块各达最低要求，才拿得到 2 个必修学分",
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 二课积分的查看指引 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ErkeSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HyperSpacing.pageHorizontal)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = "二课积分在哪看",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "北信科的第二课堂系统在微信公众号里，不在教务系统",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))

            ErkeStep("1", "打开微信，搜索公众号「勤信青年」（校团委官方号）")
            ErkeStep("2", "进公众号，底部菜单找「第二课堂」")
            ErkeStep("3", "点「我的」→ 学分/学时，可以看到总积分和各模块明细")

            Spacer(Modifier.height(18.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .padding(14.dp)
            ) {
                Text(
                    text = "达标要求",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "毕业前累计满 80 积分 + 五大模块各达最低线 → 兑换 2 个必修学分。" +
                            "二课还占综合测评的 25%，直接影响奖学金和保研。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(18.dp))

            Button(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("公众号", "勤信青年"))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (copied) "已复制「勤信青年」" else "复制公众号名称")
            }
        }
    }
}

@Composable
private fun ErkeStep(index: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = index,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
