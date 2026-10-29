package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.ui.theme.LocalUiStyle
import com.bistu.allthebistu.ui.theme.UiStyle
import com.bistu.allthebistu.ui.theme.opaqueSurface

private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 课程详情弹窗（底部抽屉）。
 *
 * 数据全部来自已有的 [Course] 模型，不额外请求接口：
 * 教师、教室、校区、学分、周次、课程码都在里面。
 *
 * @param currentWeek 当前查看的周次，用于标注「本周是否有课」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    currentWeek: Int,
    colorScheme: Int,
    isDark: Boolean,
    paletteIndex: Int? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 沿用课表里的同一套配色，点开弹窗时颜色和课块对得上
    val uiStyleNow = LocalUiStyle.current
    val courseIndex = CourseColors.indexOf(course.name, paletteIndex)
    val accent = remember(course.colorHex, course.name, colorScheme, isDark, paletteIndex, uiStyleNow) {
        CourseColors.backgroundOf(
            originalHex = course.colorHex,
            courseName = course.name,
            scheme = colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex,
            style = uiStyleNow
        )
    }
    val onAccent = remember(accent, isDark, colorScheme, uiStyleNow, courseIndex) {
        if (colorScheme == CourseColorScheme.FOLLOW_STYLE) {
            CourseColors.textPairStyle(courseIndex, UiStyle.courseTone(uiStyleNow, isDark), accent, isDark).first
        } else {
            CourseColors.textColorOf(accent, isDark)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = opaqueSurface(MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            // 自定义拖动条：默认的偏窄，HyperOS 风格用更宽更淡的一条
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 28.dp)
        ) {
            // ── 头部：课程名 + 课程码 + 本周状态 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 左侧色块，直接沿用课块颜色
                Box(
                    modifier = Modifier
                        .size(width = 6.dp, height = 40.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (course.code.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = course.code,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                WeekStatusPill(inThisWeek = course.isInWeek(currentWeek))
            }

            // ── 信息区 ──
            Spacer(Modifier.height(20.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                InfoRow(
                    icon = Icons.Default.DateRange,
                    label = "时间",
                    value = "${DayNames.getOrElse(course.dayOfWeek - 1) { "—" }} " +
                            "第 ${course.beginSection}-${course.endSection} 节",
                    secondary = courseTimeRange(course)
                )
                InfoRow(
                    icon = Icons.Default.Place,
                    label = "地点",
                    value = course.room.ifBlank { "未安排教室" },
                    secondary = course.campus.takeIf { it.isNotBlank() }
                )
                InfoRow(
                    icon = Icons.Default.Person,
                    label = "教师",
                    value = course.teacher.ifBlank { "未标注" }
                )
                InfoRow(
                    icon = Icons.Default.Info,
                    label = "周次",
                    value = formatWeeks(course.weeks),
                    secondary = "本门课共 ${course.sectionCount} 节/周" +
                            if (course.credit.isNotBlank()) " · ${course.credit} 学分" else "",
                    showDivider = false
                )
            }

            // ── 脚注 ──
            Spacer(Modifier.height(16.dp))
            Text(
                text = "数据来自教务系统，如有调整以教务系统为准",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 本周是否有课的小标签 */
@Composable
private fun WeekStatusPill(inThisWeek: Boolean) {
    val bg = if (inThisWeek) Color(0xFFE8F5E9) else Color(0xFFF0F0F2)
    val fg = if (inThisWeek) Color(0xFF2E7D32) else Color(0xFF8C8C8C)
    Text(
        text = if (inThisWeek) "本周有课" else "本周无课",
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

/**
 * 一行「图标 + 标签 + 值」。
 */
@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    secondary: String? = null,
    showDivider: Boolean = true
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .size(16.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(40.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = value,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (!secondary.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = secondary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (showDivider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
            )
        }
    }
}

/** 上课时段，如 "10:10 – 11:45"；课程没带时间时用节次表兜底 */
private fun courseTimeRange(course: Course): String? {
    if (course.beginTime.isNotBlank() && course.endTime.isNotBlank()) {
        return "${course.beginTime} – ${course.endTime}"
    }
    val start = PeriodTimes.of(course.beginSection)?.startTime
    val end = PeriodTimes.of(course.endSection)?.endTime
    return if (start != null && end != null) "$start – $end" else null
}

/**
 * 把周次集合压成人类能读的文本。
 *
 * setOf(3,4,5,7,9,10,11) → "3-5、7、9-11 周"
 */
fun formatWeeks(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "未安排"

    val sorted = weeks.sorted()
    val parts = mutableListOf<String>()
    var rangeStart = sorted.first()
    var previous = rangeStart

    for (week in sorted.drop(1)) {
        if (week == previous + 1) {
            previous = week
            continue
        }
        parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"
        rangeStart = week
        previous = week
    }
    parts += if (rangeStart == previous) "$rangeStart" else "$rangeStart-$previous"

    return parts.joinToString("、") + " 周"
}
