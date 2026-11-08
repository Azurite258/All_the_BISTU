package com.bistu.allthebistu.ui.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 学习页上的「AI 水课助手」入口卡，紧跟在 [com.bistu.allthebistu.ui.ai.AiPlanEntryCard] 后面。
 *
 * 和 AI 学习计划那张卡保持同一套版式：两行、矮卡、只负责跳转。
 * 唯一的差别是标题右边挂了一个「开发中」小标——这个功能确实没做完，
 * 而且要用花钱的云端识别，不标出来用户点进去会以为坏了。
 *
 * 真正的界面在 [com.bistu.allthebistu.classroom.ClassroomAssistActivity]（原生 View 写的，
 * 没走 Compose，因为里面要频繁刷新转写文本，TextView 比重组便宜）。
 */
@Composable
fun ClassroomEntryCard(
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
                text = "AI 水课助手",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "开发中",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = colors.onPrimary,
                modifier = Modifier
                    .background(
                        colors.primary.copy(alpha = 0.62f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "›",
                fontSize = 16.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = "上课录音，听到点名时震你一下；需要小米 MiMo 的 Key",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant
        )
    }
}
