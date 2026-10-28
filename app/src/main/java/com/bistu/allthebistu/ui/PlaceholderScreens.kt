package com.bistu.allthebistu.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bistu.allthebistu.R
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.data.prefs.SettingsStore
import com.bistu.allthebistu.ui.life.EmptyRoomCard
import com.bistu.allthebistu.ui.life.LibrarySeatCard
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.styleCard
import com.bistu.allthebistu.ui.theme.pageBackgroundColor
import com.bistu.allthebistu.ui.wechat.WeChatEntry
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
 *  - 橙金渐变校园卡：真实校徽 + 校名 + 学生卡徽章 + 余额 + 本月消费 + 网费充值
 *  - 流水不在本页展示，**点击卡片进入明细页** [CardDetailScreen]
 *
 * 「扫一扫」「校园码」两个入口已删（2026-10-27）：都只能把微信拉起来，
 * 跳不到具体功能，留着是块死按钮。
 *
 * 刻意**不展示**姓名、学号、学院、班级、照片。
 *
 * 网费：门户没有余额接口（`items` 里只有邮件/工资/手机号/一卡通/知会五张卡），
 * 但网费充值会落进一卡通流水，所以从流水里按商户名识别，显示本月充值合计。
 * 本月一笔都没有时显示「--」，不编 0。
 *
 * 电费：**已拆除**（2026-10-24）。E 卡通那套查询要微信企业号 OAuth 换的 openid，
 * App 的 CAS 会话换不出来，实测校园网内外都一样被拦，详见交接文档 17.3。
 * 为免误导，卡面不再摆电费，改由第一次进本页时弹一次说明。
 */
@Composable
fun LifeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var overview by remember { mutableStateOf<PortalCardApi.Overview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    /** 电费说明弹窗：第一次进生活页弹一次 */
    var showElectricNotice by remember { mutableStateOf(false) }

    val settingsStore = remember { SettingsStore(context) }

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

    // 电费说明：只弹一次（标记在 DataStore，重装/清数据才会再弹）
    LaunchedEffect(Unit) {
        if (settingsStore.shouldShowElectricNotice()) {
            showElectricNotice = true
            settingsStore.markElectricNoticeShown()
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
            .background(pageBackgroundColor())
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(
                start = LocalUiMetrics.current.pagePadding,
                end = LocalUiMetrics.current.pagePadding,
                top = 14.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "生活",
                fontSize = LocalUiMetrics.current.titleSize,
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
                        text = "余额、网费和消费流水都来自信息门户，需要先登录一次。",
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

            overview?.error?.let { err ->
                Text(
                    text = err,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── 图书馆座位（接口公开，未登录也显示）──
        LibrarySeatCard()

        // ── 空教室（教务系统反查，要登录）──
        EmptyRoomCard()

        // ── 查看原始数据：固定在整页最底部，不插在卡片中间 ──
        if (overview != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { showRaw = true }) {
                    Text("查看原始数据", fontSize = 12.sp)
                }
            }
        }

        Spacer(Modifier.height(100.dp))
    }

    // ── 电费说明：第一次进生活页弹一次，讲清楚为什么这里没有电费 ──
    if (showElectricNotice) {
        AlertDialog(
            onDismissRequest = { showElectricNotice = false },
            confirmButton = {
                TextButton(onClick = { showElectricNotice = false }) { Text("知道了") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showElectricNotice = false
                        if (!WeChatEntry.openWeChat(context)) WeChatEntry.openWeCom(context)
                    }
                ) { Text("去微信看看") }
            },
            title = {
                Text(
                    text = "关于电费",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column {
                    Text(
                        text = "我们试过几种办法，想把宿舍电费也放进来，但暂时还没能做到——" +
                            "电费余额只显示在微信企业号的「信息科大E卡通」里，" +
                            "那套验证只认微信，App 这边进不去。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "与其摆一个不准的数字，不如先不摆。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "要看电费：微信 → 通讯录 → 我的企业 → 信息科大E卡通 → 购电入口。",
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
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
            .clip(RoundedCornerShape(LocalUiMetrics.current.cardRadius))
            .background(Brush.linearGradient(listOf(CardGoldTop, CardGoldBottom)))
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 20.dp)) {
            // 卡头：真实校徽 + 校名 + 学生卡徽章
            // 校名区固定 32dp 高（= 校徽高度）并两端对齐：书法体顶端贴校徽顶端，
            // 英文底端贴校徽底端，中间空出来的部分是两者的天然行距。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_school_badge),
                    contentDescription = "校徽",
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // 校名用学校官方书法体（从 logo 里裁出来的），比排版字更贴原图
                    Image(
                        painter = painterResource(R.drawable.ic_school_name),
                        contentDescription = "北京信息科技大学",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(19.dp)
                            .aspectRatio(8.43f)
                    )
                    Text(
                        text = "BEIJING INFORMATION SCIENCE & TECHNOLOGY UNIVERSITY",
                        fontSize = 4.5.sp,
                        lineHeight = 5.sp,
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

            Spacer(Modifier.height(16.dp))

            // 余额 + 本月消费
            // 两个标签统一 14sp、统一 CenterVertically，所以「余额」和「本月消费」
            // 这四个字落在同一条水平线上（之前 12sp/11sp + 各自不同的底部留白，是歪的）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "余额",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = balanceText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "本月消费",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = monthText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            Spacer(Modifier.height(14.dp))

            // 网费充值：门户只给了一卡通消费流水，没有网费余额接口，
            // 但充值会落进流水（实测商户名「网络中心网费（微信）」），
            // 所以这里显示的是**本月充值合计**，不是余额。
            InlineStat(
                label = "网费充值",
                value = overview?.networkTopUp?.let {
                    PortalCardApi.formatMoney(it.amount)
                } ?: "--"
            )

            Spacer(Modifier.height(14.dp))

            // 状态条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 11.dp, vertical = 8.dp),
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

/** 标签和数值排在同一行，省掉一整行高度。两者统一 14sp，与「余额 / 本月消费」同规格 */
@Composable
private fun InlineStat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
        Spacer(Modifier.width(6.dp))
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
            .background(pageBackgroundColor())
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
                .padding(horizontal = LocalUiMetrics.current.pagePadding)
                .styleCard(shape = RoundedCornerShape(LocalUiMetrics.current.innerRadius))
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
            modifier = Modifier.padding(horizontal = LocalUiMetrics.current.pagePadding)
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
                    start = LocalUiMetrics.current.pagePadding,
                    end = LocalUiMetrics.current.pagePadding,
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
