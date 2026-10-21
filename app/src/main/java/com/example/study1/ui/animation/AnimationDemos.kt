package com.example.study1.ui.animation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compose 动画能力验证。
 *
 * 这个文件里的每个函数展示一种 HyperOS 风格的动画效果，
 * 用于验证 Compose 是否能做出流畅的动效。
 */

/**
 * ① 弹性缩放（HyperOS 按钮点击效果）
 *
 * 用 spring 动画让按钮按下时轻微缩小，松开弹回。
 * HyperOS 的按钮反馈就是这个感觉。
 */
@Composable
fun SpringScaleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableIntStateOf(0) }

    val scale by animateFloatAsState(
        targetValue = if (pressed == 1) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,  // 弹性
            stiffness = Spring.StiffnessMedium
        ),
        label = "buttonScale"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .clickable {
                pressed = 1 - pressed
                onClick()
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Medium,
            fontSize = 15.sp
        )
    }
}

/**
 * ② 周次切换滑入滑出（HyperOS 列表切换效果）
 *
 * 切周次时，新内容从右侧滑入，旧内容向左滑出。
 * 用 AnimatedContent 实现，自动处理进入/退出动画。
 */
@Composable
fun AnimatedWeekSwitch(
    currentWeek: Int,
    onWeekChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // 内容切换动画
        AnimatedContent(
            targetState = currentWeek,
            transitionSpec = {
                // 判断方向：切到更大的周次 = 向左滑（内容左移，新的从右进）
                val forward = targetState > initialState
                if (forward) {
                    (slideInHorizontally(
                        animationSpec = tween(300),
                        initialOffsetX = { it }
                    ) + fadeIn(tween(300)))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(300),
                                targetOffsetX = { -it / 3 }
                            ) + fadeOut(tween(200))
                        )
                } else {
                    (slideInHorizontally(
                        animationSpec = tween(300),
                        initialOffsetX = { -it }
                    ) + fadeIn(tween(300)))
                        .togetherWith(
                            slideOutHorizontally(
                                animationSpec = tween(300),
                                targetOffsetX = { it / 3 }
                            ) + fadeOut(tween(200))
                        )
                }
            },
            label = "weekContent"
        ) { week ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "第 $week 周",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "（内容随切换滑入滑出）",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { if (currentWeek > 1) onWeekChange(currentWeek - 1) },
                contentAlignment = Alignment.Center
            ) { Text("<", fontSize = 18.sp) }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onWeekChange(currentWeek + 1) },
                contentAlignment = Alignment.Center
            ) { Text(">", fontSize = 18.sp) }
        }
    }
}

/**
 * ③ 展开收起（HyperOS 卡片展开效果）
 *
 * 点击卡片，内容从高度 0 展开到实际高度。
 */
@Composable
fun ExpandableCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)

            // 箭头旋转动画
            val rotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "arrowRotation"
            )
            Text(
                text = "▼",
                fontSize = 12.sp,
                modifier = Modifier.scale(1f)
            )
        }

        // 展开/收起内容
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) + fadeIn(tween(200)),
            exit = shrinkVertically(
                animationSpec = tween(200)
            ) + fadeOut(tween(150))
        ) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    text = "这是展开后的内容。HyperOS 的卡片展开用的就是这种动画——" +
                            "高度从 0 平滑展开，同时内容淡入。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * ④ 颜色渐变过渡（HyperOS 主题切换效果）
 *
 * 切换深色模式时，颜色不是突变而是渐变。
 */
@Composable
fun AnimatedColorBox(
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF),
        animationSpec = tween(400),
        label = "bgColor"
    )

    val textColor by animateColorAsState(
        targetValue = if (isDark) Color(0xFFFFFFFF) else Color(0xFF000000),
        animationSpec = tween(400),
        label = "textColor"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bgColor)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isDark) "深色模式（颜色渐变而来）" else "浅色模式（颜色渐变而来）",
            color = textColor,
            fontSize = 14.sp
        )
    }
}

/**
 * ⑤ 弹性进度条（HyperOS 加载动画）
 *
 * 用 animateFloatAsState 做弹性进度条。
 */
@Composable
fun SpringProgressBar(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "progress"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
