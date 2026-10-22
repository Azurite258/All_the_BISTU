package com.example.study1.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study1.ui.navigation.MainTab
import com.example.study1.ui.theme.HyperSpacing

/**
 * 校园 Tab 占位页。
 *
 * 未来放：一卡通余额、电费、图书馆、空教室、校园通知、校历。
 */
@Composable
fun CampusScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "校园",
        subtitle = "All the Bistu 的核心板块",
        plannedItems = listOf(
            "一卡通余额与消费流水",
            "宿舍电费余额与用量",
            "图书馆座位查询",
            "空教室查询",
            "校园通知聚合",
            "校历与节假日"
        ),
        modifier = modifier
    )
}

/**
 * 学习 Tab 占位页。
 *
 * 未来放：考试安排、课时统计、课表总览、课程笔记、AI 问答、学习计划。
 */
@Composable
fun StudyScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = "学习",
        subtitle = "学业相关的一切",
        plannedItems = listOf(
            "考试安排与倒计时",
            "课时统计图表",
            "整学期课表总览",
            "课程笔记",
            "AI 课表问答",
            "学习计划生成"
        ),
        modifier = modifier
    )
}

/**
 * 我的 Tab 占位页。
 *
 * 未来放：账号信息、设置、关于。
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
            // 这几个页面没有 Scaffold，启用 edge-to-edge 后必须自己加 insets，
            // 否则大标题会压到状态栏（手机时间/信号图标）上。
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(
                start = HyperSpacing.pageHorizontal,
                end = HyperSpacing.pageHorizontal,
                top = 16.dp
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 超大标题
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

        // 规划清单卡片
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
