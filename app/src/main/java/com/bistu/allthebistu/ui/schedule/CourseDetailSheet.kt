package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bistu.allthebistu.R
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.patch.CoursePatchLogic
import com.bistu.allthebistu.data.patch.CoursePatches
import com.bistu.allthebistu.data.patch.PatchScope
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.ui.components.WheelPicker
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.LocalUiStyle
import com.bistu.allthebistu.ui.theme.UiStyle
import com.bistu.allthebistu.ui.theme.glassHairlineColor
import com.bistu.allthebistu.ui.theme.opaqueSurface
import com.bistu.allthebistu.ui.theme.styleCard
import kotlinx.coroutines.delay

private val DayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 节次总数的上限：与课表网格一致（1..14） */
private const val MAX_SECTION = 14

/**
 * 课程详情（底部抽屉）。
 *
 * 版式照用户给的例子：左上一条课程色竖条 + 课程名，下面四行「图标 + 内容」
 * （周次 / 节次时间 / 教室 / 教师），再下面是备注行，底部一排操作
 * （编辑 / 复制 / 删除）。
 *
 * 与例子的区别只在「长相」：颜色、圆角、描边、阴影全部走本项目的六套风格令牌
 * （[styleCard] / [LocalUiMetrics]），换风格时这个抽屉跟着一起变。
 *
 * 数据侧的所有写操作都进 [com.bistu.allthebistu.data.patch.CoursePatchStore]，
 * 不碰教务系统：
 *  - 备注：按「整门 / 本周」两个作用域分开存
 *  - 编辑：覆盖星期、节次、教室、教师
 *  - 复制：在目标时段造一条本机克隆课
 *  - 删除：整门或单周隐藏（原数据还在，随时能恢复）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    currentWeek: Int,
    patches: CoursePatches,
    colorScheme: Int,
    isDark: Boolean,
    paletteIndex: Int? = null,
    onDismiss: () -> Unit,
    onSaveNote: (scope: Int, week: Int, text: String) -> Unit,
    onHide: (scope: Int, week: Int) -> Unit,
    onEdit: (day: Int, begin: Int, end: Int, room: String, teacher: String) -> Unit,
    onClone: (day: Int, begin: Int) -> Unit,
    onRemoveClone: () -> Unit,
    onReset: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val uiStyle = LocalUiStyle.current
    val metrics = LocalUiMetrics.current
    val colors = MaterialTheme.colorScheme
    val isDarkTheme = colors.background.luminance() < 0.5f

    // 课块/色条沿用课表里那一套课程色，点开弹窗时颜色和格子对得上
    val courseIndex = CourseColors.indexOf(course.name, paletteIndex)
    val accent = remember(course.colorHex, course.name, colorScheme, isDark, paletteIndex, uiStyle) {
        CourseColors.backgroundOf(
            originalHex = course.colorHex,
            courseName = course.name,
            scheme = colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex,
            style = uiStyle
        )
    }

    val key = CoursePatchLogic.keyOf(course)
    val patch = patches.patchOf(key)

    // 备注默认落在「整门」，除非本周单独写过
    var noteScope by remember(key) {
        mutableStateOf(
            if (patch?.weekNotes?.get(currentWeek)?.isNotBlank() == true) PatchScope.WEEK else PatchScope.ALL
        )
    }
    val scopeNote = remember(key, noteScope, patch, currentWeek) {
        if (noteScope == PatchScope.WEEK) patch?.weekNotes?.get(currentWeek).orEmpty() else patch?.note.orEmpty()
    }
    var noteText by remember(key, noteScope) { mutableStateOf(scopeNote) }
    var lastSaved by remember(key, noteScope) { mutableStateOf(scopeNote) }

    // 输入停顿 500ms 就落盘：不必逼用户去找保存按钮，也不会每敲一个字写一次文件
    LaunchedEffect(noteText, noteScope) {
        if (noteText == lastSaved) return@LaunchedEffect
        delay(500)
        onSaveNote(noteScope, currentWeek, noteText)
        lastSaved = noteText
    }

    var editOpen by remember { mutableStateOf(false) }
    var cloneOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    // 点外部 / 下滑关闭时把还没落盘的备注补存一次，否则最后 500ms 内打的字会丢
    fun closeWithFlush() {
        if (noteText != lastSaved) onSaveNote(noteScope, currentWeek, noteText)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { closeWithFlush() },
        sheetState = sheetState,
        // 抽屉底用 surfaceVariant，卡片用 surface：
        // 两个都是 surface 的话，白纸卡摊在白抽屉上完全看不出边界（原版/极简最明显）。
        // 参照示例图也是「灰底抽屉 + 白卡片」这个层次。
        containerColor = opaqueSurface(colors.surfaceVariant),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetHandle() },
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.pagePadding)
                .padding(top = 4.dp, bottom = 24.dp)
        ) {
            // ── 标题：色条 + 课程名 +（改过的话）恢复原样 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = course.name,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sub = buildString {
                        if (course.code.isNotBlank()) append(course.code)
                        if (course.isClone) {
                            if (isNotEmpty()) append(" · ")
                            append("本机复制")
                        }
                    }
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = sub,
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                if (patch != null) {
                    Text(
                        text = "恢复原样",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                onReset()
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // ── 信息卡：四行图标信息 + 备注 ──
            val cardShape = RoundedCornerShape(metrics.innerRadius)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .styleCard(shape = cardShape)
                    .then(
                        // 无描边风格（原版/M3 等）在这里补一条极淡分界线，
                        // 否则白卡片摊在白抽屉上，看不出这是一块内容区
                        if (!metrics.usesBorder) {
                            Modifier.border(1.dp, glassHairlineColor(), cardShape)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                InfoLine(
                    icon = { Icon(Icons.Default.DateRange, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                    text = weekRangeText(course)
                )
                InfoLine(
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_clock),
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    text = "第 ${course.beginSection} - ${course.endSection} 节",
                    trailing = courseTimeRange(course)
                )
                InfoLine(
                    icon = { Icon(Icons.Default.Place, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                    text = buildString {
                        append(course.room.ifBlank { "未安排教室" })
                        if (course.campus.isNotBlank()) append("（${course.campus}）")
                    }
                )
                InfoLine(
                    icon = { Icon(Icons.Default.Person, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                    text = course.teacher.ifBlank { "未标注教师" }
                )

                NoteRow(
                    text = noteText,
                    onTextChange = { noteText = it },
                    scope = noteScope,
                    onScopeChange = { noteScope = it },
                    currentWeek = currentWeek
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── 底部操作 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    text = if (course.isClone) "编辑" else "编辑",
                    modifier = Modifier.weight(1f)
                ) { editOpen = true }
                Spacer(Modifier.width(10.dp))
                IconActionButton(painterResource(R.drawable.ic_copy), "复制到其他时段") { cloneOpen = true }
                Spacer(Modifier.width(10.dp))
                IconActionButton(painterResource(R.drawable.ic_delete), "删除这门课") { deleteOpen = true }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "以上改动只存在本机，不会同步到教务系统",
                fontSize = 10.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }

    if (editOpen) {
        CourseEditSheet(
            course = course,
            onDismiss = { editOpen = false },
            onSave = { day, begin, end, room, teacher ->
                onEdit(day, begin, end, room, teacher)
                editOpen = false
                // 课程挪了位置，这一层拿到的还是旧 course，直接关掉回到网格更自然
                onDismiss()
            }
        )
    }

    if (cloneOpen) {
        CourseCloneSheet(
            course = course,
            onDismiss = { cloneOpen = false },
            onConfirm = { day, begin ->
                onClone(day, begin)
                cloneOpen = false
            }
        )
    }

    if (deleteOpen) {
        val isWeekOnly = course.weeks.size > 1
        ChoiceDialog(
            title = "删除「${course.name}」",
            message = if (isWeekOnly) {
                "只删第 $currentWeek 周，还是整门课都删？原始课表不会被改动，随时可以恢复。"
            } else {
                "这门课只排了这一周，删除后整条从课表上消失。原始课表不会被改动。"
            },
            options = buildList {
                if (isWeekOnly) add("仅第 $currentWeek 周" to {
                    onHide(PatchScope.WEEK, currentWeek)
                    onDismiss()
                })
                add("整门课都删" to {
                    if (course.isClone) onRemoveClone() else onHide(PatchScope.ALL, currentWeek)
                    onDismiss()
                })
            },
            onDismiss = { deleteOpen = false }
        )
    }
}

/** 抽屉顶部的小横条 */
@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .padding(top = 10.dp, bottom = 4.dp)
            .size(width = 36.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
    )
}

/**
 * 一行「图标 + 主文字 + 次要文字」。
 *
 * 不用 Compose 的 ListItem：它的行高、内边距、文字样式都写死在组件里，
 * 六套风格的密度差别压不出来。
 */
@Composable
private fun InfoLine(
    icon: @Composable () -> Unit,
    text: String,
    trailing: String? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (!trailing.isNullOrBlank()) {
            Text(
                text = trailing,
                fontSize = 13.sp,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/**
 * 备注行：图标 + 输入框 + 「仅本周 / 所有周」切换。
 *
 * 切换的是**作用域**，不是「显示哪条」——切到本周时输入框清空并
 * 只写本周那条，切回所有周才看得见整门备注。
 */
@Composable
private fun NoteRow(
    text: String,
    onTextChange: (String) -> Unit,
    scope: Int,
    onScopeChange: (Int) -> Unit,
    currentWeek: Int
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(
                    text = if (scope == PatchScope.WEEK) "第 $currentWeek 周备注（可不填）" else "备注（可不填）",
                    fontSize = 14.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.width(8.dp))
        ScopeSwitch(
            scope = scope,
            onScopeChange = onScopeChange
        )
    }
}

/** 「仅本周 / 所有周」小开关：一段灰底 + 一枚会滑动的白色胶囊 */
@Composable
private fun ScopeSwitch(scope: Int, onScopeChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    val track = colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
    val thumb = if (isDark) colors.surfaceVariant else Color.White

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(track)
            .padding(2.dp)
    ) {
        ScopeCell("仅本周", scope == PatchScope.WEEK, thumb) { onScopeChange(PatchScope.WEEK) }
        ScopeCell("所有周", scope == PatchScope.ALL, thumb) { onScopeChange(PatchScope.ALL) }
    }
}

@Composable
private fun ScopeCell(
    label: String,
    selected: Boolean,
    thumb: Color,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) colors.onSurface else colors.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) thumb else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    )
}

/** 主操作按钮（编辑）：铺满剩余宽度 */
@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val isDark = colors.background.luminance() < 0.5f
    val fill = colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
    }
}

/** 图标按钮（复制 / 删除）：与主按钮同高同圆角 */
@Composable
private fun IconActionButton(painter: Painter, description: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val isDark = colors.background.luminance() < 0.5f
    val fill = colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius))
            .background(fill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painter,
            contentDescription = description,
            tint = colors.onSurface,
            modifier = Modifier.size(19.dp)
        )
    }
}

/**
 * 编辑面板：星期 / 节次区间 / 教室 / 教师。
 *
 * 节次用两列滚轮而不是一堆 chip：1~14 节铺开要 28 个按钮，
 * 滚轮在手掌范围内一次能选完，也不占高度。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseEditSheet(
    course: Course,
    onDismiss: () -> Unit,
    onSave: (day: Int, begin: Int, end: Int, room: String, teacher: String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var day by remember { mutableStateOf(course.dayOfWeek.coerceIn(1, 7)) }
    var begin by remember { mutableStateOf(course.beginSection.coerceIn(1, MAX_SECTION)) }
    var end by remember { mutableStateOf(course.endSection.coerceIn(begin, MAX_SECTION)) }
    var room by remember { mutableStateOf(course.room) }
    var teacher by remember { mutableStateOf(course.teacher) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = opaqueSurface(colors.surfaceVariant),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetHandle() },
        modifier = Modifier.imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.pagePadding)
                .padding(top = 4.dp, bottom = 24.dp)
        ) {
            Text("编辑课程", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = course.name,
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("星期")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayNames.forEachIndexed { index, label ->
                    val value = index + 1
                    DayChip(
                        label = label.removePrefix("周"),
                        selected = value == day,
                        modifier = Modifier.weight(1f)
                    ) { day = value }
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("节次")
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("开始", fontSize = 11.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    WheelPicker(
                        index = begin - 1,
                        count = MAX_SECTION,
                        onIndexChange = {
                            begin = it + 1
                            if (end < begin) end = begin
                        },
                        formatter = { "第 ${it + 1} 节" },
                        itemHeight = 34.dp,
                        visibleItems = 3
                    )
                }
                Text("至", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("结束", fontSize = 11.sp, color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    WheelPicker(
                        index = end - begin,
                        count = MAX_SECTION - begin + 1,
                        onIndexChange = { end = begin + it },
                        formatter = { "第 ${begin + it} 节" },
                        itemHeight = 34.dp,
                        visibleItems = 3
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            FieldLabel("教室")
            Spacer(Modifier.height(6.dp))
            PlainTextField(value = room, onValueChange = { room = it }, placeholder = "例如 XXD-202(智)")
            Spacer(Modifier.height(12.dp))
            FieldLabel("教师")
            Spacer(Modifier.height(6.dp))
            PlainTextField(value = teacher, onValueChange = { teacher = it }, placeholder = "例如 雷丙寅")

            Spacer(Modifier.height(18.dp))
            ActionButton(text = "保存", modifier = Modifier.fillMaxWidth()) {
                onSave(day, begin, end, room, teacher)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "只改本机课表，教务系统不会变",
                fontSize = 10.sp,
                color = colors.onSurfaceVariant
            )
        }
    }
}

/** 复制面板：选星期 + 开始节次，节数沿用它自己 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseCloneSheet(
    course: Course,
    onDismiss: () -> Unit,
    onConfirm: (day: Int, begin: Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var day by remember { mutableStateOf(course.dayOfWeek.coerceIn(1, 7)) }
    var begin by remember {
        // 默认放到「这门课后面那一节」，但不能越过后面的边界：
        // 3 节的课从第 13 节开始就排不下了
        mutableStateOf(
            (course.beginSection + course.sectionCount)
                .coerceIn(1, (MAX_SECTION - course.sectionCount + 1).coerceAtLeast(1))
        )
    }
    val end = (begin + course.sectionCount - 1).coerceAtMost(MAX_SECTION)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = opaqueSurface(colors.surfaceVariant),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { SheetHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = metrics.pagePadding)
                .padding(top = 4.dp, bottom = 24.dp)
        ) {
            Text("复制到", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${course.name} · 共 ${course.sectionCount} 节",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(16.dp))
            FieldLabel("星期")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DayNames.forEachIndexed { index, label ->
                    val value = index + 1
                    DayChip(
                        label = label.removePrefix("周"),
                        selected = value == day,
                        modifier = Modifier.weight(1f)
                    ) { day = value }
                }
            }

            Spacer(Modifier.height(16.dp))
            FieldLabel("开始节次")
            Spacer(Modifier.height(4.dp))
            WheelPicker(
                index = begin - 1,
                count = (MAX_SECTION - course.sectionCount + 1).coerceAtLeast(1),
                onIndexChange = { begin = it + 1 },
                formatter = { "第 ${it + 1} 节" },
                itemHeight = 36.dp,
                visibleItems = 3
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "从第 $begin 节排到第 $end 节，周次和这门课一致",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            ActionButton(text = "复制到这里", modifier = Modifier.fillMaxWidth()) {
                onConfirm(day, begin)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 星期选择的小胶囊 */
@Composable
private fun DayChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    // 选中态用 primary 的淡底 + primary 文字：
    // 只靠 4% / 10% 的灰阶差在深色和极简风格里几乎看不出来（试过，七颗长得一样）
    Box(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(metrics.innerRadius * 0.6f))
            .background(
                if (selected) colors.primary.copy(alpha = 0.16f)
                else colors.onSurface.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant
        )
    }
}

/** 普通的单行输入框（教室 / 教师） */
@Composable
private fun PlainTextField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.innerRadius * 0.7f))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, fontSize = 14.sp, color = colors.onSurfaceVariant.copy(alpha = 0.8f))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * 通用选择弹窗（删除确认用）。
 *
 * 不用 AlertDialog：它只有「确认/取消」两个槽位，
 * 而删除这里需要三个选项（仅本周 / 整门 / 取消），挤进两个槽位会让人选错。
 */
@Composable
private fun ChoiceDialog(
    title: String,
    message: String,
    options: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .styleCard(shape = RoundedCornerShape(metrics.cardRadius))
                .padding(20.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(message, fontSize = 13.sp, color = colors.onSurfaceVariant, lineHeight = 19.sp)
            Spacer(Modifier.height(16.dp))
            options.forEach { (label, action) ->
                ActionButton(text = label, modifier = Modifier.fillMaxWidth()) {
                    action()
                    onDismiss()
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "取消",
                fontSize = 14.sp,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp)
            )
        }
    }
}

/** 「3-12 周」这种周次文本 */
private fun weekRangeText(course: Course): String = formatWeeks(course.weeks)

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

/** 上课时段，如 "09:50 - 11:25"；课程没带时间时用节次表兜底 */
private fun courseTimeRange(course: Course): String? {
    if (course.beginTime.isNotBlank() && course.endTime.isNotBlank()) {
        return "${course.beginTime} - ${course.endTime}"
    }
    val start = PeriodTimes.of(course.beginSection)?.startTime
    val end = PeriodTimes.of(course.endSection)?.endTime
    return if (start != null && end != null) "$start - $end" else null
}
