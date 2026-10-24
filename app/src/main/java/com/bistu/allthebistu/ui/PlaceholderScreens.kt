package com.bistu.allthebistu.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.theme.HyperSpacing
import kotlinx.coroutines.launch

// 卡面配色（取自「信息科大 E 卡通」的橙金渐变）
private val CardGoldTop = Color(0xFFF9C24A)
private val CardGoldBottom = Color(0xFFDF7A1C)
private val CardBadgeRed = Color(0xFF8E1B1B)

/**
 * 生活 Tab。
 *
 * 卡面样式对齐学校「信息科大 E 卡通」：
 *  - 橙金渐变校园卡：校名 + 学生卡徽章 + 余额 + 宿舍电费 + 网费 + 状态/有效期
 *  - 卡片下方是「扫一扫」「校园码」两个入口（功能待接）
 *  - 再下面是近期流水
 *
 * 刻意**不展示**姓名、学号、学院、班级、照片——这些字段接口里虽然有（XH 等），
 * 但不落盘、不上屏，避免截图外泄。
 *
 * 宿舍电费 / 网费：门户目前没有暴露接口，先占位显示「--」，
 * 数据层 [PortalCardApi.Overview] 已经留好 electricity / networkFee 两个字段。
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "生活",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (AuthHolder.isLoggedIn) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "刷新",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { load() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (!AuthHolder.isLoggedIn) {
            // 未登录：给一张简单的引导卡，不做假数据
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "登录后查看校园卡",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "余额、电费、网费和消费流水都来自信息门户，需要先登录一次。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = {
                            context.startActivity(Intent(context, NativeLoginActivity::class.java))
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("去登录") }
                }
            }
        } else {
            // ── 校园卡 ──
            CampusCard(overview = overview, loading = loading)

            // ── 扫一扫 / 校园码 ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickAction(
                        label = "扫一扫",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "扫一扫还在接", Toast.LENGTH_SHORT).show()
                        }
                    ) { tint ->
                        ScanGlyph(tint = tint)
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(34.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    )

                    QuickAction(
                        label = "校园码",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "校园码还在接", Toast.LENGTH_SHORT).show()
                        }
                    ) { tint ->
                        BarcodeGlyph(tint = tint)
                    }
                }
            }

            // ── 近期流水 ──
            val records = overview?.records.orEmpty()
            if (records.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "近期流水",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = "本月 ${PortalCardApi.formatMoney(overview?.monthSpent ?: 0.0)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                                    color = if (r.amount > 0) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (records.size > 8) {
                            Text(
                                text = "仅显示最近 8 笔",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            overview?.error?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            overview?.let { ov ->
                TextButton(onClick = { showRaw = true }) {
                    Text("查看原始数据", fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(100.dp))
    }

    if (showRaw && overview != null) {
        Dialog(onDismissRequest = { showRaw = false }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
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

// ══════════════════════════════════════════════════════════════
// 校园卡
// ══════════════════════════════════════════════════════════════

@Composable
private fun CampusCard(
    overview: PortalCardApi.Overview?,
    loading: Boolean,
) {
    val balanceText = when {
        overview?.balance != null -> PortalCardApi.formatMoney(overview.balance)
        loading -> "…"
        else -> "--"
    }
    val monthText = PortalCardApi.formatMoney(overview?.monthSpent ?: 0.0)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(CardGoldTop, CardGoldBottom)))
    ) {
        // 右下角装饰弧线（模拟原卡面的建筑剪影层次）
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.width * 0.62f,
                center = Offset(size.width * 1.12f, size.height * 1.02f)
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = size.width * 0.44f,
                center = Offset(size.width * 1.06f, size.height * 0.96f)
            )
        }

        Column(modifier = Modifier.padding(18.dp)) {
            // 卡头：校徽 + 校名 + 学生卡徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                SchoolBadge()
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "北京信息科技大学",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "BEIJING INFORMATION SCIENCE & TECHNOLOGY UNIVERSITY",
                        fontSize = 5.5.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = CardBadgeRed
                ) {
                    Text(
                        text = "学生卡",
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // 余额
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "余额",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = balanceText,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "本月消费 $monthText",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(18.dp))

            // 宿舍电费 / 网费
            Row {
                CardStat(
                    label = "宿舍电费",
                    value = overview?.electricity?.let { PortalCardApi.formatMoney(it) } ?: "--"
                )
                Spacer(Modifier.width(36.dp))
                CardStat(
                    label = "网费",
                    value = overview?.networkFee?.let { PortalCardApi.formatMoney(it) } ?: "--"
                )
            }

            Spacer(Modifier.height(16.dp))

            // 状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "校园卡状态：${overview?.status?.ifBlank { "正常" } ?: "--"}",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.95f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "有效期 ${overview?.expiry?.ifBlank { "--" } ?: "--"}",
                    fontSize = 10.sp,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }
    }
}

@Composable
private fun CardStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

/** 简化校徽：白色双环 */
@Composable
private fun SchoolBadge() {
    Canvas(modifier = Modifier.size(32.dp)) {
        val r = size.minDimension / 2f
        drawCircle(Color.White, radius = r, style = Stroke(width = 1.8.dp.toPx()))
        drawCircle(Color.White, radius = r * 0.52f, style = Stroke(width = 1.2.dp.toPx()))
        drawCircle(Color.White, radius = r * 0.16f)
    }
}

// ══════════════════════════════════════════════════════════════
// 快捷入口
// ══════════════════════════════════════════════════════════════

@Composable
private fun QuickAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    glyph: @Composable (Color) -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        glyph(tint)
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 扫码框：四角 + 中间扫描线 */
@Composable
private fun ScanGlyph(tint: Color, size: Dp = 28.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.085f
        val c = s * 0.30f
        val o = sw / 2f
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

        // 左上
        ln(o, c, o, o); ln(o, o, c, o)
        // 右上
        ln(s - c, o, s - o, o); ln(s - o, o, s - o, c)
        // 左下
        ln(o, s - c, o, s - o); ln(o, s - o, c, s - o)
        // 右下
        ln(s - c, s - o, s - o, s - o); ln(s - o, s - o, s - o, s - c)
        // 扫描线
        ln(s * 0.27f, s * 0.5f, s * 0.73f, s * 0.5f)
    }
}

/** 条形码 */
@Composable
private fun BarcodeGlyph(tint: Color, size: Dp = 28.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val widths = listOf(0.05f, 0.09f, 0.04f, 0.11f, 0.04f, 0.08f, 0.05f)
        val gap = s * 0.045f
        var x = s * 0.10f
        widths.forEach { w ->
            val bw = s * w
            drawRect(
                color = tint,
                topLeft = Offset(x, s * 0.14f),
                size = Size(bw, s * 0.58f)
            )
            x += bw + gap
        }
        // 底部数字区示意
        drawRect(
            color = tint.copy(alpha = 0.55f),
            topLeft = Offset(s * 0.10f, s * 0.82f),
            size = Size(s * 0.80f, s * 0.05f)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 工具
// ══════════════════════════════════════════════════════════════

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
