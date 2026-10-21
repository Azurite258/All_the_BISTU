package com.example.study1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.example.study1.data.Course
import com.example.study1.data.ScheduleData
import com.example.study1.data.ScheduleParser
import com.example.study1.ui.theme.Study1Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Study1Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DataTestScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

/**
 * 数据层验证界面：
 * 读取 assets/schedule_sample.json → 解析 → 展示结果
 */
@Composable
fun DataTestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val result by remember {
        mutableStateOf(
            runCatching {
                val rawJson = context.assets.open("schedule_sample.json")
                    .bufferedReader()
                    .use { it.readText() }
                ScheduleParser.parse(rawJson, termCode = "2026-2027-1")
            }
        )
    }

    result.fold(
        onSuccess = { data -> ScheduleResultContent(data, modifier) },
        onFailure = { error -> ParseErrorContent(error, modifier) }
    )
}

@Composable
private fun ScheduleResultContent(data: ScheduleData, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "解析成功",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32)
                )
                Spacer(Modifier.height(8.dp))
                Text("共解析出 ${data.courses.size} 条课程", fontSize = 15.sp)
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionTitle("学生信息")
        InfoRow("姓名", data.studentName)
        InfoRow("学号", data.studentId)
        InfoRow("专业", data.major)
        InfoRow("班级", data.className)
        InfoRow("校区", data.campus)
        InfoRow("学期", data.termCode)
        InfoRow("周次范围", "${data.weekRange.first} - ${data.weekRange.last} 周")

        Spacer(Modifier.height(16.dp))

        SectionTitle("周次分布")
        val weekCounts = data.weekRange.map { w -> w to data.coursesInWeek(w).size }
        Text(
            text = weekCounts.joinToString("  ") { "${it.first}周:${it.second}" },
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(Modifier.height(16.dp))

        SectionTitle("课程列表（按星期排序）")

        data.courses
            .sortedWith(compareBy({ it.dayOfWeek }, { it.beginSection }))
            .forEach { course ->
                CourseItem(course)
                Spacer(Modifier.height(6.dp))
            }

        Spacer(Modifier.height(24.dp))
        Text(
            text = "如果以上信息正确，说明数据层已经跑通",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2E7D32)
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun CourseItem(course: Course) {
    val bgColor = runCatching {
        Color(android.graphics.Color.parseColor(course.colorHex))
    }.getOrElse { Color(0xFFEEEEEE) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = course.name, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "周${dayLabel(course.dayOfWeek)} 第${course.beginSection}-${course.endSection}节",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(text = "${course.beginTime}-${course.endTime}  |  ${course.room}", fontSize = 13.sp)
            Text(text = "教师：${course.teacher.ifEmpty { "未标注" }}", fontSize = 13.sp)
            Text(
                text = "周次：${formatWeeks(course.weeks)}",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ParseErrorContent(error: Throwable, modifier: Modifier) {
    Column(
        modifier = modifier
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

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 6.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label：",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(80.dp)
        )
        Text(text = value.ifEmpty { "—" }, fontSize = 14.sp)
    }
}

/** 1 → 一, 7 → 日 */
private fun dayLabel(day: Int): String = when (day) {
    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"
    5 -> "五"; 6 -> "六"; 7 -> "日"
    else -> "?"
}

/** setOf(1,2,3,5) → "1-3, 5" */
private fun formatWeeks(weeks: Set<Int>): String {
    if (weeks.isEmpty()) return "—"
    val sorted = weeks.sorted()
    val parts = mutableListOf<String>()
    var start = sorted[0]
    var prev = sorted[0]

    for (i in 1 until sorted.size) {
        val cur = sorted[i]
        if (cur == prev + 1) {
            prev = cur
        } else {
            parts.add(if (start == prev) "$start" else "$start-$prev")
            start = cur
            prev = cur
        }
    }
    parts.add(if (start == prev) "$start" else "$start-$prev")
    return parts.joinToString(", ")
}
