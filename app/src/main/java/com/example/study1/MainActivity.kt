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
import com.example.study1.data.ScheduleData
import com.example.study1.data.ScheduleParser
import com.example.study1.ui.schedule.ScheduleGrid
import com.example.study1.ui.theme.Study1Theme

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

    // 解析数据（只做一次）
    val result by remember {
        mutableStateOf(
            runCatching {
                val rawJson = context.assets.open("schedule_sample.json")
                    .bufferedReader().use { it.readText() }
                ScheduleParser.parse(rawJson, termCode = "2026-2027-1")
            }
        )
    }

    result.fold(
        onSuccess = { data -> ScheduleScreen(data) },
        onFailure = { error -> ErrorScreen(error) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleScreen(data: ScheduleData) {
    val weekRange = data.weekRange
    // 默认显示"今天所在周"或第 1 周
    var currentWeek by remember { mutableIntStateOf(weekRange.first) }

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
                            text = "${data.major} · ${data.campus}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
            // ── 周次切换条 ──
            WeekSelector(
                currentWeek = currentWeek,
                weekRange = weekRange,
                courseCount = data.coursesInWeek(currentWeek).size,
                onWeekChange = { currentWeek = it }
            )

            Divider()

            // ── 课表网格（可滚动）──
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
                    todayDayOfWeek = java.util.Calendar.getInstance()
                        .get(java.util.Calendar.DAY_OF_WEEK)
                        .let { if (it == 1) 7 else it - 1 }
                )
            }
        }
    }
}

/**
 * 周次切换器
 *  [<]  第 6 周 (15 节)  [>]
 */
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
private fun ErrorScreen(error: Throwable) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "解析失败",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFC62828)
        )
        Spacer(Modifier.height(12.dp))
        Text(text = error.message ?: "未知错误", fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            text = error.stackTraceToString().take(2000),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
