package com.bistu.allthebistu.ui.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ai.AiClient
import com.bistu.allthebistu.data.ai.AiConfig
import com.bistu.allthebistu.data.ai.AiResult
import com.bistu.allthebistu.data.ai.PlanDraft
import com.bistu.allthebistu.data.ai.StudyPlanner
import com.bistu.allthebistu.data.event.EventTag
import com.bistu.allthebistu.ui.event.EventTags
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.pageBackgroundColor
import com.bistu.allthebistu.ui.theme.styleCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * AI 学习计划 —— 二级页。
 *
 * 学习页只留一张两行的小入口卡，真正的操作都在这一页：
 * 一项一项添加要学的内容 → 选时间（**今天几点到几点自己定** / 几天内）
 * → 生成 → 勾选要的条目 → **勾上「加入日程」并确认**才落库。
 *
 * ## 两种跨度，两套提示词
 * - **今天内**：解决「怎么切段效率高」——先难后易、段间留休息、最后留时间回顾。
 * - **几天内**：解决「什么时候复习什么」——按遗忘曲线，当天学、次日复习、
 *   第 3~4 天再复习，并且**顺着课表排**（课多的天少排、复习贴着当天那门课的晚上）。
 *
 * ## 为什么「今天内」不给 3 小时 / 5 小时这种预设
 * 用户 2026-11-04 明确要求去掉：每个人每天的空档不一样，给两档固定值等于逼人选一个
 * 不合适的数。现在直接让用户点开始和结束时刻，默认值只作为「懒得改时的合理猜测」。
 */
@Composable
fun AiPlanScreen(
    courses: List<Course>,
    week: Int,
    todayDow: Int,
    config: AiConfig,
    studyNote: String,
    onStudyNoteChange: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    onSavePlan: (List<PlanDraft>) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    // ── 输入：一项一项加，加完是一条小胶囊 ──
    // 用 rememberSaveable：Activity 被重建（旋转、切深色、系统回收）时，
    // 用户已经敲进去的几项不该跟着没
    var items by rememberSaveable(
        stateSaver = listSaver(save = { it }, restore = { it })
    ) { mutableStateOf(listOf<String>()) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var draftText by rememberSaveable { mutableStateOf("") }

    val now = remember { System.currentTimeMillis() }
    val nowMinutes = remember(now) {
        Calendar.getInstance().apply { timeInMillis = now }
            .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    }
    val autoStart = remember(now) { ceilToQuarter(now + 5 * 60_000L) }

    // ── 时长：今天内只有一个「自选时段」，不再有固定几小时的预设 ──
    var duration by rememberSaveable(
        stateSaver = DurationSaver
    ) { mutableStateOf<Duration>(Duration.defaultRange(now, autoStart)) }
    var rangeDialog by rememberSaveable { mutableStateOf(false) }
    var daysDialog by rememberSaveable { mutableStateOf(false) }

    // ── 个人情况：长期偏好，跨次保留 ──
    var note by rememberSaveable { mutableStateOf(studyNote) }
    // 每敲一个字就写一次 DataStore 太浪费，停下来 600ms 再落盘
    LaunchedEffect(note) {
        if (note.trim() != studyNote.trim()) {
            delay(600)
            onStudyNoteChange(note)
        }
    }

    // ── 生成 ──
    var loading by remember { mutableStateOf(false) }
    var drafts by remember { mutableStateOf<List<PlanDraft>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var error by remember { mutableStateOf<AiResult.Err?>(null) }
    var demo by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    // 生成完是否要写进日程：默认不勾，必须用户主动确认
    var confirmed by remember { mutableStateOf(false) }

    fun addItem() {
        val text = draftText.trim()
        if (text.isEmpty()) {
            adding = false
            return
        }
        if (items.size < 8 && items.none { it == text }) {
            items = items + text.take(30)
        }
        draftText = ""
        adding = false
        hint = null
    }

    suspend fun generate(useDemo: Boolean) {
        if (items.isEmpty()) {
            hint = "先添加至少一项要学的内容"
            return
        }
        hint = null
        loading = true
        error = null
        drafts = emptyList()
        selected = emptySet()
        confirmed = false
        demo = useDemo

        val content = items.joinToString("、")

        val result: List<PlanDraft> = when (val d = duration) {
            is Duration.Range -> {
                val (startAt, minutes) = d.window(now, autoStart)
                if (useDemo) {
                    StudyPlanner.sampleShortPlan(content, startAt, minutes)
                } else {
                    when (
                        val r = AiClient.chat(
                            config = config,
                            system = StudyPlanner.shortSystemPrompt(),
                            user = StudyPlanner.shortUserPrompt(content, startAt, minutes, note),
                            maxTokens = 900
                        )
                    ) {
                        is AiResult.Err -> {
                            error = r; loading = false; return
                        }

                        is AiResult.Ok -> StudyPlanner.parse(r.text, now)
                    }
                }
            }

            is Duration.Days -> {
                if (useDemo) {
                    StudyPlanner.sampleMultiDayPlan(content, d.days, now)
                } else {
                    val slots = StudyPlanner.freeSlots(
                        courses = courses,
                        week = week,
                        todayDow = todayDow,
                        nowMinutes = nowMinutes,
                        horizonDays = d.days,
                        // 多日模式放低门槛，两节课之间那 45 分钟也能排一轮复习
                        minSlot = StudyPlanner.MULTI_MIN_SLOT
                    )
                    when (
                        val r = AiClient.chat(
                            config = config,
                            system = StudyPlanner.multiDaySystemPrompt(),
                            user = StudyPlanner.multiDayUserPrompt(
                                content = content,
                                days = d.days,
                                courses = courses,
                                week = week,
                                today = now,
                                todayDow = todayDow,
                                slots = slots,
                                note = note
                            ),
                            maxTokens = 1400
                        )
                    ) {
                        is AiResult.Err -> {
                            error = r; loading = false; return
                        }

                        is AiResult.Ok -> StudyPlanner.parse(r.text, now)
                    }
                }
            }
        }

        loading = false
        if (result.isEmpty()) {
            error = AiResult.Err("没能排出计划", "把内容拆得更具体一点，或者换个时间跨度再试")
            return
        }
        drafts = result
        selected = result.map { it.startAt }.toSet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(pageBackgroundColor())
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ── 顶栏：和「外观」「界面风格」二级页同一个样式 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.onBackground
                )
            }
            Text(
                text = "AI 学习计划",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(
                    start = metrics.pagePadding,
                    end = metrics.pagePadding,
                    top = 6.dp,
                    bottom = 28.dp
                )
        ) {
            // ── 1. 要学什么（一项一条胶囊） ──
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("要学什么")
                    Spacer(Modifier.weight(1f))
                    if (items.isNotEmpty()) {
                        Text(
                            text = "${items.size} 项",
                            fontSize = 12.sp,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                if (items.isEmpty() && !adding) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "一项一项加，比如「高数 极限与导数」「英语四级 Unit 3」，AI 会按项分配时间。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = colors.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(10.dp))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEach { item ->
                        ItemCapsule(
                            text = item,
                            onRemove = { items = items - item }
                        )
                    }

                    if (adding) {
                        AddingCapsule(
                            value = draftText,
                            onChange = { if (it.length <= 30) draftText = it },
                            onConfirm = { addItem() },
                            onCancel = {
                                adding = false
                                draftText = ""
                            }
                        )
                    } else if (items.size < 8) {
                        AddCapsule(onClick = { adding = true })
                    }
                }
            }

            Spacer(Modifier.height(metrics.cardGap))

            // ── 2. 可用时间 ──
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("可用时间")
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = duration.summary(now, autoStart),
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
                DurationChips(
                    current = duration,
                    onPick = { duration = it },
                    onCustomRange = { rangeDialog = true },
                    onCustomDays = { daysDialog = true }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (duration is Duration.Days) {
                        "按遗忘曲线排「学 + 复习」，并顺着课表分配：课多的天少排，没课的天多排"
                    } else {
                        "把内容切成 30~60 分钟一段，中间留短休息，最后一段时间用来回顾"
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.85f)
                )
            }

            Spacer(Modifier.height(metrics.cardGap))

            // ── 3. 我的情况（长期偏好，可选） ──
            // 和「要学什么」分开两张卡：上面是这一次的内容，每次都要重填；
            // 这张存下来，以后每次生成都自动带上，用户不必反复描述自己
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("我的情况")
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${note.length}/200",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "可选。写下来会一直记着，比如「晚上 10 点后不学」「周三有社团」「高数期末 11-20」。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                NoteField(value = note, onChange = { note = it.take(200) })
            }

            // ── 4. 未配置 Key 的提示 ──
            if (!config.configured) {
                Spacer(Modifier.height(metrics.cardGap))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(metrics.innerRadius * 0.7f))
                        .background(colors.primary.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "还没配置 API Key，先看示例",
                        fontSize = 12.sp,
                        color = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "去设置",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onOpenSettings)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            hint?.let {
                Spacer(Modifier.height(10.dp))
                Text(text = it, fontSize = 12.sp, color = colors.error)
            }

            Spacer(Modifier.height(14.dp))

            // ── 5. 生成 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    text = when {
                        loading -> "正在排…"
                        config.configured -> "生成计划"
                        else -> "生成示例"
                    },
                    enabled = !loading,
                    modifier = Modifier.weight(1f),
                    onClick = { scope.launch { generate(useDemo = !config.configured) } }
                )
                if (config.configured && drafts.isNotEmpty()) {
                    GhostButton(
                        text = "重新生成",
                        modifier = Modifier.weight(0.6f),
                        onClick = { scope.launch { generate(useDemo = false) } }
                    )
                }
            }

            if (loading) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.8.dp,
                        color = colors.primary
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (duration is Duration.Days) "正在看课表、排复习节奏…" else "正在切分时段…",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (drafts.isEmpty() && !loading && error == null) {
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "生成后计划会按天列在这里。勾选要的条目，再勾上「加入日程」并确认，才会写进「今日日程」。",
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.75f)
                )
            }

            error?.let { err ->
                Spacer(Modifier.height(12.dp))
                Text(text = err.message, fontSize = 13.sp, color = colors.error)
                if (err.detail.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = err.detail,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // ── 6. 结果 ──
            if (drafts.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "计划",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground
                    )
                    if (demo) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "示例",
                            fontSize = 10.sp,
                            color = colors.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(colors.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${drafts.size} 条 · 已选 ${selected.size}",
                        fontSize = 11.sp,
                        color = colors.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(8.dp))

                drafts.groupBy { StudyPlanner.startOfDay(it.startAt, 0) }
                    .toSortedMap()
                    .forEach { (day, dayItems) ->
                        Text(
                            text = dayLabel(day, now),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .styleCard()
                        ) {
                            dayItems.forEach { draft ->
                                DraftRow(
                                    draft = draft,
                                    checked = draft.startAt in selected,
                                    onToggle = {
                                        selected = if (draft.startAt in selected) {
                                            selected - draft.startAt
                                        } else {
                                            selected + draft.startAt
                                        }
                                    }
                                )
                            }
                        }
                    }

                Spacer(Modifier.height(14.dp))

                // ── 7. 是否真的写进日程 ──
                // 单独一个勾选框：「挑了哪几条」和「要不要真写进课表」是两件事，
                // 前面是挑内容，这一步是拍板，不勾就只是预览。
                val isMultiDay = duration is Duration.Days
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .styleCard()
                        .clickable { confirmed = !confirmed }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CheckBoxMark(checked = confirmed)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isMultiDay) "加入日程" else "加入今日日程",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = colors.onSurface
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "勾上并确认后才写进日历，不勾只是预览",
                            fontSize = 11.sp,
                            color = colors.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                PrimaryButton(
                    text = when {
                        selected.isEmpty() -> "先勾选几条计划"
                        !confirmed -> "勾选「加入日程」后确认"
                        isMultiDay -> "确认加入（${selected.size}）"
                        else -> "加入今日日程（${selected.size}）"
                    },
                    enabled = confirmed && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onSavePlan(drafts.filter { it.startAt in selected })
                        drafts = emptyList()
                        selected = emptySet()
                        confirmed = false
                    }
                )
            }
        }
    }

    // ── 自定义时间弹窗 ──
    if (rangeDialog) {
        CustomRangeDialog(
            // 从「几天内」切回来时没有现成的 Range，用默认值（现在 +5 分钟起 2 小时）
            initial = duration as? Duration.Range ?: Duration.defaultRange(now, autoStart),
            onDismiss = { rangeDialog = false },
            onConfirm = {
                duration = it
                rangeDialog = false
            }
        )
    }

    if (daysDialog) {
        CustomDaysDialog(
            initial = (duration as? Duration.Days)?.days ?: 3,
            onDismiss = { daysDialog = false },
            onConfirm = {
                duration = Duration.Days(it)
                daysDialog = false
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 时间跨度
// ══════════════════════════════════════════════════════════════

/** 两种跨度：今天几点到几点（自己选） / 几天内 */
private sealed interface Duration {

    /** 今天的时间段，分钟表示（14:00 → 840） */
    data class Range(val startMinutes: Int, val endMinutes: Int) : Duration

    data class Days(val days: Int) : Duration

    /** 算出「从哪一刻开始、一共多少分钟」；[autoStart] 是「现在 + 5 分钟」取整的结果 */
    fun window(now: Long, autoStart: Long): Pair<Long, Int> = when (this) {
        is Range -> {
            val dayStart = StudyPlanner.startOfDay(now, 0)
            (dayStart + startMinutes * 60_000L) to (endMinutes - startMinutes).coerceAtLeast(30)
        }

        is Days -> autoStart to 0
    }

    fun summary(now: Long, autoStart: Long): String = when (this) {
        is Range -> "今天 ${clock(startMinutes)}–${clock(endMinutes)} · ${
            spanText(endMinutes - startMinutes)
        }"

        is Days -> {
            val last = StudyPlanner.startOfDay(now, days - 1)
            "${dateShort(now)} → ${dateShort(last)} · $days 天"
        }
    }

    fun label(): String = when (this) {
        is Range -> "${clock(startMinutes)}–${clock(endMinutes)}"
        is Days -> "$days 天"
    }

    companion object {
        fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

        /** "5 小时" / "5 小时 30 分" / "45 分钟" */
        fun spanText(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h > 0 && m > 0 -> "$h 小时 $m 分"
                h > 0 -> "$h 小时"
                else -> "$m 分钟"
            }
        }

        /**
         * 默认时间段：从「现在 + 5 分钟」取整到一刻钟起，往后 2 小时。
         *
         * 只作为**懒得改时的合理猜测**，不是预设档位 —— 用户随时点开两个时间轮自己调。
         * 两处兜底：太晚（22:00 之后打开）就整段往前挪，保证至少 90 分钟；
         * 再短的话窗口里排不下任何东西，默认值就没意义了。
         */
        fun defaultRange(now: Long, autoStart: Long): Range {
            val c = Calendar.getInstance().apply { timeInMillis = autoStart }
            val from = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
            val end = (from + 120).coerceAtMost(23 * 60 + 59)
            val start = if (end - from < 90) (end - 90).coerceAtLeast(0) else from
            return Range(start, end)
        }
    }
}

/**
 * [Duration] 的存档方式：`"R:555:615"`（今天 09:15–10:15）/ `"D:3"`（跨 3 天）。
 *
 * 为什么不用 Parcelable：这个 sealed interface 只有两个分支、四个 Int 字段，
 * 编成一个短字符串最省事，也不用给 UI 层加 `@Parcelize` 依赖。
 */
private val DurationSaver: Saver<Duration, String> = Saver(
    save = { d ->
        when (d) {
            is Duration.Range -> "R:${d.startMinutes}:${d.endMinutes}"
            is Duration.Days -> "D:${d.days}"
        }
    },
    restore = { raw ->
        val parts = raw.split(":")
        when (parts.getOrNull(0)) {
            "R" -> Duration.Range(
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 60
            )

            "D" -> Duration.Days(parts.getOrNull(1)?.toIntOrNull() ?: 3)
            else -> null
        }
    }
)

private fun dateShort(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    return "%02d-%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun dayLabel(dayMillis: Long, todayMillis: Long): String {
    val diff = ((StudyPlanner.startOfDay(dayMillis, 0) - StudyPlanner.startOfDay(todayMillis, 0)) /
            86_400_000L).toInt()
    val prefix = when (diff) {
        0 -> "今天 · "
        1 -> "明天 · "
        2 -> "后天 · "
        else -> ""
    }
    val dow = PlanDraft.dayOfWeek(dayMillis)
    return "$prefix${dateShort(dayMillis)} ${StudyPlanner.weekdayName(dow)}"
}

/**
 * 时长选择。
 *
 * 两行分开：上一行是「今天内」（切段问题），下一行是「跨几天」（复习节奏问题）。
 * 上一行**只有一个胶囊**：点开就是开始/结束两个时间轮。这里曾经有「3 小时」「5 小时」
 * 两个预设，用户 2026-11-04 明确要求去掉 —— 每天空档不一样，给固定档等于逼人将就。
 * 下一行保留 1 / 3 / 7 天，是因为「跨几天」本身就是个整数直觉，不存在将就。
 */
@Composable
private fun DurationChips(
    current: Duration,
    onPick: (Duration) -> Unit,
    onCustomRange: () -> Unit,
    onCustomDays: () -> Unit
) {
    val multiPresets = listOf(Duration.Days(1), Duration.Days(3), Duration.Days(7))
    val rangeSelected = current is Duration.Range
    val customDaysSelected = current is Duration.Days && current.days !in listOf(1, 3, 7)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("今天内")
            Chip(
                text = if (rangeSelected) current.label() else "选择时段",
                selected = rangeSelected,
                onClick = onCustomRange
            )
            if (!rangeSelected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "点一下选开始和结束时间",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Caption("跨几天")
            multiPresets.forEach { preset ->
                Chip(
                    text = preset.label(),
                    selected = preset == current,
                    onClick = { onPick(preset) }
                )
                Spacer(Modifier.width(8.dp))
            }
            Chip(
                text = if (customDaysSelected) current.label() else "自定义",
                selected = customDaysSelected,
                onClick = onCustomDays
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Box(modifier = Modifier.width(48.dp)) {
        Text(
            text = text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(metrics.innerRadius * 0.6f))
            .background(
                if (selected) colors.primary.copy(alpha = 0.14f)
                else colors.onSurface.copy(alpha = 0.05f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 「要学什么」的胶囊
// ══════════════════════════════════════════════════════════════

/** 已经加进去的一项：圆角胶囊 + 右侧 ✕ */
@Composable
private fun ItemCapsule(text: String, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.primary.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

/** 「+ 添加一项」：浅底 + 细描边，和整体材质统一 */
@Composable
private fun AddCapsule(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.onSurface.copy(alpha = 0.04f))
            .border(
                width = 0.9.dp,
                color = colors.onSurface.copy(alpha = 0.12f),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "+", fontSize = 14.sp, color = colors.primary)
        Spacer(Modifier.width(6.dp))
        Text(text = "添加一项", fontSize = 13.sp, color = colors.primary)
    }
}

/** 正在输入的那一条：同一个胶囊里放输入框，回车 / 对勾 即加入 */
@Composable
private fun AddingCapsule(
    value: String,
    onChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .clip(RoundedCornerShape(50))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .border(
                width = 0.9.dp,
                color = colors.primary.copy(alpha = 0.45f),
                shape = RoundedCornerShape(50)
            )
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onConfirm() }),
            modifier = Modifier.widthIn(min = 120.dp).weight(1f, fill = false),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        text = "例如：高数 极限与导数",
                        fontSize = 13.sp,
                        color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                inner()
            }
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.primary)
                .clickable(onClick = onConfirm),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "添加",
                tint = colors.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onCancel),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "✕", fontSize = 11.sp, color = colors.onSurfaceVariant)
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 自定义弹窗
// ══════════════════════════════════════════════════════════════

/**
 * 自定义「今天几点到几点」。
 *
 * 用系统的 TimePicker 而不是手输：手输 24 小时制很容易把 9:30 和 21:30 打混。
 * 默认填「现在 + 5 分钟」到「+3 小时」，用户改两下就成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomRangeDialog(
    initial: Duration.Range,
    onDismiss: () -> Unit,
    onConfirm: (Duration.Range) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var startMinutes by remember { mutableIntStateOf(initial.startMinutes) }
    var endMinutes by remember { mutableIntStateOf(initial.endMinutes) }
    var picking by remember { mutableIntStateOf(0) }   // 0 = 没在选，1 = 选开始，2 = 选结束

    val valid = endMinutes > startMinutes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今天的时间段", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                TimeRow(label = "开始", minutes = startMinutes, onClick = { picking = 1 })
                Spacer(Modifier.height(8.dp))
                TimeRow(label = "结束", minutes = endMinutes, onClick = { picking = 2 })
                Spacer(Modifier.height(10.dp))
                Text(
                    text = if (valid) {
                        "共 ${Duration.spanText(endMinutes - startMinutes)}"
                    } else {
                        "结束时间要晚于开始时间"
                    },
                    fontSize = 11.5.sp,
                    color = if (valid) colors.onSurfaceVariant else colors.error
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onConfirm(Duration.Range(startMinutes, endMinutes)) }
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (picking != 0) {
        val isStart = picking == 1
        val current = if (isStart) startMinutes else endMinutes
        val state = rememberTimePickerState(
            initialHour = current / 60,
            initialMinute = current % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { picking = 0 },
            title = { Text(if (isStart) "开始时间" else "结束时间", fontSize = 16.sp) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val minutes = state.hour * 60 + state.minute
                    if (isStart) startMinutes = minutes else endMinutes = minutes
                    picking = 0
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text("取消") } }
        )
    }
}

@Composable
private fun TimeRow(label: String, minutes: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.innerRadius * 0.6f))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 13.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(
            text = Duration.clock(minutes),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = colors.onSurface
        )
    }
}

@Composable
private fun CustomDaysDialog(
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 2..14

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跨几天", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() }.take(2) },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 22.sp, color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(text = "天", fontSize = 15.sp, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Text(text = "2 ~ 14 天", fontSize = 11.sp, color = colors.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { value?.let(onConfirm) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ══════════════════════════════════════════════════════════════
// 小块件
// ══════════════════════════════════════════════════════════════

/**
 * 多行自由输入（「我的情况」用）。
 *
 * 没用 OutlinedTextField：那套自带 label、边框和最小高度，和这一页其余分节卡
 * 「浅底 + 无边框」不是一路。这里就是一块浅底区域。
 */
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit) {
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
            Text(
                text = "不填也行，写给自己看的",
                fontSize = 13.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.55f)
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            textStyle = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
        )
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .styleCard()
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/** 勾选框（条目选择和「加入日程」那个共用一套画法） */
@Composable
private fun CheckBoxMark(checked: Boolean, size: Int = 18) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) colors.primary else colors.onSurface.copy(alpha = 0.06f))
            .border(
                width = if (checked) 0.dp else 0.8.dp,
                color = colors.onSurface.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size((size - 5).dp)
            )
        }
    }
}

@Composable
private fun DraftRow(draft: PlanDraft, checked: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = EventTags.colorOf(draft.tag)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CheckBoxMark(checked = checked)
        Spacer(Modifier.width(11.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = draft.startClock,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                )
                Text(
                    text = "–${draft.endClock}",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(tint)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = EventTag.nameOf(draft.tag),
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = draft.title,
                fontSize = 14.sp,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (draft.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = draft.note,
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
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
            .clip(RoundedCornerShape(metrics.innerRadius * 0.8f))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.30f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun GhostButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius * 0.8f))
            .background(colors.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 14.sp, color = colors.onSurface)
    }
}

/** 向上取整到最近的一刻钟，免得起点是 14:47 这种看着别扭的时刻 */
private fun ceilToQuarter(millis: Long): Long {
    val c = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val minute = c.get(Calendar.MINUTE)
    val add = (15 - minute % 15) % 15
    c.add(Calendar.MINUTE, add)
    return c.timeInMillis
}
