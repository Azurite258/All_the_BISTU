package com.bistu.allthebistu.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.theme.HyperSpacing
import kotlinx.coroutines.launch

/**
 * 生活 Tab。
 *
 * 目前上线：一卡通（余额 / 本月消费 / 近期流水），数据走信息门户，见 [PortalCardApi]。
 * 其余规划功能还是占位。
 */
@Composable
fun LifeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<PortalCardApi.Overview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    fun load() {
        val auth = AuthHolder.auth ?: return
        if (loading) return
        loading = true
        scope.launch {
            overview = PortalCardApi.fetch(auth)
            loading = false
        }
    }

    // 已登录时自动拉一次；退出登录则清掉展示
    LaunchedEffect(AuthHolder.isLoggedIn) {
        if (AuthHolder.isLoggedIn) {
            if (overview == null) load()
        } else {
            overview = null
        }
    }

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
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "生活",
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "校园里的吃穿住行，一站搞定",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // ── 一卡通 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "一卡通",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.weight(1f))
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (AuthHolder.isLoggedIn) {
                        Text(
                            text = "刷新",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { load() }
                                .padding(6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (!AuthHolder.isLoggedIn) {
                    Text(
                        text = "登录后可以查看卡内余额和消费流水",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            context.startActivity(
                                Intent(context, NativeLoginActivity::class.java)
                            )
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("去登录") }
                } else {
                    val ov = overview

                    // 余额 + 本月消费
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = ov?.balance?.let { PortalCardApi.formatMoney(it) } ?: "—",
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "本月消费 ${
                                PortalCardApi.formatMoney(ov?.monthSpent ?: 0.0)
                            }",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    ov?.error?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = err,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    val records = ov?.records.orEmpty()
                    if (records.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "近期流水",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))

                        records.take(8).forEach { r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = r.merchant,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = shortTime(r.time),
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = PortalCardApi.formatMoney(r.amount, withSign = true),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    // 充值是正数，用主色区分；消费保持中性
                                    color = if (r.amount > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (records.size > 8) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "仅显示最近 8 笔",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (ov != null && ov.error == null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "本月还没有消费记录",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (ov != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { showRaw = true }) {
                                Text("原始数据", fontSize = 11.sp)
                            }
                            TextButton(onClick = { copyToClipboard(context, ov.rawText()) }) {
                                Text("复制调试信息", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── 规划中 ──
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "规划中的功能",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(12.dp))
                listOf(
                    "宿舍电费余额与用量",
                    "图书馆座位查询",
                    "空教室查询",
                    "校园通知聚合",
                    "校历与节假日"
                ).forEach { item ->
                    Row(
                        modifier = Modifier.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    RoundedCornerShape(50)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = item,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(100.dp))
    }

    // ── 原始数据弹窗 ──
    if (showRaw && overview != null) {
        Dialog(onDismissRequest = { showRaw = false }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "原始响应",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer(
                        modifier = Modifier
                            .heightIn(max = 460.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = overview!!.rawText(),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            copyToClipboard(context, overview!!.rawText())
                        }) { Text("复制") }
                        TextButton(onClick = { showRaw = false }) { Text("关闭") }
                    }
                }
            }
        }
    }
}

/** "2026-10-24 12:30:00" → "10-24 12:30"；格式不认识就原样返回 */
private fun shortTime(raw: String): String {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(raw)
    return if (m != null) {
        "${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}"
    } else {
        raw
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("portal_card", text))
}

/**
 * 我的 Tab 占位页。
 *
 * 未来放：账号信息、设置、关于。
 */
@Composable
fun ProfileScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "我的",
        subtitle = "账号与偏好设置",
        plannedItems = listOf(
            "账号信息（姓名/学号/班级）",
            "登录与退出",
            "主题与配色设置",
            "字体选择",
            "课表显示设置",
            "关于与致谢"
        ),
        modifier = modifier
    )
}

/**
 * 通用占位页。
 */
@Composable
private fun PlaceholderScreen(
    title: String,
    subtitle: String,
    plannedItems: List<String>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 这几个页面没有 Scaffold，启用 edge-to-edge 后必须自己加 insets，
            // 否则大标题会压到状态栏（手机时间/信号图标）上。
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(
                start = HyperSpacing.pageHorizontal,
                end = HyperSpacing.pageHorizontal,
                top = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 超大标题
        Text(
            text = title,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(8.dp))

        // 规划清单卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "规划中的功能",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = "待开发",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                plannedItems.forEach { item ->
                    Row(
                        modifier = Modifier.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(5.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                    RoundedCornerShape(50)
                                )
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = item,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(100.dp))
    }
}
