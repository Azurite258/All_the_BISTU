package com.bistu.allthebistu.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ai.AiClient
import com.bistu.allthebistu.data.ai.AiConfig
import com.bistu.allthebistu.data.ai.AiResult
import com.bistu.allthebistu.data.ai.FreeSlot
import com.bistu.allthebistu.data.ai.PlanDraft
import com.bistu.allthebistu.data.ai.StudyPlanner
import com.bistu.allthebistu.data.event.EventTag
import com.bistu.allthebistu.ui.event.EventTags
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.styleCard
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 「AI 学习计划」卡片（学习页底部）。
 *
 * ## 为什么放在学习页而不是新开一个 Tab
 * 这条功能产出的东西会直接变成「今日日程」里的条目，放在同一个页面上，
 * 用户生成完低头就能看到结果，不用来回切。
 *
 * ## 生成 ≠ 落库
 * 模型给的草案先摆在卡片里，用户勾选后才转成 UserEvent。这是硬规矩：
 * 模型偶尔会把两件事排在同一时段、或者排到凌晨，自动落库等于把它的错误
 * 写进用户的日程，而且还得自己一条条删。
 */
@Composable
fun StudyPlanCard(
    courses: List<Course>,
    week: Int,
    todayDow: Int,
    config: AiConfig,
    onOpenSettings: () -> Unit,
    onSave: (List<PlanDraft>) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val scope = rememberCoroutineScope()

    // 初始阶段直接由「有没有 Key」决定：
    // 没配 Key 时给的是「去设置 / 先看示例」两个出口，而不是一个点了必然失败的主按钮
    var phase by remember(config.configured) {
        mutableStateOf(if (config.configured) PlanPhase.IDLE else PlanPhase.NEED_KEY)
    }
    var drafts by remember { mutableStateOf<List<PlanDraft>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var error by remember { mutableStateOf<AiResult.Err?>(null) }
    var isDemo by remember { mutableStateOf(false) }

    suspend fun run(useDemo: Boolean) {
        phase = PlanPhase.LOADING
        error = null
        isDemo = useDemo

        val now = System.currentTimeMillis()
        val nowMinutes = Calendar.getInstance().apply { timeInMillis = now }
            .let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val slots: List<FreeSlot> = StudyPlanner.freeSlots(
            courses = courses,
            week = week,
            todayDow = todayDow,
            nowMinutes = nowMinutes
        )

        if (slots.isEmpty()) {
            error = AiResult.Err("这周没有整块的空闲时间", "空档至少要 1 小时才会被排进来")
            phase = PlanPhase.ERROR
            return
        }

        if (useDemo) {
            val sample = StudyPlanner.samplePlan(courses, slots, now)
            drafts = sample
            selected = sample.map { it.startAt }.toSet()
            phase = PlanPhase.DONE
            return
        }

        val result = AiClient.chat(
            config = config,
            system = StudyPlanner.systemPrompt(),
            user = StudyPlanner.userPrompt(
                courses = courses,
                week = week,
                today = now,
                todayDow = todayDow,
                slots = slots,
                goal = ""
            )
        )

        when (result) {
            is AiResult.Err -> {
                error = result
                phase = PlanPhase.ERROR
            }

            is AiResult.Ok -> {
                val parsed = StudyPlanner.parse(result.text, now)
                if (parsed.isEmpty()) {
                    error = AiResult.Err("模型返回的格式看不懂", result.text.take(120))
                    phase = PlanPhase.ERROR
                } else {
                    drafts = parsed
                    selected = parsed.map { it.startAt }.toSet()
                    phase = PlanPhase.DONE
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .styleCard()
            .padding(16.dp)
    ) {
        // ── 标题行 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 学习计划",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(text = "Beta", fontSize = 9.sp, color = colors.primary)
            }
            Spacer(Modifier.weight(1f))
            if (config.configured) {
                Text(
                    text = config.maskedKey,
                    fontSize = 10.sp,
                    color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Text(
            text = if (phase == PlanPhase.DONE && isDemo) {
                "示例数据 · 按你这周的真实空档排的，配好 Key 后换成模型生成"
            } else {
                "按这周的空闲时间排一份可执行的计划，生成后挑着加进日程"
            },
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = colors.onSurfaceVariant
        )

        Spacer(Modifier.height(14.dp))

        when (phase) {
            PlanPhase.NEED_KEY -> {
                // 拆成两行而不是一整句：整句 30 个字会在一行里断在「真实计 / 划。」，
                // 换个机型断点还会变，看着像没排过版
                Text(
                    text = "还没配置 API Key",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "密钥只存本机，配好后就能生成真实计划",
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidButton(
                        text = "去设置",
                        modifier = Modifier.weight(1f),
                        onClick = onOpenSettings
                    )
                    GhostButton(
                        text = "先看示例",
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { run(useDemo = true) } }
                    )
                }
            }

            PlanPhase.IDLE -> {
                SolidButton(
                    text = "生成本周计划",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { scope.launch { run(useDemo = false) } }
                )
            }

            PlanPhase.LOADING -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.8.dp,
                        color = colors.primary
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = "正在读课表、排时间…",
                        fontSize = 12.5.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            PlanPhase.ERROR -> {
                error?.let { err ->
                    Text(text = err.message, fontSize = 13.sp, color = colors.error)
                    if (err.detail.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = err.detail,
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            color = colors.onSurfaceVariant.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidButton(
                        text = "重试",
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { run(useDemo = !config.configured) } }
                    )
                    if (!config.configured) {
                        GhostButton(
                            text = "去设置",
                            modifier = Modifier.weight(1f),
                            onClick = onOpenSettings
                        )
                    } else {
                        GhostButton(
                            text = "看示例",
                            modifier = Modifier.weight(1f),
                            onClick = { scope.launch { run(useDemo = true) } }
                        )
                    }
                }
            }

            PlanPhase.DONE -> {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    drafts.forEach { draft ->
                        val checked = draft.startAt in selected
                        DraftRow(
                            draft = draft,
                            checked = checked,
                            onToggle = {
                                selected = if (checked) selected - draft.startAt
                                else selected + draft.startAt
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidButton(
                        text = if (selected.isEmpty()) "先选几条" else "加入日程（${selected.size}）",
                        enabled = selected.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onSave(drafts.filter { it.startAt in selected })
                            // 存完回到初始态：这些条目已经在「今日日程」里了，
                            // 再留着勾选框用户会以为没保存成功
                            phase = PlanPhase.IDLE
                            drafts = emptyList()
                            selected = emptySet()
                        }
                    )
                    GhostButton(
                        text = "重新生成",
                        modifier = Modifier.weight(1f),
                        onClick = { scope.launch { run(useDemo = isDemo) } }
                    )
                }
            }
        }
    }
}

/**
 * 一条草案。
 *
 * 左边勾选框、右边两行（标题 + 时间/标签），和「今日日程」的时间线是两种版式：
 * 那边看的是「今天怎么过」，这边是「要挑哪几条」，所以勾选框必须在。
 */
@Composable
private fun DraftRow(draft: PlanDraft, checked: Boolean, onToggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = EventTags.colorOf(draft.tag)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) colors.primary else colors.onSurface.copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = draft.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = draft.dayTimeLabel(),
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
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SolidButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val metrics = LocalUiMetrics.current
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(metrics.innerRadius * 0.75f))
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.30f)
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.5.sp,
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
            .height(40.dp)
            .clip(RoundedCornerShape(metrics.innerRadius * 0.75f))
            .background(colors.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, fontSize = 13.5.sp, color = colors.onSurface)
    }
}

/** 卡片内部的四个阶段 */
private enum class PlanPhase { NEED_KEY, IDLE, LOADING, ERROR, DONE }
