package com.example.study1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study1.data.LoadResult
import com.example.study1.data.ScheduleData
import com.example.study1.data.ScheduleRepository
import com.example.study1.data.Source
import com.example.study1.data.prefs.AppSettings
import com.example.study1.data.prefs.SettingsStore
import com.example.study1.data.prefs.ThemeMode
import com.example.study1.ui.schedule.DateUtils
import com.example.study1.ui.schedule.ScheduleGrid
import com.example.study1.ui.settings.SettingsScreen
import com.example.study1.ui.theme.HyperSpacing
import com.example.study1.ui.theme.Study1Theme
import com.example.study1.ui.CampusScreen
import com.example.study1.ui.StudyScreen
import com.example.study1.ui.ProfileScreen
import com.example.study1.ui.navigation.FloatingBottomBar
import com.example.study1.ui.navigation.MainTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学期第 1 周周一的日期。
 *
 * 当前值根据「2026-10-21 是第 7 周周三」反推得出：
 * 第1周周一 = 2026-10-21 - (6周 × 7天 + 2天) = 2026-09-07
 *
 * 接入登录后，这个值应该从教务系统的 getTermWeeks.do 接口获取，
 * 或者由用户在设置里手动指定。
 */
private const val TERM_START_DATE = "2026-09-07"

/**
 * 下拉刷新指示器的最短显示时长（毫秒）。
 *
 * 本地数据（如未登录时读 assets）几毫秒就返回，指示器会一闪而过。
 * 保证至少显示这么久，让「下拉出现 → 转一圈 → 回缩」的动画完整播完。
 * 800ms 是 Material Design 建议的「既能感知到、又不觉得卡」的时长。
 */
private const val MIN_REFRESH_MS = 800L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())

            Study1Theme(themeMode = settings.themeMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(settings = settings, settingsStore = settingsStore)
                }
            }
        }
    }
}

@Composable
fun MainScreen(settings: AppSettings, settingsStore: SettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ScheduleRepository(context) }

    var loadResult by remember { mutableStateOf<LoadResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    // 下拉刷新中的状态（与首次加载的 isLoading 分开，避免整页转圈）
    var isRefreshing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    // 登录状态变化触发器（登录成功后自增，触发重新加载）
    var loginVersion by remember { mutableIntStateOf(0) }
    // 自动登录失败原因（用于提示用户）
    var autoLoginHint by remember { mutableStateOf<String?>(null) }

    /** 首次加载 / 普通重新加载 */
    fun reload(forceRefresh: Boolean = false) {
        scope.launch {
            isLoading = true
            com.example.study1.ui.login.AuthHolder.auth?.let { repository.setAuth(it) }
            if (forceRefresh) repository.clearCache()
            loadResult = repository.loadSchedule()
            isLoading = false
        }
    }

    /** 下拉刷新：保留当前内容，只在顶部显示刷新指示器 */
    fun pullRefresh() {
        scope.launch {
            isRefreshing = true
            val startedAt = System.currentTimeMillis()
            try {
                com.example.study1.ui.login.AuthHolder.auth?.let { repository.setAuth(it) }
                // 下拉 = 强制从网络拉最新
                loadResult = repository.loadSchedule(forceRefresh = true)
            } finally {
                // 本地数据（如未登录读 assets）几毫秒就返回，
                // 指示器会一闪而过看不到动画。这里补足最短显示时长，
                // 让「出现 → 转一圈 → 回缩」完整播完。
                val elapsed = System.currentTimeMillis() - startedAt
                val remaining = MIN_REFRESH_MS - elapsed
                if (remaining > 0) delay(remaining)
                isRefreshing = false
            }
        }
    }

    // 会话失效时：清理持久化登录态
    LaunchedEffect(Unit) {
        repository.onSessionExpired = {
            scope.launch {
                com.example.study1.ui.login.AuthHolder.clear(context)
                repository.clearAuth()
            }
        }
    }

    // 首次加载：先尝试自动登录（复用持久化 Cookie），再加载课表
    LaunchedEffect(Unit) {
        val result = com.example.study1.ui.login.AuthHolder.autoLogin(context)
        android.util.Log.d("MainScreen", "自动登录结果: $result")
        when (result) {
            is com.example.study1.ui.login.AutoLoginResult.Success -> {
                autoLoginHint = null
                // 自动登录成功 → 强制从网络拉最新课表
                isLoading = true
                com.example.study1.ui.login.AuthHolder.auth?.let { repository.setAuth(it) }
                loadResult = repository.loadSchedule(forceRefresh = true)
                isLoading = false
            }
            is com.example.study1.ui.login.AutoLoginResult.NeedManual -> {
                autoLoginHint = result.reason
                reload()
            }
        }
    }

    // 监听登录状态变化：登录成功后强制从网络刷新
    val isLoggedInNow = com.example.study1.ui.login.AuthHolder.isLoggedIn
    var lastLoginState by remember { mutableStateOf(isLoggedInNow) }

    LaunchedEffect(isLoggedInNow) {
        if (isLoggedInNow && !lastLoginState) {
            // 刚登录成功 → 强制刷新（清缓存，从网络拉）
            reload(forceRefresh = true)
        }
        lastLoginState = isLoggedInNow
    }
    // 计算当前是否深色
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onThemeModeChange = { mode ->
                scope.launch { settingsStore.setThemeMode(mode) }
            },
            onColorSchemeChange = { scheme ->
                scope.launch { settingsStore.setColorScheme(scheme) }
            },
            onBack = { showSettings = false },
            isLoggedIn = com.example.study1.ui.login.AuthHolder.isLoggedIn,
            onLogout = {
                com.example.study1.ui.login.AuthHolder.clear(context)
                repository.clearAuth()
                scope.launch {
                    // 退出后清掉真实课表缓存，回落到示例数据
                    repository.clearCache()
                    loadResult = repository.loadSchedule()
                }
                showSettings = false
            }
        )
        return
    }

    // ── 当前选中的 Tab ──
    var currentTab by remember { mutableStateOf(MainTab.SCHEDULE) }

    // ── 主框架：内容区 + 悬浮导航栏 ──
    Box(modifier = Modifier.fillMaxSize()) {

        // 内容区
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentTab == MainTab.SCHEDULE) 0.dp else 0.dp)
        ) {
            when (currentTab) {
                MainTab.SCHEDULE -> {
                    // 课表页（原有逻辑）
                    ScheduleTabContent(
                        loadResult = loadResult,
                        isLoading = isLoading,
                        isRefreshing = isRefreshing,
                        isDark = isDark,
                        settings = settings,
                        autoLoginHint = autoLoginHint,
                        onRefresh = { pullRefresh() },
                        onOpenSettings = { showSettings = true },
                        onOpenLogin = {
                            context.startActivity(
                                android.content.Intent(
                                    context,
                                    com.example.study1.ui.login.NativeLoginActivity::class.java
                                )
                            )
                            loginVersion++
                        }
                    )
                }

                MainTab.CAMPUS -> CampusScreen()
                MainTab.STUDY -> StudyScreen()
                MainTab.PROFILE -> ProfileScreen()
            }
        }

        // 悬浮导航栏（底部加 insets，避开手势导航条/虚拟按键）
        FloatingBottomBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }
}

/**
 * 课表 Tab 的内容（把原来 MainScreen 里的分支逻辑抽出来）。
 */
@Composable
private fun ScheduleTabContent(
    loadResult: LoadResult?,
    isLoading: Boolean,
    isRefreshing: Boolean,
    isDark: Boolean,
    settings: AppSettings,
    autoLoginHint: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit
) {
    val result = loadResult
    when {
        isLoading && result == null -> LoadingScreen()
        result?.data != null -> ScheduleScreen(
            data = result.data,
            source = result.source,
            timestamp = result.timestamp,
            isDark = isDark,
            settings = settings,
            isLoggedIn = com.example.study1.ui.login.AuthHolder.isLoggedIn,
            isRefreshing = isRefreshing,
            autoLoginHint = autoLoginHint,
            onRefresh = onRefresh,
            onOpenSettings = onOpenSettings,
            onOpenLogin = onOpenLogin
        )
        else -> ErrorScreen(result?.error)
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在加载课表…", fontSize = 14.sp)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleScreen(
    data: ScheduleData,
    source: Source,
    timestamp: Long,
    isDark: Boolean,
    settings: AppSettings,
    isLoggedIn: Boolean,
    isRefreshing: Boolean = false,
    autoLoginHint: String? = null,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit
) {
    // 显示范围：优先用教务系统的教学周，拿不到时退化为课程覆盖范围
    val weekRange = data.displayWeekRange

    // 默认显示「今天所在周」；如果算不出来或超出范围，回退到范围内第一周
    var currentWeek by remember(data.termCode, data.termWeeks.size) {
        mutableIntStateOf(
            DateUtils.currentWeekOfTermWeeks(data.termWeeks, DateUtils.todayIso())
                ?: DateUtils.currentWeekOf(TERM_START_DATE)
                ?.takeIf { it in weekRange }
                ?: weekRange.first
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // HyperOS 风格：不用 TopAppBar，自己画一个。
            // 注意：启用 enableEdgeToEdge 后内容会绘制到状态栏下方，
            // 必须自己加 statusBars inset，否则标题会压到手机时间上。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = HyperSpacing.pageHorizontal,
                        end = HyperSpacing.pageHorizontal,
                        top = 16.dp,
                        bottom = 8.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 超大标题
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "课表",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(data.className)
                            append(" · ")
                            append(sourceLabel(source, timestamp))
                            if (isLoggedIn) {
                                // 已登录却仍在展示示例数据 → 说明本次同步失败
                                append(
                                    if (source == Source.ASSETS) " · 同步失败"
                                    else " · 已登录"
                                )
                            }
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        color = when {
                            source == Source.ASSETS && isLoggedIn -> Color(0xFFE65100)
                            isLoggedIn -> Color(0xFF2E7D32)
                            else -> sourceColor(source)
                        }
                    )
                    // 自动登录未成功时的提示（点击可去登录）
                    if (!isLoggedIn && !autoLoginHint.isNullOrBlank()) {
                        Text(
                            text = "$autoLoginHint · 点右上角头像登录",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = Color(0xFFE65100),
                            modifier = Modifier.clickable(onClick = onOpenLogin)
                        )
                    }
                }

                // 圆形图标按钮（HyperOS 风格）
                HyperIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = "重新加载",
                    onClick = onRefresh
                )
                Spacer(Modifier.width(8.dp))
                HyperIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings
                )
                Spacer(Modifier.width(8.dp))
                HyperIconButton(
                    icon = Icons.Default.AccountCircle,
                    contentDescription = "登录",
                    onClick = onOpenLogin
                )
            }
        }
    ) { padding ->
        // 课表纵向滚动状态（下拉刷新的「是否在顶部」判断依赖它）
        val vScroll = rememberScrollState()
        val pullState = rememberPullToRefreshState()

        // 下拉刷新：包在滚动容器外层，才能在下拉时优先拿到手势。
        // 只有课表已滚到顶部（无法继续向上滚）时才允许触发刷新，
        // 否则课表中途往下拖也会误触发。
        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .pullToRefresh(
                    isRefreshing = isRefreshing,
                    state = pullState,
                    enabled = !vScroll.canScrollBackward,
                    onRefresh = onRefresh
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            // ── 周次切换卡片 ──
            WeekSelector(
                currentWeek = currentWeek,
                weekRange = weekRange,
                courseCount = data.coursesInWeek(currentWeek).size,
                termStartDate = TERM_START_DATE,
                termWeek = data.termWeekOf(currentWeek),
                onWeekChange = { currentWeek = it }
            )

            // ── 课表卡片（支持左右滑动切换周次）──
            // 周次列表：weekRange 可能是 1..20 这样的范围，转成 0-based 索引
            val weeks = remember(weekRange) { weekRange.toList() }
            val pagerState = rememberPagerState(
                initialPage = (currentWeek - weekRange.first).coerceIn(0, weeks.lastIndex),
                pageCount = { weeks.size }
            )

            // 滑动 pager → 同步回 currentWeek
            LaunchedEffect(pagerState) {
                snapshotFlow { pagerState.currentPage }.collect { page ->
                    val w = weeks.getOrNull(page) ?: return@collect
                    if (w != currentWeek) currentWeek = w
                }
            }
            // 点箭头/外部改 currentWeek → 同步 pager
            LaunchedEffect(currentWeek) {
                val target = (currentWeek - weekRange.first).coerceIn(0, weeks.lastIndex)
                if (target != pagerState.currentPage) pagerState.animateScrollToPage(target)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = HyperSpacing.pageHorizontal,
                        end = HyperSpacing.pageHorizontal,
                        // 底部留出悬浮导航栏的空间（胶囊高度 + 上下边距）
                        bottom = 88.dp
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 边界不回弹，避免误以为还能继续滑
                    overscrollEffect = null
                ) { page ->
                    val weekNumber = weeks.getOrNull(page) ?: return@HorizontalPager
                    Box(modifier = Modifier.verticalScroll(vScroll)) {
                        ScheduleGrid(
                            data = data,
                            weekNumber = weekNumber,
                            todayDayOfWeek = todayDayOfWeek(),
                            colorScheme = settings.colorScheme,
                            isDark = isDark,
                            termStartDate = TERM_START_DATE
                        )
                    }
                }
            }
            }

            // 下拉刷新指示器（Modifier.pullToRefresh 只负责手势，不渲染 UI，
            // 需要手动叠一个 Indicator，否则下拉时看不到任何反馈）。
            // 注意：Indicator 内部会按下拉距离自己算垂直位置，
            // 这里不要再加 offset/padding，否则会破坏它的定位。
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            // 刷新结束后播放「回缩」动画：指示器平滑缩回顶部后消失，
            // 而不是 isRefreshing 一置 false 就瞬间跳没。
            var wasRefreshing by remember { mutableStateOf(false) }
            LaunchedEffect(isRefreshing) {
                if (wasRefreshing && !isRefreshing) {
                    pullState.animateToHidden()
                }
                wasRefreshing = isRefreshing
            }
        }
    }
}

/**
 * HyperOS 风格圆形图标按钮
 */
@Composable
private fun HyperIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 周次切换（HyperOS 卡片风格）
 */
@Composable
private fun WeekSelector(
    currentWeek: Int,
    weekRange: IntRange,
    courseCount: Int,
    termStartDate: String?,
    termWeek: com.example.study1.data.TermWeek? = null,
    onWeekChange: (Int) -> Unit
) {
    // 日期显示优先级：教务系统给的教学周 > 用学期起始日推算
    val weekDateRange = remember(currentWeek, termStartDate, termWeek) {
        if (termWeek != null) {
            // 教学周直接给 "yyyy-MM-dd"，转成 "MM-dd ~ MM-dd"
            val s = termWeek.startDate.takeIf { it.length >= 10 }?.substring(5)
            val e = termWeek.endDate.takeIf { it.length >= 10 }?.substring(5)
            if (s != null && e != null) "$s ~ $e" else null
        } else if (termStartDate.isNullOrBlank()) {
            null
        } else {
            runCatching {
                val dates = DateUtils.weekDates(termStartDate, currentWeek)
                if (dates.size == 7) {
                    "${dates[0].substring(5)} ~ ${dates[6].substring(5)}"
                } else null
            }.getOrNull()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = HyperSpacing.pageHorizontal,
                end = HyperSpacing.pageHorizontal,
                bottom = HyperSpacing.cardGap
            )
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一周
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = currentWeek > weekRange.first) {
                    if (currentWeek > weekRange.first) onWeekChange(currentWeek - 1)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = if (currentWeek > weekRange.first)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }

        // 中间信息
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "第 $currentWeek 周",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$courseCount 节课",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (weekDateRange != null) {
                    Text(
                        text = " · $weekDateRange",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 下一周
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = currentWeek < weekRange.last) {
                    if (currentWeek < weekRange.last) onWeekChange(currentWeek + 1)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = if (currentWeek < weekRange.last)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 旧版 WeekSelector（保留签名兼容，已不再使用）
 */
@Composable
private fun LegacyWeekSelector(
    currentWeek: Int,
    weekRange: IntRange,
    courseCount: Int,
    termStartDate: String?,
    onWeekChange: (Int) -> Unit
) {
    // 计算本周的日期范围（周一 ～ 周日）
    val weekDateRange = remember(currentWeek, termStartDate) {
        if (termStartDate.isNullOrBlank()) null
        else runCatching {
            val dates = com.example.study1.ui.schedule.DateUtils
                .weekDates(termStartDate, currentWeek)
            if (dates.size == 7) {
                "${dates[0].substring(5)} ~ ${dates[6].substring(5)}"
            } else null
        }.getOrNull()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { if (currentWeek > weekRange.first) onWeekChange(currentWeek - 1) },
            enabled = currentWeek > weekRange.first
        ) {
            Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "上一周")
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "第 $currentWeek 周",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$courseCount 节课",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (weekDateRange != null) {
                    Text(
                        text = " · $weekDateRange",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        IconButton(
            onClick = { if (currentWeek < weekRange.last) onWeekChange(currentWeek + 1) },
            enabled = currentWeek < weekRange.last
        ) {
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = "下一周")
        }
    }
}

@Composable
private fun ErrorScreen(error: Throwable?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "加载失败",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFC62828)
        )
        Spacer(Modifier.height(12.dp))
        Text(text = error?.message ?: "未知错误", fontSize = 14.sp)
        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error.stackTraceToString().take(2000),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

private fun todayDayOfWeek(): Int {
    val cal = java.util.Calendar.getInstance()
    val d = cal.get(java.util.Calendar.DAY_OF_WEEK)
    return if (d == 1) 7 else d - 1
}

private fun sourceLabel(source: Source, timestamp: Long): String {
    val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    return when (source) {
        Source.CACHE -> "本地缓存 · $timeStr"
        Source.ASSETS -> "示例数据 · $timeStr"
        Source.NETWORK -> "已同步 · $timeStr"
        Source.ERROR -> "加载出错"
    }
}

@Composable
private fun sourceColor(source: Source): Color = when (source) {
    Source.CACHE -> Color(0xFF2E7D32)
    Source.ASSETS -> Color(0xFFE65100)
    Source.NETWORK -> Color(0xFF1565C0)
    Source.ERROR -> Color(0xFFC62828)
}
