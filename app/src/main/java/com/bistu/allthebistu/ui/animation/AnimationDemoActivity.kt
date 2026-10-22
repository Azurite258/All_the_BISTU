package com.bistu.allthebistu.ui.animation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.ui.theme.Study1Theme

/**
 * 动画能力演示页。
 *
 * 用途：验证 Compose 是否能实现 HyperOS 风格的各种动效。
 * 逐个点一遍就能看出效果。
 */
class AnimationDemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Study1Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AnimationDemoScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimationDemoScreen() {
    var currentWeek by remember { mutableIntStateOf(7) }
    var expanded by remember { mutableStateOf(false) }
    var isDark by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0.3f) }
    var clickCount by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("动画能力验证") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ① 弹性缩放按钮
            DemoSection("① 弹性缩放（spring）") {
                SpringScaleButton(
                    text = "点我试试（已点 $clickCount 次）",
                    onClick = { clickCount++ }
                )
                Text(
                    text = "按下时缩小到 92%，松开弹回，带弹性回弹",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ② 周次切换动画
            DemoSection("② 周次切换滑入滑出") {
                AnimatedWeekSwitch(
                    currentWeek = currentWeek,
                    onWeekChange = { currentWeek = it }
                )
            }

            // ③ 展开收起
            DemoSection("③ 卡片展开收起") {
                ExpandableCard(
                    title = "点击展开/收起",
                    expanded = expanded,
                    onToggle = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ④ 颜色渐变
            DemoSection("④ 颜色渐变过渡") {
                AnimatedColorBox(isDark = isDark)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { isDark = !isDark }) {
                    Text(if (isDark) "切到浅色" else "切到深色")
                }
            }

            // ⑤ 弹性进度条
            DemoSection("⑤ 弹性进度条") {
                SpringProgressBar(progress = progress)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { progress = 0.3f }) { Text("30%") }
                    OutlinedButton(onClick = { progress = 0.7f }) { Text("70%") }
                    OutlinedButton(onClick = { progress = 1f }) { Text("100%") }
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "以上 5 种动画全部用 Compose 内置 API 实现，" +
                        "不需要任何第三方库，也不需要额外依赖。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DemoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}
