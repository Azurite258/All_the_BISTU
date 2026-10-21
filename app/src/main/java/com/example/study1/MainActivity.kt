package com.example.study1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.study1.ui.schedule.ScheduleGrid
import com.example.study1.ui.theme.Study1Theme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Study1Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { ScheduleRepository(context) }

    // 加载状态
    var loadResult by remember { mutableStateOf<LoadResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    fun reload(forceRefresh: Boolean = false) {
        scope.launch {
            isLoading = true
            if (forceRefresh) {
                // 强制刷新：清缓存后重新从 assets 解析
                repository.clearCache()
            }
            loadResult = repository.loadSchedule()
            isLoading = false
        }
    }

    // 首次加载
    LaunchedEffect(Unit) { reload() }

    val result = loadResult
    when {
        isLoading && result == null -> LoadingScreen()
        result?.data != null -> ScheduleScreen(
            data = result.data,
            source = result.source,
            timestamp = result.timestamp,
            onRefresh = { reload(forceRefresh = true) }
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
    onRefresh: () -> Unit
) {
    val weekRange = data.weekRange
    var currentWeek by remember(data.termCode) { mutableIntStateOf(weekRange.first) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "${data.className} 课表",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = sourceLabel(source, timestamp),
                            fontSize = 10.sp,
                            color = sourceColor(source)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新加载")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            WeekSelector(
                currentWeek = currentWeek,
                weekRange = weekRange,
                courseCount = data.coursesInWeek(currentWeek).size,
                onWeekChange = { currentWeek = it }
            )

            HorizontalDivider()

            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(2.dp)
            ) {
                ScheduleGrid(
                    data = data,
                    weekNumber = currentWeek,
                    todayDayOfWeek = todayDayOfWeek()
                )
            }
        }
    }
}

@Composable
private fun WeekSelector(
    currentWeek: Int,
    weekRange: IntRange,
    courseCount: Int,
    onWeekChange: (Int) -> Unit
) {
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
            Text(
                text = "$courseCount 节课",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

// ── 工具函数 ──

private fun todayDayOfWeek(): Int {
    val cal = java.util.Calendar.getInstance()
    val d = cal.get(java.util.Calendar.DAY_OF_WEEK)
    return if (d == 1) 7 else d - 1   // Calendar: 周日=1 → 转成 7
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
    Source.CACHE -> Color(0xFF2E7D32)      // 绿：缓存
    Source.ASSETS -> Color(0xFFE65100)     // 橙：示例数据
    Source.NETWORK -> Color(0xFF1565C0)    // 蓝：已同步
    Source.ERROR -> Color(0xFFC62828)      // 红：错误
}
