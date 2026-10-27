package com.bistu.allthebistu.ui.life

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.library.LibrarySeatApi
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生活页的「图书馆座位」卡片。
 *
 * 数据只有**全馆空座**这一个数——图书馆预约系统只公开了
 * `/ic-web/seatDevice/resvStatus`（详见 [LibrarySeatApi] 的注释），
 * 分楼层/分区域要登录。所以这里不摆假的分层数据，
 * 想看具体座位就点卡片去官方预约页。
 *
 * 不依赖登录态：接口是公开的，未登录也照常显示。
 */
@Composable
fun LibrarySeatCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<LibrarySeatApi.Snapshot?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun load() {
        if (loading) return
        loading = true
        scope.launch {
            snapshot = LibrarySeatApi.fetch()
            loading = false
        }
    }

    // 首次进入拉一次；失败也只在卡片里提示，不弹窗
    LaunchedEffect(Unit) { if (snapshot == null) load() }

    val snap = snapshot
    // 条形填充 = **空座**占比（彩色 = 还有位置）。
    // 之前反着做（彩色 = 已占用），同一根条上一眼看上去像「满格有座」，容易读反。
    val availableFraction by animateFloatAsState(
        targetValue = snap?.let { (1f - it.occupancy).coerceIn(0f, 1f) } ?: 0f,
        label = "seatAvailable"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { openReservation(context) },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {

            // ── 标题行：名称 + 刷新 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "图书馆座位",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "沙河馆",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "刷新",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { load() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 大数字：空座 ──
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = when {
                        snap?.error != null -> "--"
                        snap != null -> snap.available.toString()
                        else -> "…"
                    },
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 32.sp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "空座",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
                Spacer(Modifier.weight(1f))
                if (snap != null && snap.error == null) {
                    Text(
                        text = "已占 ${(snap.occupancy * 100).toInt()}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 占用率条：填充部分 = 空座（彩色 = 还有位置），灰底 = 已占用 ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.20f))
            ) {
                if (!availableFraction.isNaN() && availableFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(availableFraction)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── 底部一行：总数 / 更新时间 / 去预约 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        snap?.error != null -> snap.error
                        snap != null -> "共 ${snap.total} 个座位 · ${clock(snap.atMillis)} 更新"
                        else -> "正在查座位余量…"
                    },
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = if (snap?.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "去预约",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End
                )
            }
        }
    }
}

/** 跳到图书馆官方预约页；没有浏览器就静默失败（不弹窗打断） */
private fun openReservation(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LibrarySeatApi.MOBILE_HOME))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun clock(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(millis))
