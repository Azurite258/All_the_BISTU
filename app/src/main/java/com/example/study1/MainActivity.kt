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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.example.study1.ui.schedule.ScheduleGrid
import com.example.study1.ui.settings.SettingsScreen
import com.example.study1.ui.theme.HyperSpacing
import com.example.study1.ui.theme.Study1Theme
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
    var showSettings by remember { mutableStateOf(false) }

    fun reload(forceRefresh: Boolean = false) {
        scope.launch {
            isLoading = true
            if (forceRefresh) repository.clearCache()
            loadResult = repository.loadSchedule()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

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
            onBack = { showSettings = false }
        )
        return
    }

    val result = loadResult
    when {
        isLoading && result == null -> LoadingScreen()
        result?.data != null -> ScheduleScreen(
            data = result.data,
            source = result.source,
            timestamp = result.timestamp,
            isDark = isDark,
            settings = settings,
            onRefresh = { reload(forceRefresh = true) },
            onOpenSettings = { showSettings = true }
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
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val weekRange = data.weekRange

    // 默认显示「今天所在周」；如果算不出来或超出范围，回退到第 1 周
    var currentWeek by remember(data.termCode) {
        mutableIntStateOf(
            com.example.study1.ui.schedule.DateUtils
                .currentWeekOf(TERM_START_DATE)
                ?.takeIf { it in weekRange }
                ?: weekRange.first
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // HyperOS 风格：不用 TopAppBar，自己画一个
            Row(
                modifier = Modifier
                    .fillMaxWidth()
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
                        text = "${data.className} · ${sourceLabel(source, timestamp)}",
                        fontSize = 12.sp,
                        color = sourceColor(source)
                    )
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
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // ── 周次切换卡片 ──
            WeekSelector(
                currentWeek = currentWeek,
                weekRange = weekRange,
                courseCount = data.coursesInWeek(currentWeek).size,
                termStartDate = TERM_START_DATE,
                onWeekChange = { currentWeek = it }
            )

            // ── 课表卡片 ──
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = HyperSpacing.pageHorizontal,
                        end = HyperSpacing.pageHorizontal,
                        bottom = HyperSpacing.cardGap
                    )
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
            ) {
                ScheduleGrid(
                    data = data,
                    weekNumber = currentWeek,
                    todayDayOfWeek = todayDayOfWeek(),
                    colorScheme = settings.colorScheme,
                    isDark = isDark,
                    termStartDate = TERM_START_DATE
                )
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
    onWeekChange: (Int) -> Unit
) {
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
