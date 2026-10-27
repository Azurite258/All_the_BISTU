package com.bistu.allthebistu.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.ScheduleRepository
import com.bistu.allthebistu.data.TermWeek
import com.bistu.allthebistu.data.auth.JwxtApi
import com.bistu.allthebistu.data.auth.OffCampusException
import com.bistu.allthebistu.data.auth.SessionExpiredException
import com.bistu.allthebistu.data.classroom.EmptyRoomFinder
import com.bistu.allthebistu.data.classroom.RoomScheduleParser
import com.bistu.allthebistu.data.db.AppDatabase
import com.bistu.allthebistu.data.db.toTermWeek
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.schedule.DateUtils
import com.bistu.allthebistu.ui.theme.HyperSpacing
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 生活页的「空教室」入口卡片。
 *
 * 点开是 [EmptyRoomSheet]：选教学楼 + 今天/明天 + 节次段，列出这个时段空着的教室。
 *
 * 数据是「课程时间地点表」反查出来的（教务没有直接的「空教室」接口），
 * 见 [EmptyRoomFinder]。反查规则、边界、周次解析都在那个文件里，
 * 这里只负责把选择条件传进去、把结果摆出来。
 */
@Composable
fun EmptyRoomCard(modifier: Modifier = Modifier) {
    var showSheet by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showSheet = true },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "空教室",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (AuthHolder.isLoggedIn) {
                        "按教学楼、节次查这个时段哪些教室空着"
                    } else {
                        "登录后可用（要问教务系统）"
                    },
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = "查询",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    if (showSheet) {
        EmptyRoomSheet(onDismiss = { showSheet = false })
    }
}

/** 一次查询需要的上下文：学期、教学周、当前选中的日期/节次 */
private data class RoomQueryState(
    val termCode: String = "",
    val termWeeks: List<TermWeek> = emptyList(),
    val parsed: RoomScheduleParser.Parsed? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 选中的节次段（小节编号） */
private val SECTION_RANGES = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EmptyRoomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(RoomQueryState()) }
    var dayOffset by remember { mutableStateOf(0) }          // 0=今天 1=明天
    var building by remember { mutableStateOf("") }           // ""=全部
    var sectionIndex by remember { mutableStateOf(1) }        // 默认 3-4 节

    fun load() {
        if (state.loading) return
        val auth = AuthHolder.auth
        if (auth == null) {
            state = state.copy(error = "还没登录，先登录再去查")
            return
        }
        state = state.copy(loading = true, error = null)
        scope.launch {
            try {
                val dao = AppDatabase.get(context).scheduleDao()
                val meta = dao.getLatestMeta()
                val termCode = meta?.termCode ?: ScheduleRepository.DEFAULT_TERM
                val weeks = dao.getTermWeeks(termCode).map { it.toTermWeek() }

                val payload = JwxtApi(auth).fetchRoomSchedule(termCode)
                val parsed = RoomScheduleParser.toLessons(payload)
                AppLog.d(
                    "EmptyRoom",
                    "时间地点表: 行=${payload.rows.size} 解析=${parsed.lessons.size} " +
                        "丢弃=${parsed.unmapped} 字段=[${parsed.sampleKeys}]"
                )
                if (parsed.lessons.isEmpty()) {
                    state = state.copy(
                        loading = false,
                        termCode = termCode,
                        termWeeks = weeks,
                        error = "一条排课都没解析出来（字段对不上，字段名=${parsed.sampleKeys.ifBlank { "空" }}）",
                    )
                } else {
                    state = RoomQueryState(
                        termCode = termCode,
                        termWeeks = weeks,
                        parsed = parsed,
                        loading = false,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                AppLog.e("EmptyRoom", "拉时间地点表失败", e)
                state = state.copy(loading = false, error = describe(e))
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    val target = remember(dayOffset) { dateOf(dayOffset) }
    val weekOfTarget = remember(state.termWeeks, target) {
        // DateUtils 的 isoFormat 是私有的，这里自己格式化一份
        DateUtils.currentWeekOfTermWeeks(state.termWeeks, isoTarget(target))
    }
    val dayOfWeek = remember(target) { dayOfWeekOf(target) }

    val emptyRooms = remember(state.parsed, weekOfTarget, dayOfWeek, building, sectionIndex) {
        val p = state.parsed
        val week = weekOfTarget
        if (p == null || week == null) emptyList() else {
            val range = SECTION_RANGES[sectionIndex.coerceIn(SECTION_RANGES.indices)]
            EmptyRoomFinder.findEmpty(
                lessons = p.lessons,
                // 只按教学楼过滤时，也只能用「这学期排过课」的教室（见 Parsed.rooms 的注释）
                allRooms = if (building.isBlank()) {
                    p.rooms
                } else {
                    p.lessons.filter { it.building == building }.map { it.room }.distinct()
                },
                week = week,
                dayOfWeek = dayOfWeek,
                fromSection = range.first,
                toSection = range.second,
            )
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        // 一次点开直接到最大；返回键一次关闭，不再要两下
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = HyperSpacing.pageHorizontal)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "空教室",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = buildString {
                        if (weekOfTarget != null) append("第 ${weekOfTarget} 周 · ")
                        append(weekdayName(dayOfWeek))
                        append(" · ")
                        append(SimpleDateFormat("M月d日", Locale.CHINA).format(target))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                if (state.loading) {
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

            Spacer(Modifier.height(14.dp))

            // ── 今天 / 明天 ──
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("今天", dayOffset == 0) { dayOffset = 0 }
                ChoiceChip("明天", dayOffset == 1) { dayOffset = 1 }
            }

            // ── 教学楼 ──
            val buildings = state.parsed?.buildings.orEmpty()
            if (buildings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoiceChip("全部", building.isBlank()) { building = "" }
                    buildings.forEach { b ->
                        ChoiceChip(b, building == b) { building = b }
                    }
                }
            }

            // ── 节次 ──
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SECTION_RANGES.forEachIndexed { i, range ->
                    ChoiceChip(
                        "${range.first}-${range.second} 节",
                        sectionIndex == i,
                    ) { sectionIndex = i }
                }
            }

            Spacer(Modifier.height(16.dp))

            when {
                state.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                            .padding(14.dp)
                    ) {
                        Text(
                            text = state.error!!,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }

                state.loading && state.parsed == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "正在拉全学期排课表…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                weekOfTarget == null -> {
                    Text(
                        text = "今天不在教学周内，没有课表可比。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> {
                    Text(
                        text = "共 ${emptyRooms.size} 间空着",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (emptyRooms.isEmpty()) {
                        Text(
                            text = "这个时段所有教室都有课。换个节次或教学楼试试。",
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            emptyRooms.forEach { room ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = room,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "只含这学期排过课的教室；整学期没排课的教室不在这张表里。",
                            fontSize = 10.sp,
                            lineHeight = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 12.sp) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            selectedLabelColor = MaterialTheme.colorScheme.primary,
        ),
        border = null,
    )
}

/** 网络/会话失败翻译成人话 */
private fun describe(e: Exception): String = when (e) {
    is OffCampusException -> "现在进不去教务系统：校外要连校园网或学校 VPN。"
    is SessionExpiredException -> "登录态失效了，去「我的」重新登录一次。"
    is java.net.UnknownHostException -> "网络没通（DNS 解析失败）。"
    is java.net.SocketTimeoutException -> "连接教务超时，稍后再试。"
    else -> "${e.javaClass.simpleName}: ${e.message}"
}

private fun dateOf(offsetDays: Int): Date {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, offsetDays)
    return cal.time
}

private fun isoTarget(date: Date): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(date)

/** 1..7，周一 = 1 */
private fun dayOfWeekOf(date: Date): Int {
    val cal = Calendar.getInstance()
    cal.time = date
    val c = cal.get(Calendar.DAY_OF_WEEK) // 周日=1
    return if (c == Calendar.SUNDAY) 7 else c - 1
}

private fun weekdayName(day: Int): String =
    listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日").getOrElse(day - 1) { "周?" }
