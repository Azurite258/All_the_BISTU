package com.bistu.allthebistu.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 * 学习页上的「电子课本」入口卡。
 *
 * 和 [AiPlanEntryCard]、[ClassroomEntryCard] 同一套版式：两行、矮卡、只负责跳转。
 * 副标题带书名数量——用户能一眼知道"里面有没有书"，不用点进去看。
 */
@Composable
fun TextbookEntryCard(
    count: Int,
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
                text = "电子课本",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Spacer(Modifier.width(7.dp))
            if (count > 0) {
                Text(
                    text = "$count 本",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "›",
                fontSize = 16.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = if (count > 0) "接着上次的进度继续读"
            else "导入上课要用的 PDF 教材，按课程自动归类",
            fontSize = 12.sp,
            color = colors.onSurfaceVariant
        )
    }
}
