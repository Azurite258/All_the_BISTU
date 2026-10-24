package com.bistu.allthebistu.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bistu.allthebistu.R
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.theme.HyperSpacing
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext

// 卡面配色（取自「信息科大 E 卡通」的橙金渐变）
private val CardGoldTop = Color(0xFFF9C24A)
private val CardGoldBottom = Color(0xFFDF7A1C)
private val CardBadgeRed = Color(0xFF8E1B1B)

/**
 * 生活 Tab。
 *
 * 卡面样式对齐学校「信息科大 E 卡通」：
 *  - 橙金渐变校园卡：真实校徽 + 校名 + 学生卡徽章 + 余额 + 宿舍电费 + 网费
 *  - 卡片下方是「扫一扫」「校园码」两个入口（功能待接）
 *  - 流水不在本页展示，**点击卡片进入明细页** [CardDetailScreen]
 *
 * 刻意**不展示**姓名、学号、学院、班级、照片。
 *
 * 宿舍电费 / 网费：门户目前没有暴露接口，先占位显示「--」，
 * 数据层 [PortalCardApi.Overview] 已留好 electricity / networkFee 字段。
 */
@Composable
fun LifeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<PortalCardApi.Overview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }

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
            showDetail = false
        }
    }

    // 明细页：整页覆盖
    if (showDetail && overview != null) {
        CardDetailScreen(
            overview = overview!!,
            onBack = { showDetail = false },
            modifier = modifier
        )
        return
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
                top = 14.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "生活",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.weight(1f))
            if (AuthHolder.isLoggedIn) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
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
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }

        if (!AuthHolder.isLoggedIn) {
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
            // ── 校园卡（点击进明细）──
            CampusCard(
                overview = overview,
                loading = loading,
                onClick = {
                    if (overview != null) showDetail = true
                }
            )

            // ── 扫一扫 / 校园码（压矮版）──
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QuickAction(
                        label = "扫一扫",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "扫一扫还在接", Toast.LENGTH_SHORT).show()
                        }
                    ) { tint -> ScanGlyph(tint = tint, size = 24.dp) }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(26.dp)
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    )

                    QuickAction(
                        label = "校园码",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            Toast.makeText(context, "校园码还在接", Toast.LENGTH_SHORT).show()
                        }
                    ) { tint -> BarcodeGlyph(tint = tint, size = 24.dp) }
                }
            }

            overview?.error?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (overview != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    TextButton(onClick = { showRaw = true }) {
                        Text("查看原始数据", fontSize = 11.sp)
                    }
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
    onClick: () -> Unit,
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
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(CardGoldTop, CardGoldBottom)))
            .clickable { onClick() }
    ) {
        // 右下角校园建筑剪影。底部正好抵住状态条上沿，既不悬空也不压文字。
        Canvas(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 40.dp)
                .width(150.dp)
                .height(76.dp)
        ) {
            drawCampusSkyline()
        }

        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            // 卡头：真实校徽 + 校名 + 学生卡徽章
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_school_badge),
                    contentDescription = "校徽",
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "北京信息科技大学",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "BEIJING INFORMATION SCIENCE & TECHNOLOGY UNIVERSITY",
                        fontSize = 5.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1
                    )
                }
                Surface(shape = RoundedCornerShape(50), color = CardBadgeRed) {
                    Text(
                        text = "学生卡",
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(13.dp))

            // 余额
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "余额",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = balanceText,
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
            Text(
                text = "本月消费 $monthText",
                fontSize = 10.5.sp,
                color = Color.White.copy(alpha = 0.85f)
            )

            Spacer(Modifier.height(12.dp))

            // 宿舍电费 / 网费
            Row {
                CardStat(
                    label = "宿舍电费",
                    value = overview?.electricity?.let { PortalCardApi.formatMoney(it) } ?: "--"
                )
                Spacer(Modifier.width(34.dp))
                CardStat(
                    label = "网费",
                    value = overview?.networkFee?.let { PortalCardApi.formatMoney(it) } ?: "--"
                )
            }

            Spacer(Modifier.height(11.dp))

            // 状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "校园卡状态：${overview?.status?.ifBlank { "正常" } ?: "--"}",
                    fontSize = 9.5.sp,
                    color = Color.White.copy(alpha = 0.95f)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "有效期 ${overview?.expiry?.ifBlank { "--" } ?: "--"}",
                    fontSize = 9.5.sp,
                    color = Color.White.copy(alpha = 0.95f)
                )
            }
        }
    }
}

/**
 * 右下角校园建筑剪影。
 *
 * 参考 E 卡通卡面：主楼 + 两侧副楼 + 窗户阵列 + 树，用比背景稍深的橙色画，
 * 窗户用半透明白，整体压在卡面右下角，不抢文字。
 */
private fun DrawScope.drawCampusSkyline() {
    val w = size.width
    val h = size.height
    val base = h

    val body = Color(0xFFA94D0A).copy(alpha = 0.26f)
    val bodyLight = Color(0xFFA94D0A).copy(alpha = 0.18f)
    val win = Color.White.copy(alpha = 0.42f)

    // 地面
    drawRect(
        color = bodyLight,
        topLeft = Offset(0f, base - h * 0.06f),
        size = Size(w, h * 0.06f)
    )

    // 左侧副楼
    val lx = w * 0.06f
    val lw = w * 0.17f
    val lh = h * 0.42f
    drawRect(bodyLight, Offset(lx, base - lh), Size(lw, lh))
    repeat(3) { row ->
        repeat(2) { col ->
            drawRect(
                win.copy(alpha = 0.32f),
                Offset(
                    lx + lw * (0.18f + col * 0.42f),
                    base - lh + lh * (0.16f + row * 0.25f)
                ),
                Size(lw * 0.22f, lh * 0.14f)
            )
        }
    }

    // 主楼（带屋顶塔楼）
    val mx = w * 0.30f
    val mw = w * 0.40f
    val mh = h * 0.74f
    drawRect(body, Offset(mx, base - mh), Size(mw, mh))
    // 塔楼
    drawRect(
        body,
        Offset(mx + mw * 0.40f, base - mh - h * 0.14f),
        Size(mw * 0.20f, h * 0.14f)
    )
    // 窗户阵列 4 列 x 4 行
    val cols = 4
    val rows = 4
    val padX = mw * 0.10f
    val cellW = (mw - padX * 2f) / cols
    val cellH = (mh * 0.78f) / rows
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            drawRect(
                win,
                Offset(
                    mx + padX + c * cellW + cellW * 0.18f,
                    base - mh + mh * 0.09f + r * cellH
                ),
                Size(cellW * 0.62f, cellH * 0.46f)
            )
        }
    }

    // 右侧副楼
    val rx = w * 0.74f
    val rw = w * 0.18f
    val rh = h * 0.50f
    drawRect(bodyLight, Offset(rx, base - rh), Size(rw, rh))
    repeat(3) { row ->
        repeat(2) { col ->
            drawRect(
                win.copy(alpha = 0.32f),
                Offset(
                    rx + rw * (0.18f + col * 0.42f),
                    base - rh + rh * (0.16f + row * 0.24f)
                ),
                Size(rw * 0.22f, rh * 0.13f)
            )
        }
    }

    // 树（靠左，在两栋楼之间）
    val tx = w * 0.26f
    drawRect(
        body,
        Offset(tx - w * 0.006f, base - h * 0.22f),
        Size(w * 0.012f, h * 0.22f)
    )
    drawCircle(
        color = bodyLight,
        radius = h * 0.15f,
        center = Offset(tx, base - h * 0.30f)
    )
    drawCircle(
        color = bodyLight,
        radius = h * 0.10f,
        center = Offset(tx - h * 0.11f, base - h * 0.22f)
    )
}

@Composable
private fun CardStat(label: String, value: String) {
    Column {
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = Color.White.copy(alpha = 0.8f)
        )
        Spacer(Modifier.height(1.dp))
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 明细页
// ══════════════════════════════════════════════════════════════

@Composable
private fun CardDetailScreen(
    overview: PortalCardApi.Overview,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏
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
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "一卡通明细",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // 汇总条
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HyperSpacing.pageHorizontal)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "卡内余额",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = overview.balance?.let { PortalCardApi.formatMoney(it) } ?: "--",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "本月消费",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = PortalCardApi.formatMoney(overview.monthSpent),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "全部流水 · ${overview.records.size} 笔",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = HyperSpacing.pageHorizontal)
        )

        Spacer(Modifier.height(6.dp))

        if (overview.records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "本月还没有消费记录",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    start = HyperSpacing.pageHorizontal,
                    end = HyperSpacing.pageHorizontal,
                    bottom = 90.dp
                )
            ) {
                items(overview.records) { r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = r.merchant,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${shortTime(r.time)} · 余额 ${
                                    PortalCardApi.formatMoney(r.balance)
                                }",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = PortalCardApi.formatMoney(r.amount, withSign = true),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (r.amount > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
                    )
                }
            }
        }
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
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        glyph(tint)
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/** 扫码框：四角 + 中间扫描线 */
@Composable
private fun ScanGlyph(tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.085f
        val c = s * 0.30f
        val o = sw / 2f
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, Offset(x1, y1), Offset(x2, y2), sw, StrokeCap.Round)

        ln(o, c, o, o); ln(o, o, c, o)
        ln(s - c, o, s - o, o); ln(s - o, o, s - o, c)
        ln(o, s - c, o, s - o); ln(o, s - o, c, s - o)
        ln(s - c, s - o, s - o, s - o); ln(s - o, s - o, s - o, s - c)
        ln(s * 0.27f, s * 0.5f, s * 0.73f, s * 0.5f)
    }
}

/** 条形码 */
@Composable
private fun BarcodeGlyph(tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val s = this.size.minDimension
        val widths = listOf(0.05f, 0.09f, 0.04f, 0.11f, 0.04f, 0.08f, 0.05f)
        val gap = s * 0.045f
        var x = s * 0.10f
        widths.forEach { w ->
            val bw = s * w
            drawRect(tint, Offset(x, s * 0.14f), Size(bw, s * 0.58f))
            x += bw + gap
        }
        drawRect(
            tint.copy(alpha = 0.55f),
            Offset(s * 0.10f, s * 0.82f),
            Size(s * 0.80f, s * 0.05f)
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
