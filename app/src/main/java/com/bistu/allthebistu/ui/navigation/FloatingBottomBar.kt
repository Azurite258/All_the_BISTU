package com.bistu.allthebistu.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 悬浮胶囊导航栏（HyperOS 风格）。
 *
 * 特点：
 *  - 圆角胶囊，浮在内容上方（不是贴底）
 *  - 柔和阴影
 *  - 选中项用「胶囊内高亮 + 图标缩放」动画
 *  - 未选中项只显示图标，选中项显示图标 + 文字
 */
@Composable
fun FloatingBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 胶囊容器
    Box(
        modifier = modifier
            .padding(horizontal = 40.dp, vertical = 16.dp)
            .shadow(
                elevation = 12.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x33000000),
                spotColor = Color(0x33000000)
            )
            .clip(RoundedCornerShape(28.dp))
            .background(surfaceColor)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MainTab.entries.forEach { tab ->
                val selected = tab == currentTab

                // 选中项背景色动画
                val itemBg by animateColorAsState(
                    targetValue = if (selected) primaryColor.copy(alpha = 0.12f)
                    else Color.Transparent,
                    animationSpec = tween(250),
                    label = "itemBg"
                )

                // 选中项文字/图标颜色动画
                val contentColor by animateColorAsState(
                    targetValue = if (selected) primaryColor else onSurfaceVariant,
                    animationSpec = tween(250),
                    label = "contentColor"
                )

                // 图标缩放动画
                val iconScale by animateFloatAsState(
                    targetValue = if (selected) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "iconScale"
                )

                val interactionSource = remember { MutableInteractionSource() }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(itemBg)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null
                        ) { onTabSelected(tab) }
                        .padding(
                            horizontal = if (selected) 14.dp else 12.dp,
                            vertical = 8.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = contentColor,
                        modifier = Modifier
                            .size(20.dp)
                            .scale(if (selected) iconScale else 1f)
                    )                    // 选中项才显示文字
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = contentColor
                        )
                    }
                }
            }
        }
    }
}
