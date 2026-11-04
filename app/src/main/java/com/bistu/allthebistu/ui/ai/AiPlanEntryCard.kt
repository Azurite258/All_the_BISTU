package com.bistu.allthebistu.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.ui.theme.styleCard

/**
 * 学习页上的「AI 学习计划」入口卡。
 *
 * 刻意做成**一张很矮的卡**（两行、约 64dp），只负责跳转：
 * 真正的输入和结果都在二级页 [AiPlanScreen] 里。
 * 上一版把输入框和结果列表全塞在这张卡里，学习页被撑得很长，
 * 而且和「学期进度」「今日日程」几张卡抢注意力。
 */
@Composable
fun AiPlanEntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .styleCard()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "AI 学习计划",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "›",
                fontSize = 16.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "说清要学什么、有多少时间，帮你排一份计划",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant
        )
    }
}
