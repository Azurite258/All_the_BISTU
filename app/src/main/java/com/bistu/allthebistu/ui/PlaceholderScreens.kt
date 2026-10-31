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
import com.bistu.allthebistu.data.auth.TestAccount
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.data.prefs.SettingsStore
import com.bistu.allthebistu.ui.life.EmptyRoomCard
import com.bistu.allthebistu.ui.life.LibrarySeatCard
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.rememberContentBottomInset
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
 *  - 橙金渐变校园卡：真实校徽 + 校名 + 学生卡徽章 + 本月消费 + 网费充值
 *  - 流水不在本页展示，**点击卡片进入明细页** [CardDetailScreen]
 *
 * 「扫一扫」「校园码」两个入口已删（2026-10-27）：都只能把微信拉起来，
 * 跳不到具体功能，留着是块死按钮。
 *
 * 刻意**不展示**姓名、学号、学院、班级、照片。
 *
 * ## 为什么卡面没有「余额」（2026-10-31 定案）
 * 用户实机对账发现卡面 16.54、官方 E 卡通 200.50，等两天也不会自己追上。
 * 查到根因：门户这条链路**没有余额字段**，能用的只有每笔流水的 KNYE；
 * 而门户流水**只覆盖当月**，且不含微信转账充值和开水机水控。
 * 于是「余额」永远停在最后一笔门户可见交易，本质是个过期值。
 * 结论：卡面主数字换成**本月消费**（门户当月接口的真数据），
 * 余额只在明细页按「交易后余额」逐笔记账式展示，并注明实时余额去微信企业号看。
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
        // 测试账号：本地随机数据，余额是 ∞，不发请求
        if (AuthHolder.isTestSession) {
            overview = TestAccount.cardOverview()
            return
        }
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
            // 同 生活页另两张卡：材质统一走 styleCard()，不再写死 20dp 圆角 + surface
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .styleCard()
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
                        text = "本月消费和消费流水来自信息门户（只覆盖当月），需要先登录一次。",
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

        // 底部让位：底栏本体（contentBottom）+ 手势条 inset + 呼吸位。
        // 之前写死 100dp，既没算 inset，未登录时最后一张「空教室」卡还会被引导条压住一截。
        Spacer(Modifier.height(rememberContentBottomInset(extra = 12.dp)))
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
    // ⚠️ 卡面**不再摆余额**（2026-10-31 定案，用户确认门户只给当月数据）。
    // 门户这条链路里没有任何「余额」字段，能用的只有每笔流水的 KNYE，
    // 而这张表只覆盖当月、且不含微信转账充值和开水机水控 ——
    // 于是 KNYE 永远停在「最后一笔门户可见交易」，跟官方 E 卡通一比必然差
    // （实测 16.54 vs 200.50，用户等了几天也不会自己追上）。
    // 与其挂个永远对不上的数，不如把主数字换成**本月消费**：
    // 它来自门户 `card.spentMonth`，是当月真数据。
    // 余额只在明细页按「交易后余额」逐笔展示，卡面指路去微信企业号看实时余额。
    val monthText = when {
        overview == null -> if (loading) "…" else "--"
        overview.error != null -> "--"
        else -> PortalCardApi.formatMoney(overview.monthSpent)
    }

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

            // 本月消费（门户当月接口）+ 网费充值（当月流水汇总）
            // 两个标签统一 14sp、统一 CenterVertically，四个字落在同一条水平线上。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本月消费",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = monthText,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "网费充值",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.85f)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = overview?.networkTopUp?.let {
                        PortalCardApi.formatMoney(it.amount)
                    } ?: "--",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            // 两行小字把数据边界说清楚，否则用户拿它跟 E 卡通一对账就觉得是 bug：
            //  1. 门户只给当月 → 说明「同步到哪一刻」；
            //  2. 门户没有余额接口 → 告诉他实时余额去哪看。
            Spacer(Modifier.height(4.dp))
            Text(
                text = overview?.records?.firstOrNull()?.let {
                    "门户只提供当月数据 · 流水同步至 ${shortTime(it.time)}"
                } ?: "门户只提供当月数据",
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.72f)
            )
            Text(
                text = "实时余额请在微信企业号「信息科大E卡通」查看",
                fontSize = 9.5.sp,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier.padding(top = 1.dp)
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

        // 汇总条：只放门户**真有的**两个当月数字（本月消费 / 网费充值）。
        // 余额不在这里 —— 门户没有余额接口，且流水只覆盖当月，算出来的数必然对不上账，
        // 见 CampusCard 顶部注释。
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
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "网费充值",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = overview.networkTopUp?.let {
                        PortalCardApi.formatMoney(it.amount)
                    } ?: "--",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 数据边界写在流水标题里：门户只给**当月**流水（2026-10-31 用户确认），
        // 所以标题不写「全部流水」，直接说清楚是哪个月的。
        Text(
            text = buildString {
                append("当月流水 · ${overview.records.size} 笔")
                overview.records.firstOrNull()?.let {
                    append(" · 同步至 ${shortTime(it.time)}")
                }
            },
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = LocalUiMetrics.current.pagePadding)
        )
        Text(
            text = "每笔下面的余额是那笔交易后的卡内余额；实时余额请到微信企业号「信息科大E卡通」查看。",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.padding(
                start = LocalUiMetrics.current.pagePadding,
                end = LocalUiMetrics.current.pagePadding,
                top = 2.dp
            )
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
                // 底部留白必须把底栏**和手势条 inset** 一起让出来：
                // 之前写死 90dp，比底栏真正的顶边矮了 20~30dp，滚到底时最后一行
                // 会钻到药丸底下、甚至从药丸下面漏出来（底栏是半透明的，穿帮更明显）。
                contentPadding = PaddingValues(
                    start = LocalUiMetrics.current.pagePadding,
                    end = LocalUiMetrics.current.pagePadding,
                    bottom = rememberContentBottomInset(extra = 8.dp)
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
                            // 测试账号的卡内余额是 ∞（见 TestAccount），真数据偶尔也可能给 NaN。
                            // 这两种情况下「交易后余额」没有意义，整段省掉，别在每行挂一排 ¥∞。
                            Text(
                                text = buildString {
                                    append(shortTime(r.time))
                                    if (r.balance.isFinite()) {
                                        append(" · 交易后余额 ")
                                        append(PortalCardApi.formatMoney(r.balance))
                                    }
                                },
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
