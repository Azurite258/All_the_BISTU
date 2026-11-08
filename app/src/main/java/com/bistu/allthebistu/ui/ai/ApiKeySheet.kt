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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.ai.AiClient
import com.bistu.allthebistu.data.ai.AiConfig
import com.bistu.allthebistu.data.ai.AiProviders
import com.bistu.allthebistu.data.ai.AiResult
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.opaqueSurface
import kotlinx.coroutines.launch

/**
 * API Key 设置。
 *
 * 版式和「课程提醒」「新建日程」是同一套（2026-10-31 定的规矩）：
 * 白底、22sp 大标题、小节 15sp SemiBold、小节之间一条发丝线，不用卡片堆叠。
 *
 * 这里刻意**不做成登录页那种全屏页**：用户一年可能只进来改一两次，
 * 抽屉改完就关，不打断他正在看的东西。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiKeySheet(
    initial: AiConfig,
    onDismiss: () -> Unit,
    onSave: (apiKey: String, baseUrl: String, model: String) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val scope = rememberCoroutineScope()

    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var provider by remember { mutableIntStateOf(AiProviders.matchOf(initial)) }
    var showKey by remember { mutableStateOf(false) }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<AiResult?>(null) }

    // 用户改了地址或模型就不再算某个预设了 —— 否则点了别的 chip 会一头雾水
    fun markCustom() {
        val matched = AiProviders.matchOf(AiConfig(apiKey, baseUrl, model))
        provider = matched
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                .padding(top = 2.dp, bottom = 28.dp)
        ) {
            Text(
                text = "API Key",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "AI 学习计划要用到它。密钥只存在这台手机上，App 只把课程名、时间、教室发给模型服务，" +
                        "不会发送学号和姓名。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = colors.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))

            // ── 服务商 ──
            H2("服务商")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProviders.ALL.forEachIndexed { index, item ->
                    ProviderChip(
                        text = item.name,
                        selected = provider == index,
                        onClick = {
                            provider = index
                            testResult = null
                            // 「自定义」保留用户已经填的，其余两个直接把预设写进去
                            if (index < AiProviders.ALL.lastIndex) {
                                baseUrl = item.baseUrl
                                model = item.model
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = AiProviders.ALL.getOrNull(provider)?.note.orEmpty(),
                fontSize = 11.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(22.dp))

            // ── 密钥 ──
            H2("API Key")
            Spacer(Modifier.height(8.dp))
            FieldBox {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it.trim().take(120)
                            testResult = null
                        },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = if (showKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (apiKey.isEmpty()) {
                                Text(
                                    text = "sk-…（粘贴服务商后台的密钥）",
                                    fontSize = 14.sp,
                                    color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                            inner()
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (showKey) "隐藏" else "显示",
                        fontSize = 12.sp,
                        color = colors.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showKey = !showKey }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // ── 地址 ──
            H2("接口地址")
            Spacer(Modifier.height(8.dp))
            FieldBox {
                BasicTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it.trim().take(160)
                        testResult = null
                        markCustom()
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "填接口根地址即可，App 会自动接上 /chat/completions",
                fontSize = 11.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(22.dp))

            // ── 模型 ──
            H2("模型")
            Spacer(Modifier.height(8.dp))
            FieldBox {
                BasicTextField(
                    value = model,
                    onValueChange = {
                        model = it.trim().take(60)
                        testResult = null
                        markCustom()
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 14.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── 测试连接 ──
            // 单独做一个按钮：用户最怕的是「填完了不知道对不对」，
            // 直接点保存去生成计划，失败时分不清是 Key 错还是解析错
            SecondaryButton(
                text = if (testing) "正在测试…" else "测试连接",
                enabled = !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val result = AiClient.test(
                            AiConfig(apiKey = apiKey, baseUrl = baseUrl, model = model)
                        )
                        testResult = result
                        testing = false
                    }
                }
            )

            testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                when (result) {
                    is AiResult.Ok -> Text(
                        text = "连接正常，模型回复：${result.text.take(30)}",
                        fontSize = 12.sp,
                        color = colors.primary
                    )

                    is AiResult.Err -> Column {
                        Text(text = result.message, fontSize = 12.sp, color = colors.error)
                        if (result.detail.isNotBlank()) {
                            Text(
                                text = result.detail,
                                fontSize = 10.sp,
                                color = colors.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            PrimaryButton(
                text = "保存",
                enabled = apiKey.isNotBlank(),
                onClick = { onSave(apiKey, baseUrl, model) }
            )

            if (apiKey.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "清除密钥",
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSave("", baseUrl, model) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 小块件
// ══════════════════════════════════════════════════════════════

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f))
        )
    }
}

/** 小节标题：和提醒页、日程页同一档 15sp SemiBold */
@Composable
private fun H2(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun FieldBox(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val isDark = colors.background.luminance() < 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.innerRadius * 0.7f))
            .background(colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.05f))
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        content()
    }
}

@Composable
private fun ProviderChip(text: String, selected: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurface
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val metrics = LocalUiMetrics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
private fun SecondaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val metrics = LocalUiMetrics.current
    val isDark = colors.background.luminance() < 0.5f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(metrics.innerRadius))
            .background(colors.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (text.startsWith("正在")) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.6.dp,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, fontSize = 15.sp, color = colors.onSurface)
    }
}
