package com.bistu.allthebistu.ui.card

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.card.PortalCardApi
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.wechat.WeChatEntry

/**
 * 宿舍电费页。
 *
 * 为什么需要「绑定宿舍」：电费是按**房间**结算的，任何查询都得先知道楼栋 + 房间号。
 *
 * 为什么绑了也查不到实时余额：学校供电系统没有对外的查询接口，门户 `personal_data`
 * 只返回 5 张数据卡（邮件/工资/手机号/一卡通/知会），没有电费。App 现在能给的
 * 真数据只有一个——一卡通流水里「电费充值」那类商户记录，也就是本月充了多少。
 * 所以这一页做的是：把宿舍记下来，把能显示的真实数据摆出来，并说清楚缺什么。
 */
@Composable
fun ElectricityScreen(
    overview: PortalCardApi.Overview?,
    building: String,
    room: String,
    onSaveDorm: (String, String) -> Unit,
    onClearDorm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 没绑定时直接进编辑态。
    // 注意 key 用 building/room：设置是从 DataStore 异步读的，第一帧一定是空字符串，
    // 如果不带 key，页面会永远停在「没绑定」的空表单上，绑过的宿舍也不显示。
    var editing by remember(building, room) {
        mutableStateOf(building.isBlank() || room.isBlank())
    }
    var inputBuilding by remember(building) { mutableStateOf(building) }
    var inputRoom by remember(room) { mutableStateOf(room) }

    val topUp = overview?.electricityTopUp
    val canSave = inputBuilding.isNotBlank() && inputRoom.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "宿舍电费",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HyperSpacing.pageHorizontal),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (editing) {
                BindCard(
                    building = inputBuilding,
                    room = inputRoom,
                    canSave = canSave,
                    onBuildingChange = { inputBuilding = it.trim() },
                    onRoomChange = { inputRoom = it.take(6).filter { c -> c.isDigit() } },
                    onSave = {
                        onSaveDorm(inputBuilding, inputRoom)
                        editing = false
                    },
                    onCancel = if (building.isBlank()) onBack else ({ editing = false })
                )
            } else {
                BoundCard(
                    building = building,
                    room = room,
                    topUp = topUp,
                    onEdit = {
                        inputBuilding = building
                        inputRoom = room
                        editing = true
                    },
                    onClear = onClearDorm
                )
            }

            // ── 说明：能查到什么、差什么 ──
            InfoCard()

            // ── 去充值 ──
            Button(
                onClick = {
                    if (!WeChatEntry.openWeChat(context)) WeChatEntry.openWeCom(context)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("去微信给电费充值", fontSize = 14.sp)
            }

            Spacer(Modifier.height(60.dp))
        }
    }
}

/** 未绑定 / 修改时：填楼栋和房间号 */
@Composable
private fun BindCard(
    building: String,
    room: String,
    canSave: Boolean,
    onBuildingChange: (String) -> Unit,
    onRoomChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Text(
            text = "你是哪个宿舍",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "电费按房间结算，绑一次以后不用再填。这个信息只存在本机。",
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = building,
            onValueChange = onBuildingChange,
            label = { Text("楼栋", fontSize = 12.sp) },
            placeholder = { Text("例：3号楼", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = room,
            onValueChange = onRoomChange,
            label = { Text("房间号", fontSize = 12.sp) },
            placeholder = { Text("例：412", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSave,
                enabled = canSave,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Text("保存并绑定", fontSize = 14.sp)
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("取消", fontSize = 14.sp)
            }
        }
    }
}

/** 已绑定：显示宿舍 + 本月电费充值（从一卡通流水来） */
@Composable
private fun BoundCard(
    building: String,
    room: String,
    topUp: PortalCardApi.UtilityTopUp?,
    onEdit: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "已绑定宿舍",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("修改", fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(2.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = building,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = room,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "本月电费充值",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = topUp?.let { PortalCardApi.formatMoney(it.amount) } ?: "本月没有记录",
                    fontSize = if (topUp != null) 20.sp else 14.sp,
                    fontWeight = if (topUp != null) FontWeight.Bold else FontWeight.Normal,
                    color = if (topUp != null) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            if (topUp != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "最近一次",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = shortTime(topUp.lastTime),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        TextButton(
            onClick = onClear,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text(
                text = "解绑这个宿舍",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 说明卡：把「能查什么、差什么」讲清楚，别让用户以为数字不准 */
@Composable
private fun InfoCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Text(
            text = "为什么看不到实时余额",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        InfoLine("学校供电系统没有对外的查询接口，信息门户也只返回 5 张数据卡，里面没有电费。")
        InfoLine("App 能拿到的真实数据只有一条：一卡通流水里「电费充值」那笔商户记录。")
        InfoLine("所以这里显示的是「本月充了多少」，不是「还剩多少」。绑定宿舍是为了将来接口通了能按房间查。")
        InfoLine("要充值和看余额，点上面的按钮进微信企业号「信息科大E卡通」→ 购电入口。")
    }
}

@Composable
private fun InfoLine(text: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    CircleShape
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "2026-10-09 22:46:22" → "10-09 22:46" */
private fun shortTime(raw: String): String {
    val m = Regex("""(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})""").find(raw)
    return if (m != null) {
        "${m.groupValues[2]}-${m.groupValues[3]} ${m.groupValues[4]}:${m.groupValues[5]}"
    } else raw
}
