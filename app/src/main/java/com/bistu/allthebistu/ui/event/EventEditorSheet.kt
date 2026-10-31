package com.bistu.allthebistu.ui.event

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.event.EventTag
import com.bistu.allthebistu.data.event.Repeat
import com.bistu.allthebistu.data.event.UserEvent
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.opaqueSurface
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 新建 / 编辑日程。
 *
 * ## 版式
 * 和「课程提醒」那个抽屉**长成同一套**（2026-10-31 用户要求统一）：
 * 白底、左上角 22sp 大标题、小节 15sp SemiBold + 右侧控件、小节之间一条发丝线。
 * 不再用「一堆卡片叠起来」的 iOS 表单样式 —— 两套并存一眼就能看出不是一个人画的。
 *
 * 字段就是用户给的那张截图：名称、开始、结束、重复、标签、备注，
 * 只是把卡片换成了「标签在左、值在右」的分节行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorSheet(
    initial: UserEvent?,
    onDismiss: () -> Unit,
    onSave: (UserEvent) -> Unit,
    onDelete: (UserEvent) -> Unit = {}
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current

    // 默认：下一个整点开始，时长 95 分钟（正好一节课多一点）
    val defaultStart = remember {
        Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var startAt by remember(initial) { mutableLongStateOf(initial?.startAt ?: defaultStart) }
    var endAt by remember(initial) {
        mutableLongStateOf(initial?.endAt ?: (defaultStart + 95 * 60_000L))
    }
    var repeat by remember(initial) { mutableIntStateOf(initial?.repeat ?: Repeat.NONE) }
    var tag by remember(initial) { mutableIntStateOf(initial?.tag ?: EventTag.OTHER) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }

    var dateTarget by remember { mutableIntStateOf(0) }   // 1 = 开始，2 = 结束
    var timeTarget by remember { mutableIntStateOf(0) }
    var repeatOpen by remember { mutableStateOf(false) }
    var tagOpen by remember { mutableStateOf(false) }

    val canSave = title.isNotBlank()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 和提醒设置一样用 surface（白），不是 surfaceVariant：
        // 那边是整页白底 + 分隔线，这边跟着走才对得上
        containerColor = opaqueSurface(colors.surface),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetHandle() },
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = metrics.pagePadding)
                .padding(top = 2.dp, bottom = 24.dp)
        ) {
            // ── 标题行：大标题 +（编辑时）删除 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (initial == null) "新建日程" else "编辑日程",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (initial != null) {
                    Text(
                        text = "删除",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onDelete(initial) }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 名称 ──
            BasicTextField(
                value = title,
                onValueChange = { if (it.length <= 60) title = it },
                textStyle = TextStyle(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                ),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (title.isEmpty()) {
                        Text(
                            text = "日程名称",
                            fontSize = 17.sp,
                            color = colors.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    inner()
                }
            )
            Spacer(Modifier.height(8.dp))
            Hairline()

            // ── 开始 / 结束 ──
            SettingRow(label = "开始") {
                ValueChip(text = dateText(startAt)) { dateTarget = 1 }
                Spacer(Modifier.width(6.dp))
                ValueChip(text = UserEvent.clockOf(startAt)) { timeTarget = 1 }
            }
            Hairline()
            SettingRow(label = "结束") {
                ValueChip(text = dateText(endAt)) { dateTarget = 2 }
                Spacer(Modifier.width(6.dp))
                ValueChip(text = UserEvent.clockOf(endAt)) { timeTarget = 2 }
            }

            // ── 重复 ──
            SettingRow(
                label = "重复",
                hint = repeatHint(repeat),
                onClick = { repeatOpen = true }
            ) {
                Text(text = Repeat.nameOf(repeat), fontSize = 15.sp, color = colors.onSurfaceVariant)
                Chevron()
            }

            // ── 标签 ──
            SettingRow(label = "标签", onClick = { tagOpen = true }) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(EventTags.colorOf(tag))
                )
                Spacer(Modifier.width(6.dp))
                Text(text = EventTag.nameOf(tag), fontSize = 15.sp, color = colors.onSurfaceVariant)
                Chevron()
            }

            // ── 备注 ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "备注",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (note.isEmpty()) "选填" else "${note.length}/500",
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                BasicTextField(
                    value = note,
                    onValueChange = { if (it.length <= 500) note = it },
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = colors.onSurface,
                        lineHeight = 20.sp
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    decorationBox = { inner ->
                        if (note.isEmpty()) {
                            Text(
                                text = "写点什么，会显示在今日日程里",
                                fontSize = 14.sp,
                                color = colors.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                        }
                        inner()
                    }
                )
            }

            Hairline()
            Spacer(Modifier.height(10.dp))
            Text(
                text = "日程只保存在本机，不同步到教务系统；开着「课前提醒」时，日程会按同样的提前量响铃。",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))

            // ── 底部按钮 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = "取消",
                    modifier = Modifier.weight(0.38f),
                    onClick = onDismiss
                )
                Spacer(Modifier.width(10.dp))
                PrimaryButton(
                    text = "保存",
                    enabled = canSave,
                    modifier = Modifier.weight(0.62f)
                ) {
                    onSave(
                        UserEvent(
                            id = initial?.id.orEmpty(),
                            title = title.trim(),
                            startAt = startAt,
                            endAt = if (endAt > startAt) endAt else startAt + 30 * 60_000L,
                            repeat = repeat,
                            tag = tag,
                            note = note.trim()
                        )
                    )
                }
            }
        }
    }

    // ── 日期选择 ──
    if (dateTarget != 0) {
        val base = if (dateTarget == 1) startAt else endAt
        val state = rememberDatePickerState(initialSelectedDateMillis = base)
        DatePickerDialog(
            onDismissRequest = { dateTarget = 0 },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { picked ->
                        val next = applyDate(base, picked)
                        if (dateTarget == 1) {
                            val shift = next - startAt
                            startAt = next
                            // 开始往后挪，结束跟着挪，保持原来的时长
                            endAt += shift
                        } else {
                            endAt = next
                        }
                    }
                    dateTarget = 0
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { dateTarget = 0 }) { Text("取消") }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // ── 时间选择 ──
    if (timeTarget != 0) {
        val base = if (timeTarget == 1) startAt else endAt
        val cal = remember(timeTarget) {
            Calendar.getInstance().apply { timeInMillis = base }
        }
        val state = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { timeTarget = 0 },
            confirmButton = {
                TextButton(onClick = {
                    val next = applyTime(base, state.hour, state.minute)
                    if (timeTarget == 1) {
                        val shift = next - startAt
                        startAt = next
                        endAt += shift
                    } else {
                        endAt = next
                    }
                    timeTarget = 0
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { timeTarget = 0 }) { Text("取消") }
            },
            title = { Text(if (timeTarget == 1) "开始时间" else "结束时间") },
            text = { TimePicker(state = state) }
        )
    }

    // ── 重复 ──
    if (repeatOpen) {
        OptionDialog(
            title = "重复",
            options = Repeat.names,
            selected = repeat,
            colorOf = { null },
            onDismiss = { repeatOpen = false },
            onPick = {
                repeat = it
                repeatOpen = false
            }
        )
    }

    // ── 标签 ──
    if (tagOpen) {
        OptionDialog(
            title = "标签",
            options = EventTag.names,
            selected = tag,
            colorOf = { EventTags.colorOf(it) },
            onDismiss = { tagOpen = false },
            onPick = {
                tag = it
                tagOpen = false
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 小部件
// ══════════════════════════════════════════════════════════════

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 2.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
    )
}

/** 分隔线：和提醒设置页同一种淡度 */
@Composable
private fun Hairline() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f))
}

/**
 * 一行设置：左边标题（可带一行小字说明），右边值或控件。
 *
 * 结构照抄「课程提醒」里那几行（`提前多久 …… 15 分钟`），
 * 这样两个抽屉并排看是同一套排版。
 */
@Composable
private fun SettingRow(
    label: String,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (hint != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = hint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Default.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.size(18.dp)
    )
}

/** 日期 / 时间的小胶囊：和提醒页那一眼能看出来的「值」同一档淡底 */
@Composable
private fun ValueChip(text: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(metrics.innerRadius * 0.55f))
            .background(colors.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, fontSize = 14.sp, color = colors.onSurface)
    }
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val metrics = LocalUiMetrics.current
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.35f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val isDark = colors.background.luminance() < 0.5f
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius))
            .background(colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 15.sp, color = colors.onSurface)
    }
}

/**
 * 单选弹窗（重复 / 标签共用）。
 *
 * 这两组选项都只有 4~5 个，一屏摆开比下拉菜单快，也不容易点错。
 */
@Composable
private fun OptionDialog(
    title: String,
    options: List<String>,
    selected: Int,
    colorOf: (Int) -> androidx.compose.ui.graphics.Color?,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                options.forEachIndexed { i, name ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onPick(i) }
                            .padding(horizontal = 4.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        colorOf(i)?.let { dot ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(dot)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            text = name,
                            fontSize = 15.sp,
                            fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (i == selected) colors.primary else colors.onSurface
                        )
                        Spacer(Modifier.weight(1f))
                        if (i == selected) {
                            Text(text = "当前", fontSize = 11.sp, color = colors.primary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ══════════════════════════════════════════════════════════════
// 日期工具
// ══════════════════════════════════════════════════════════════

private val dateFormat = SimpleDateFormat("yyyy年M月d日", Locale.CHINA)

private fun dateText(millis: Long): String = dateFormat.format(Date(millis))

/** 重复这一行的小字说明：不用点进去也知道这档是什么意思 */
private fun repeatHint(repeat: Int): String = when (Repeat.sanitize(repeat)) {
    Repeat.DAILY -> "从开始那天起，每天都会出现"
    Repeat.WEEKLY -> "每周同一个星期几出现"
    Repeat.WEEKDAY -> "周一到周五出现，周末不排"
    else -> "只在开始那天出现一次"
}

/**
 * 把 DatePicker 给的时间戳搬到 [target] 上。
 *
 * `selectedDateMillis` 是 **UTC 当天 0 点**，直接用会差一个时区
 * （东八区会变成前一天 8 点）。所以只取年月日，再写回本地 Calendar。
 */
private fun applyDate(target: Long, pickedUtcMillis: Long): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        timeInMillis = pickedUtcMillis
    }
    return Calendar.getInstance().apply {
        timeInMillis = target
        set(
            utc.get(Calendar.YEAR),
            utc.get(Calendar.MONTH),
            utc.get(Calendar.DAY_OF_MONTH)
        )
    }.timeInMillis
}

private fun applyTime(target: Long, hour: Int, minute: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = target
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
