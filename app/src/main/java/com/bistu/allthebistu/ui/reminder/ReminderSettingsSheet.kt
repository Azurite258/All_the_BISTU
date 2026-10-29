package com.bistu.allthebistu.ui.reminder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.data.reminder.CourseCalendarSync
import com.bistu.allthebistu.data.reminder.ReminderPlanner
import com.bistu.allthebistu.ui.components.WheelColumn
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.opaqueSurface

/**
 * 课程提醒设置。
 *
 * 两个开关是**两条独立通道**，不是「主开关 + 高级选项」：
 *  - 上面那个：App 自己发通知，靠自己的闹钟，需要通知权限；
 *  - 下面那个：把整学期课表写成系统日历里的事件，由系统日历负责弹提醒，
 *    不需要 App 活着，也不需要通知权限。
 * 用户想「完全无感」就只开下面那个，想让文案更贴近课表就开上面那个，都开也不冲突。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSettingsSheet(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onAdvanceChange: (Int) -> Unit,
    onCalendarSyncChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    // skipPartiallyExpanded：点开就是全展开，不用再上拉一次；
    // 返回键也因此直接关闭弹窗，而不是先缩回半高
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val advance = ReminderPlanner.sanitizeAdvance(settings.reminderAdvanceMinutes)

    // 滚轮自己维护一个本地值。
    // 不能让滚轮直接读 settings：每次停下都要写一次 DataStore，
    // 而 DataStore 是异步的，读回滞后会让轮子「滚不动 / 往回弹」。
    // 本地值实时跟手，写盘和重排交给上层去抖。
    var localAdvance by remember(settings.reminderAdvanceMinutes) {
        mutableIntStateOf(advance)
    }

    val (hour, minute) = ReminderPlanner.splitAdvance(localAdvance)

    fun pickAdvance(totalMinutes: Int) {
        val next = ReminderPlanner.sanitizeAdvance(totalMinutes)
        if (next != localAdvance) {
            localAdvance = next
            onAdvanceChange(next)
        }
    }

    // 关闭态的圆点默认是 onSurfaceVariant 的灰，压在浅灰轨道上几乎糊成一团。
    // 这里固定成纯白（深色主题下也是白点，对比反而更强），
    // 同时把轨道压深一档，保证两种主题下都能一眼看出「关着」。
    val switchColors = SwitchDefaults.colors(
        uncheckedThumbColor = Color.White,
        uncheckedTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        uncheckedBorderColor = Color.Transparent,
        uncheckedIconColor = Color.Transparent
    )

    // 每次打开弹窗重算一次权限状态：用户可能刚在系统设置里改过
    val notificationAllowed = remember(settings.reminderEnabled) {
        notificationsAllowed(context)
    }

    // Android 13+ 通知是运行时权限。点开关时才申请，不主动弹
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onEnabledChange(true)
        } else {
            toast(context, "没有通知权限，提醒会被系统拦掉")
            // 仍然把开关打开：用户之后在系统设置里授权，功能立刻生效，
            // 不用回来再点一次
            onEnabledChange(true)
        }
    }

    // 日历是读写两个权限，必须一起给
    val calendarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_CALENDAR] == true &&
            result[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            onCalendarSyncChange(true)
        } else {
            toast(context, "没有日历权限，无法写入系统日历")
        }
    }

    fun toggleReminder(enable: Boolean) {
        if (!enable) {
            onEnabledChange(false)
            return
        }
        if (!notificationsAllowed(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // 权限给了但总开关被关（用户自己关的）→ 只能引导去设置
                toast(context, "通知已被系统关闭，请到设置里打开")
                onEnabledChange(true)
            }
        } else {
            onEnabledChange(true)
        }
    }

    fun toggleCalendar(enable: Boolean) {
        if (!enable) {
            onCalendarSyncChange(false)
            return
        }
        if (CourseCalendarSync.hasPermission(context)) {
            onCalendarSyncChange(true)
        } else {
            calendarLauncher.launch(
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 必须是**不透明**底色。iOS 液态玻璃风格里 surface 是半透明白，
        // 直接拿它当底色会让整个弹窗透出下面的课表，字压在课程块上完全没法看。
        // opaqueSurface 会把它和页面底色合成成实色。
        containerColor = opaqueSurface(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 全展开后内容可能仍高于可用高度（小屏 / 放大字体），
                // 让它自己可滚，别把最底下的开关挤没了。
                // 滚轮本身是固定高度的 LazyColumn，手势会优先被它吃掉，
                // 只有手指落在滚轮外面时才滚整个弹窗。
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HyperSpacing.pageHorizontal)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "课程提醒",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(14.dp))

            // ── 开关一：App 通知 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "上课前提醒我",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "按课表自动定时，没课的日子不会响",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.reminderEnabled,
                    onCheckedChange = ::toggleReminder,
                    colors = switchColors
                )
            }

            // 权限被系统关掉时，开关亮着却不响，必须明说
            if (settings.reminderEnabled && !notificationAllowed) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "系统通知已关闭，提醒不会弹出",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { openNotificationSettings(context) }) {
                        Text("去设置", fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 提前量 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "提前多久",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = advanceText(localAdvance),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (settings.reminderEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(Modifier.height(2.dp))

            // 两列滚轮：「小时」0–4 +「分钟」0–55（5 分钟一档）。
            // 顶到 4 小时（240 分钟，上限）时分钟轮只剩 0 一项，
            // 免得用户滚出一堆会被静默夹回去的无效值
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                WheelColumn(
                    label = "小时",
                    index = hour,
                    count = ReminderPlanner.MAX_ADVANCE / 60 + 1,
                    formatter = { it.toString() },
                    enabled = settings.reminderEnabled,
                    onIndexChange = { h -> pickAdvance(ReminderPlanner.joinAdvance(h, minute)) },
                    modifier = Modifier.width(88.dp)
                )
                Spacer(Modifier.width(28.dp))
                WheelColumn(
                    label = "分钟",
                    index = minute / ReminderPlanner.ADVANCE_STEP,
                    count = if (hour * 60 >= ReminderPlanner.MAX_ADVANCE) {
                        1
                    } else {
                        60 / ReminderPlanner.ADVANCE_STEP
                    },
                    formatter = { i ->
                        (i * ReminderPlanner.ADVANCE_STEP).toString().padStart(2, '0')
                    },
                    enabled = settings.reminderEnabled,
                    onIndexChange = { m ->
                        pickAdvance(
                            ReminderPlanner.joinAdvance(hour, m * ReminderPlanner.ADVANCE_STEP)
                        )
                    },
                    modifier = Modifier.width(88.dp)
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "提醒时间根据已同步的课表和教学周历计算。还没登录或没同步过课表时，这里打开也不会有提醒。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Spacer(Modifier.height(18.dp))

            // ── 开关二：系统日历 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "同步到系统日历",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "整学期课表写成日历事件，由系统负责提醒",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.calendarSyncEnabled,
                    onCheckedChange = ::toggleCalendar,
                    colors = switchColors
                )
            }

            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Bullet("不需要 App 在后台运行，强行停止 App 也能提醒")
                Bullet("会新建一个「课表」日历，只放课程，不动你原有的日程")
                Bullet("关闭开关时会清空这个日历里的课程")
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "两条通道可以只开一条。只想完全无感，开「同步到系统日历」就够了。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text(
            text = "·",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** "15 分钟" / "1 小时" / "1 小时 20 分钟" / "准时" */
internal fun advanceText(minutes: Int): String = when {
    minutes <= 0 -> "上课时提醒"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    minutes > 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    else -> "$minutes 分钟"
}

internal fun notificationsAllowed(context: Context): Boolean =
    NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
        .onFailure { toast(context, "打不开系统设置，请手动到「设置 → 通知」里打开") }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}
