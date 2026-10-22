package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.Source
import com.bistu.allthebistu.data.TermWeek
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.ui.theme.HyperSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 学期第 1 周周一的日期。
 *
 * 兜底用：优先取教务系统的教学周（getTermWeeks.do），
 * 接口拿不到时才用这个常量推算日期。
 */
internal const val TERM_START_DATE = "2026-09-07"

/**
 * 课表页。
 *
 * 只负责渲染 [ScheduleUiState] + 把用户操作往上抛；
 * 数据加载、刷新、登录态全在 [ScheduleViewModel] 里。
 */
@Composable
fun ScheduleScreen(
    uiState: ScheduleUiState,
    settings: AppSettings,
    isDark: Boolean,
    onRefresh: () -> Unit,
    onAddEvent: () -> Unit,
    onReminderSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val result = uiState.loadResult
    when {
        uiState.isLoading && result == null -> LoadingScreen(modifier)

        result?.data != null -> ScheduleContent(
            data = result.data,
            source = result.source,
            timestamp = result.timestamp,
            isDark = isDark,
            settings = settings,
            isLoggedIn = uiState.isLoggedIn,
            isRefreshing = uiState.isRefreshing,
            autoLoginHint = uiState.autoLoginHint,
            onRefresh = onRefresh,
            onAddEvent = onAddEvent,
            onReminderSettings = onReminderSettings,
            onOpenSettings = onOpenSettings,
            onOpenLogin = onOpenLogin,
            modifier = modifier
        )

        else -> ErrorScreen(result?.error, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleContent(
    data: ScheduleData,
    source: Source,
    timestamp: Long,
    isDark: Boolean,
    settings: AppSettings,
    isLoggedIn: Boolean,
    isRefreshing: Boolean,
    autoLoginHint: String?,
    onRefresh: () -> Unit,
    onAddEvent: () -> Unit,
    onReminderSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 显示范围：优先用教务系统的教学周，拿不到时退化为课程覆盖范围
    val weekRange = data.displayWeekRange

    // 默认显示「今天所在周」；算不出来或超出范围就回退到范围内第一周。
    // 用 rememberSaveable：横竖屏切换后仍停在原来那一周。
    var currentWeek by rememberSaveable(data.termCode, data.termWeeks.size) {
        mutableIntStateOf(
            DateUtils.currentWeekOfTermWeeks(data.termWeeks, DateUtils.todayIso())
                ?: DateUtils.currentWeekOf(TERM_START_DATE)
                    ?.takeIf { it in weekRange }
                ?: weekRange.first
        )
    }

    // 点击课程块 → 弹出详情
    var selectedCourse by remember { mutableStateOf<Course?>(null) }

    Scaffold(
        modifier = modifier,
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
                            // 不显示班级/姓名等个人信息，只显示数据来源状态
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
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            source == Source.ASSETS && isLoggedIn -> Color(0xFFE65100)
                            isLoggedIn -> Color(0xFF2E7D32)
                            else -> sourceColor(source)
                        }
                    )
                    // 自动登录未成功时的提示（点击可去登录）
                    if (!isLoggedIn && !autoLoginHint.isNullOrBlank()) {
                        Text(
                            text = "$autoLoginHint · 点此登录",
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color(0xFFE65100),
                            modifier = Modifier.clickable(onClick = onOpenLogin)
                        )
                    }
                }

                // 圆形图标按钮（HyperOS 风格）
                // 顺序：添加日程 → 日程提醒 → 设置（设置固定在最右）
                HyperIconButton(
                    icon = Icons.Default.Add,
                    contentDescription = "添加日程",
                    onClick = onAddEvent
                )
                Spacer(Modifier.width(8.dp))
                HyperIconButton(
                    icon = Icons.Default.Notifications,
                    contentDescription = "日程提醒",
                    onClick = onReminderSettings
                )
                Spacer(Modifier.width(8.dp))
                HyperIconButton(
                    icon = Icons.Default.Settings,
                    contentDescription = "设置",
                    onClick = onOpenSettings
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
            Column(modifier = Modifier.fillMaxSize()) {
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
                                todayDayOfWeek = DateUtils.todayDayOfWeek(),
                                colorScheme = settings.colorScheme,
                                isDark = isDark,
                                termStartDate = TERM_START_DATE,
                                onCourseClick = { selectedCourse = it }
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

    // ── 课程详情弹窗 ──
    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            currentWeek = currentWeek,
            colorScheme = settings.colorScheme,
            isDark = isDark,
            onDismiss = { selectedCourse = null }
        )
    }
}

/**
 * HyperOS 风格圆形图标按钮
 */
@Composable
private fun HyperIconButton(
    icon: ImageVector,
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
    termWeek: TermWeek? = null,
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
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
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
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
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

@Composable
private fun LoadingScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("正在加载课表…", fontSize = 14.sp)
        }
    }
}

@Composable
private fun ErrorScreen(error: Throwable?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
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

private fun sourceLabel(source: Source, timestamp: Long): String {
    val timeStr = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timestamp))
    return when (source) {
        Source.CACHE -> "本地缓存 · $timeStr"
        Source.ASSETS -> "示例数据 · $timeStr"
        Source.NETWORK -> "已同步 · $timeStr"
        Source.ERROR -> "加载出错"
    }
}

private fun sourceColor(source: Source): Color = when (source) {
    Source.CACHE -> Color(0xFF2E7D32)
    Source.ASSETS -> Color(0xFFE65100)
    Source.NETWORK -> Color(0xFF1565C0)
    Source.ERROR -> Color(0xFFC62828)
}
