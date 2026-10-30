package com.bistu.allthebistu.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

import androidx.compose.material3.*
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.Source
import com.bistu.allthebistu.data.TermWeek
import com.bistu.allthebistu.data.patch.CoursePatches
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.AppWallpaper
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.LocalUiStyle
import com.bistu.allthebistu.ui.theme.UiStyle
import com.bistu.allthebistu.ui.theme.glassHairlineColor
import com.bistu.allthebistu.ui.theme.glassEdge
import com.bistu.allthebistu.ui.theme.styleCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bistu.allthebistu.ui.theme.cardSurfaceColor
import com.bistu.allthebistu.ui.theme.pageBackgroundColor

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
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier,
    /** 课程详情页的写操作（备注 / 删除 / 编辑 / 复制） */
    actions: CourseActions = CourseActions()
) {
    val result = uiState.loadResult
    when {
        uiState.isLoading && result == null -> LoadingScreen(modifier)

        result?.data != null -> ScheduleContent(
            data = result.data,
            source = result.source,
            timestamp = result.timestamp,
            error = result.error,
            isDark = isDark,
            settings = settings,
            patches = uiState.patches,
            actions = actions,
            isLoggedIn = uiState.isLoggedIn,
            isRefreshing = uiState.isRefreshing,
            autoLoginHint = uiState.autoLoginHint,
            onRefresh = onRefresh,
            onAddEvent = onAddEvent,
            onReminderSettings = onReminderSettings,
            onOpenLogin = onOpenLogin,
            modifier = modifier
        )

        else -> ErrorScreen(result?.error, onRefresh, modifier)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleContent(
    data: ScheduleData,
    source: Source,
    timestamp: Long,
    error: Throwable? = null,
    isDark: Boolean,
    settings: AppSettings,
    patches: CoursePatches,
    actions: CourseActions,
    isLoggedIn: Boolean,
    isRefreshing: Boolean,
    autoLoginHint: String?,
    onRefresh: () -> Unit,
    onAddEvent: () -> Unit,
    onReminderSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 显示范围：优先用教务系统的教学周，拿不到时退化为课程覆盖范围
    val weekRange = data.displayWeekRange

    // 默认显示「今天所在周」；算不出来或超出范围就回退到范围内第一周。
    // 用 rememberSaveable：横竖屏切换后仍停在原来那一周。
    var currentWeek by rememberSaveable(data.termCode, data.termWeeks.size) {
        mutableIntStateOf(
            DateUtils.displayWeek(
                termWeeks = data.termWeeks,
                termStartDate = TERM_START_DATE,
                weekRange = weekRange
            )
        )
    }

    // 点击课程块 → 弹出详情
    var selectedCourse by remember { mutableStateOf<Course?>(null) }

    // 已登录却退化成示例数据时，把真实原因摊给用户看，而不是只显示「同步失败」
    var errorDetail by remember { mutableStateOf<Throwable?>(null) }
    LaunchedEffect(error, source, isLoggedIn) {
        if (isLoggedIn && source == Source.ASSETS && error != null) {
            errorDetail = error
        }
    }

    // 色板索引：按课程名排序后依次编号，保证同屏课程颜色不重复；
    // 课块和详情弹窗共用这一份，点开时颜色才对得上
    val paletteIndex = remember(data.courses) {
        CourseColors.paletteIndexFor(data.courses.map { it.name })
    }

    Scaffold(
        modifier = modifier,
        // 必须是 pageBackgroundColor()，不能写死 colorScheme.background。
        // 这里铺的是**整页底色**，写死不透明的话会把 MainActivity 最底层的
        // WallpaperBackdrop 整个盖掉 —— 下面课表卡就算设成半透明，
        // 透出来的也只是这层底色，壁纸在课表页等于不存在。
        containerColor = pageBackgroundColor(),
        topBar = {
            // HyperOS 风格：不用 TopAppBar，自己画一个。
            // 注意：启用 enableEdgeToEdge 后内容会绘制到状态栏下方，
            // 必须自己加 statusBars inset，否则标题会压到手机时间上。
            //
            // 底边距按 cardGap 再减 3dp：
            // 页面上四道缝（标题 / 周次卡 / 课表卡 / 登录引导条）要一样宽。
            // 其余三处直接用 cardGap 就行，唯独标题这里底下压着一行 11sp 的文字，
            // 文字行框比字形高出一截、下沿还留了约 3dp，所以 padding 要少给 3dp，
            // 眼睛看到的缝才和另外三道一样。写死 8dp 时 iOS 上这道缝比下面窄 6dp。
            val topMetrics = LocalUiMetrics.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(
                        start = topMetrics.pagePadding,
                        end = topMetrics.pagePadding,
                        top = 16.dp,
                        bottom = topMetrics.cardGap - 3.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 超大标题
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "课表",
                        fontSize = LocalUiMetrics.current.titleSize,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            // 不显示班级/姓名等个人信息，只显示数据来源状态
                            append(sourceLabel(source, timestamp))
                            if (isLoggedIn) {
                                // 已登录却还在展示示例数据 / 刷新失败 → 必须明说，
                                // 否则用户会把上次的旧课表当成「刚同步的」
                                append(
                                    when {
                                        source == Source.ASSETS -> " · 同步失败"
                                        source == Source.CACHE && error != null -> " · 未能刷新"
                                        else -> " · 已登录"
                                    }
                                )
                            }
                        },
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = when {
                            source == Source.ASSETS && isLoggedIn -> Color(0xFFE65100)
                            // 缓存 + 刷新失败：橙色，和「已同步」的绿色拉开距离。
                            // 以前这里不分情况一律绿色，看起来像「一切正常」
                            source == Source.CACHE && error != null -> Color(0xFFE65100)
                            isLoggedIn -> Color(0xFF2E7D32)
                            else -> sourceColor(source)
                        },
                        // 同步失败时点这行可以看具体原因
                        modifier = Modifier.clickable(enabled = error != null) {
                            errorDetail = error
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
                // 顺序：添加日程 → 日程提醒（设置已并入「我的」页，顶栏不再有齿轮）
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
                    // 只数画在格子里的课，未排课（无节次）不该算进来
                    courseCount = data.coursesInWeek(currentWeek).count { it.isArranged },
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

                val metrics = LocalUiMetrics.current
                val cardShape = RoundedCornerShape(metrics.cardRadius)
                // 这张卡占满整屏，是课表页唯一挡住壁纸 / 光晕的东西，所以底色分三种：
                //  - 有壁纸：透一点让照片露出来（可见度 ≈ (1-0.40) × (1-蒙层)，默认约 18%）
                //  - iOS 液态玻璃：透一点让彩色光晕透上来，卡片才有玻璃感
                //  - 其余：实色
                val cardColor = when {
                    AppWallpaper.enabled -> cardSurfaceColor(alpha = 0.40f)
                    metrics.glowBackdrop -> MaterialTheme.colorScheme.surface.copy(alpha = 0.58f)
                    else -> cardSurfaceColor()
                }
                // 用 BoxWithConstraints 而不是 Box：这里的高度是确定的，
                // 传给网格让它算「能完整放下几行」，比按屏幕高度估算准
                androidx.compose.foundation.layout.BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = metrics.pagePadding,
                            end = metrics.pagePadding,
                            // 底部留出悬浮导航栏的空间（胶囊高度 + 上下边距）
                            // 与登录引导条共用同一个常量，保证两者底边对齐
                            bottom = metrics.contentBottom
                        )
                        // 这张卡就是方向稿里的 .grid-wrap：圆角 --r-card、阴影/描边/玻璃边
                        // 全部跟风格走（HyperOS 有阴影、Linear 有描边、iOS 有白边 + 内高光）。
                        // 底色另有讲究：有壁纸时要透出照片，iOS 要透出光晕。
                        .styleCard(shape = cardShape, color = cardColor)
                ) {
                    if (data.courses.isEmpty()) {
                        // 接口通了、但本学期确实没排课（新生 / 毕业年级 / 休学）。
                        // 以前这种情况会被报成「登录已过期」，人直接被踢回登录页。
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "本学期还没有排课",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "教务系统里这个学期暂时没有课程。排课后下拉刷新就会出现。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
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
                                    fontLevel = settings.fontLevel,
                                    paletteIndex = paletteIndex,
                                    onCourseClick = { selectedCourse = it }
                                    // 高度不再往下传：表格固定画满 14 节，超出的靠滚动
                                )
                            }
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

    // ── 同步失败详情 ──
    errorDetail?.let { err ->
        AlertDialog(
            onDismissRequest = { errorDetail = null },
            title = { Text("同步失败") },
            text = {
                Column {
                    Text(LoadErrorText.describe(err), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = LoadErrorText.detail(err),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    errorDetail = null
                    if (LoadErrorText.needsRelogin(err)) onOpenLogin() else onRefresh()
                }) {
                    // 只是同步失败时，「重试」比「知道了」有用得多：
                    // 用户不必知道下拉刷新这个手势也能自己救一次
                    Text(if (LoadErrorText.needsRelogin(err)) "重新登录" else "重试")
                }
            },
            dismissButton = if (LoadErrorText.needsRelogin(err)) {
                { TextButton(onClick = { errorDetail = null }) { Text("稍后") } }
            } else {
                { TextButton(onClick = { errorDetail = null }) { Text("关闭") } }
            }
        )
    }

    // ── 课程详情弹窗 ──
    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            currentWeek = currentWeek,
            patches = patches,
            colorScheme = settings.colorScheme,
            isDark = isDark,
            paletteIndex = paletteIndex[course.name],
            onDismiss = { selectedCourse = null },
            onSaveNote = { scope, week, text -> actions.onSaveNote(course, scope, week, text) },
            onHide = { scope, week -> actions.onHide(course, scope, week) },
            onEdit = { day, begin, end, room, teacher ->
                actions.onEdit(course, day, begin, end, room, teacher)
            },
            onClone = { day, begin -> actions.onClone(course, day, begin) },
            onRemoveClone = { actions.onRemoveClone(course) },
            onReset = { actions.onReset(course) }
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
    val m = LocalUiMetrics.current
    val uiStyle = LocalUiStyle.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 方向稿 .icon-btn：36dp 圆钮（iOS 38dp），底色 --surface + --shadow；
    // Linear 换成 1px --line 描边、无阴影；极简用 surface-2 + 描边；
    // M3 用 accent-soft 底 + accent-text 图标；iOS 半透明白 + 白边 + 内高光。
    val bg = when (uiStyle) {
        UiStyle.MATERIAL3 -> UiStyle.accentSoft(uiStyle, isDark)
        UiStyle.IOS -> Color.White.copy(alpha = 0.55f)
        // 极简的按钮底色是 --surface-2，也就是列表里的纯白（与卡片同色，靠描边分离）
        else -> MaterialTheme.colorScheme.surface
    }
    val tint = when (uiStyle) {
        UiStyle.MATERIAL3 -> UiStyle.accentText(uiStyle, isDark)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier = Modifier
            .size(m.iconButtonSize)
            .then(
                if (m.usesShadow) {
                    Modifier.shadow(
                        elevation = 3.dp,
                        shape = CircleShape,
                        clip = false,
                        ambientColor = Color(0x14000000),
                        spotColor = Color(0x24000000)
                    )
                } else {
                    Modifier
                }
            )
            .clip(CircleShape)
            .background(bg)
            .then(
                if (m.usesBorder) {
                    Modifier.border(
                        1.dp,
                        if (uiStyle == UiStyle.IOS) glassHairlineColor()
                        else MaterialTheme.colorScheme.outline,
                        CircleShape
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (uiStyle == UiStyle.IOS) {
                    Modifier.glassEdge(
                        m.iconButtonSize / 2,
                        topAlpha = if (isDark) 0.18f else 0.55f,
                        bottomAlpha = 0f
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp)
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

    val m = LocalUiMetrics.current
    val uiStyle = LocalUiStyle.current
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    // 方向稿里周次卡就是一张普通 .card：圆角 --r-card、padding 11 / --cardpad、
    // 阴影/描边跟着风格走。之前用的是 innerRadius + 半透明底，两处都不对。
    // 周切换圆钮 30dp，底色 accent-soft（Linear 用 surface-2、iOS 用半透明白）
    val navBg = when (uiStyle) {
        UiStyle.LINEAR -> MaterialTheme.colorScheme.surfaceVariant
        UiStyle.IOS -> Color.White.copy(alpha = 0.58f)
        else -> UiStyle.accentSoft(uiStyle, isDark)
    }
    val navTint = when (uiStyle) {
        UiStyle.LINEAR, UiStyle.MINIMAL -> MaterialTheme.colorScheme.onSurface
        else -> UiStyle.accentText(uiStyle, isDark)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = m.pagePadding, end = m.pagePadding, bottom = m.cardGap)
            .styleCard(shape = RoundedCornerShape(m.cardRadius))
            .padding(horizontal = m.cardPadding, vertical = 11.dp),
        // 方向稿 .week-card 是 gap:10px，圆钮固定 30dp，中间自适应
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一周
        Box(
            modifier = Modifier
                .size(m.weekNavSize)
                .clip(CircleShape)
                .background(navBg)
                .then(
                    if (uiStyle == UiStyle.IOS) {
                        Modifier.border(1.dp, glassHairlineColor(), CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = currentWeek > weekRange.first) {
                    if (currentWeek > weekRange.first) onWeekChange(currentWeek - 1)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一周",
                tint = if (currentWeek > weekRange.first) navTint else navTint.copy(alpha = 0.4f),
                modifier = Modifier.size(15.dp)
            )
        }

        // 中间信息。方向稿是「第 8 周 · 12 节课」同一行（节数用 accent-text），
        // 第二行只放日期范围 —— 之前把节数挤到第二行，跟示例图不一样。
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "第 $currentWeek 周",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = " · $courseCount 节课",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = UiStyle.accentText(uiStyle, isDark),
                    // 数字等宽，切换周次时不会左右跳动
                    style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")
                )
            }
            if (weekDateRange != null) {
                Text(
                    text = weekDateRange,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // 下一周
        Box(
            modifier = Modifier
                .size(m.weekNavSize)
                .clip(CircleShape)
                .background(navBg)
                .then(
                    if (uiStyle == UiStyle.IOS) {
                        Modifier.border(1.dp, glassHairlineColor(), CircleShape)
                    } else {
                        Modifier
                    }
                )
                .clickable(enabled = currentWeek < weekRange.last) {
                    if (currentWeek < weekRange.last) onWeekChange(currentWeek + 1)
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一周",
                tint = if (currentWeek < weekRange.last) navTint else navTint.copy(alpha = 0.4f),
                modifier = Modifier.size(15.dp)
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
private fun ErrorScreen(
    error: Throwable?,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        Spacer(Modifier.height(12.dp))
        // 以前这里只有错误文本和堆栈，用户没有任何可操作的入口，
        // 只能猜「下拉刷新」或者干脆重开 App
        Button(onClick = onRefresh) { Text("重试") }
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
