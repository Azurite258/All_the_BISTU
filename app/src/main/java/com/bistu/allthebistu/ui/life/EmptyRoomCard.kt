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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.bistu.allthebistu.data.classroom.RoomDirectoryApi
import com.bistu.allthebistu.data.db.AppDatabase
import com.bistu.allthebistu.data.db.toTermWeek
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.schedule.DateUtils
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.opaqueSurface
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 生活页的「空教室」入口卡片。
 *
 * 点开是 [EmptyRoomSheet]：选教学楼 + 节次段，列出**今天**这个时段空着的教室。
 *
 * ## 数据怎么来的
 * 教务没有「给我空教室」的接口，也没有一次能拿全校课表的接口（模型注册表里没有），
 * 所以按教室逐个查：
 *  1. `jslb.do` 拿全部教室（686 间，含教学楼/楼层/座位数）
 *  2. `getScheduleDetail.do?CODE=<教室代码>&KBLX=01` 逐间拿课表
 *  3. 本地求差集（[EmptyRoomFinder]）
 *
 * 686 间逐间查太慢，所以**按教学楼分批**：选了哪栋楼才查哪栋（一栋 40-80 间），
 * 6 路并发，查过的进内存缓存，同一次运行里切回来不再请求。
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
                        "按教学楼查这个时段哪些教室空着"
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

/**
 * 节次段（小节编号）。
 *
 * 到 16 节：实测课表里出现过 11-14 节（晚上 18:31-21:45），
 * 只做到 12 节会把晚课漏在筛选范围外。
 */
private val SECTION_RANGES = listOf(
    1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12, 13 to 14, 15 to 16,
)

/** 同一次运行内缓存「教室 → 整学期占用」，切楼回来不用重查 */
private object RoomLessonCache {
    private val map = HashMap<String, List<EmptyRoomFinder.RoomLesson>>()

    fun get(key: String): List<EmptyRoomFinder.RoomLesson>? = synchronized(map) { map[key] }

    fun put(key: String, value: List<EmptyRoomFinder.RoomLesson>) {
        synchronized(map) { map[key] = value }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EmptyRoomSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current

    var termCode by remember { mutableStateOf("") }
    var termWeeks by remember { mutableStateOf<List<TermWeek>>(emptyList()) }
    var rooms by remember { mutableStateOf<List<RoomDirectoryApi.Room>>(emptyList()) }
    var building by remember { mutableStateOf("") }
    var lessons by remember {
        mutableStateOf<Map<String, List<EmptyRoomFinder.RoomLesson>>>(emptyMap())
    }

    var loadingDirectory by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0 to 0) }
    var error by remember { mutableStateOf<String?>(null) }

    var sectionIndex by remember { mutableStateOf(1) } // 默认 3-4 节

    // ① 教室清单 + 学期/教学周
    LaunchedEffect(Unit) {
        val auth = AuthHolder.auth
        if (auth == null) {
            error = "还没登录，先去「我的」登录一次"
            return@LaunchedEffect
        }
        loadingDirectory = true
        try {
            val dao = AppDatabase.get(context).scheduleDao()
            val meta = dao.getLatestMeta()
            termCode = meta?.termCode ?: ScheduleRepository.DEFAULT_TERM
            termWeeks = dao.getTermWeeks(termCode).map { it.toTermWeek() }

            val page = JwxtApi(auth).fetchRoomDirectory(termCode)
            rooms = page.rooms
            building = defaultBuilding(page.rooms)
            AppLog.d("EmptyRoom", "教室清单: ${page.rooms.size}/${page.total} 间")
        } catch (e: Exception) {
            AppLog.e("EmptyRoom", "拉教室清单失败", e)
            error = describe(e)
        } finally {
            loadingDirectory = false
        }
    }

    // ② 选中教学楼后补齐这一栋的课表（已缓存的不再请求）
    LaunchedEffect(building, termCode) {
        val auth = AuthHolder.auth ?: return@LaunchedEffect
        if (building.isBlank() || termCode.isBlank()) return@LaunchedEffect

        val targets = rooms.filter { it.building == building }
        if (targets.isEmpty()) return@LaunchedEffect

        val missing = targets.filter { RoomLessonCache.get(cacheKey(termCode, it.code)) == null }
        if (missing.isEmpty()) {
            lessons = collectLessons(termCode, targets)
            return@LaunchedEffect
        }

        busy = true
        error = null
        progress = 0 to missing.size

        val api = JwxtApi(auth)
        val sem = Semaphore(6)
        var done = 0
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())

        try {
            coroutineScope {
                missing.map { room ->
                    async {
                        sem.withPermit {
                            val key = cacheKey(termCode, room.code)
                            runCatching {
                                api.fetchRoomTimetable(termCode, room.code, room.name)
                            }.onSuccess {
                                RoomLessonCache.put(key, it)
                            }.onFailure {
                                AppLog.w("EmptyRoom", "教室 ${room.name} 课表失败: ${it.message}")
                                failed += room.name
                                // 失败也写空缓存，避免切楼回来又重试一遍把界面卡住
                                RoomLessonCache.put(key, emptyList())
                            }
                            done++
                            progress = done to missing.size
                        }
                    }
                }.awaitAll()
            }

            lessons = collectLessons(termCode, targets)
            if (failed.isNotEmpty()) {
                error = "有 ${failed.size} 间教室没查到（${failed.take(3).joinToString("、")}…），" +
                    "结果可能偏多，仅供参考"
            }
        } catch (e: Exception) {
            AppLog.e("EmptyRoom", "拉教室课表失败", e)
            error = describe(e)
        } finally {
            busy = false
        }
    }

    val target = remember { Date() }
    val weekOfTarget = remember(termWeeks, target) {
        DateUtils.currentWeekOfTermWeeks(termWeeks, isoTarget(target))
    }
    val dayOfWeek = remember(target) { dayOfWeekOf(target) }

    val emptyRooms = remember(lessons, weekOfTarget, dayOfWeek, building, sectionIndex) {
        val week = weekOfTarget
        if (week == null || building.isBlank()) {
            emptyList()
        } else {
            val range = SECTION_RANGES[sectionIndex.coerceIn(SECTION_RANGES.indices)]
            EmptyRoomFinder.findEmpty(
                lessons = lessons.values.flatten(),
                allRooms = lessons.keys.toList(),
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
        containerColor = opaqueSurface(MaterialTheme.colorScheme.surface),
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
            // ── 标题 ──
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
                        if (weekOfTarget != null) append("第 $weekOfTarget 周 · ")
                        append(weekdayName(dayOfWeek))
                        append(" · ")
                        append(SimpleDateFormat("M月d日", Locale.CHINA).format(target))
                    },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(14.dp))

            // ── 教学楼（教室清单里的真实楼栋，信息楼 A→D 在最前）──
            val buildings = remember(rooms) {
                RoomDirectoryApi.groupByBuilding(rooms).keys.toList()
            }
            if (buildings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                // 用 FlowRow 而不是横向滚动：信息楼拆成 A-D 后一共八九个按钮，
                // 横着滚的话「工学」那几栋藏在屏幕外，等于没有
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    ChoiceChip("${range.first}-${range.second} 节", sectionIndex == i) {
                        sectionIndex = i
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── 结果区 ──
            when {
                loadingDirectory -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "正在取教室清单…",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 教室清单都没拿到就别往下算了：显示「共 0 间空着」会让人以为
                // 真的排满了，其实是接口没返回数据（两种情况的处理方式完全不同）
                rooms.isEmpty() -> {
                    Text(
                        text = "教室清单没取到，所以算不出空教室。原因见下方。",
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                busy -> {
                    Text(
                        text = "正在查课表 ${progress.first}/${progress.second} 间…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (progress.second <= 0) 0f
                            else progress.first.toFloat() / progress.second
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                    )
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
                        text = "共 ${emptyRooms.size} 间空着" +
                            if (building.isNotBlank()) "（$building · ${lessons.size} 间已查）" else "",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    if (emptyRooms.isEmpty()) {
                        Text(
                            text = "这个时段这栋楼的教室都排满了。换个节次或教学楼试试。",
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
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                                        )
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
                    }
                }
            }

            error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
                        .padding(14.dp)
                ) {
                    Text(
                        text = msg,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/** 把这一栋的教室按「教室名 → 占用记录」组装起来（缓存里都该有了） */
private fun collectLessons(
    termCode: String,
    targets: List<RoomDirectoryApi.Room>,
): Map<String, List<EmptyRoomFinder.RoomLesson>> =
    targets.associate { it.name to (RoomLessonCache.get(cacheKey(termCode, it.code)) ?: emptyList()) }

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

private fun cacheKey(termCode: String, roomCode: String) = "$termCode|$roomCode"

/**
 * 默认选中哪栋楼：就取排序后的第一栋（信息楼A座）。
 *
 * 楼栋顺序由 [RoomDirectoryApi.groupByBuilding] 定好了，这里不再自己挑，
 * 免得「第一个按钮亮着、实际选的是别的楼」。
 */
private fun defaultBuilding(rooms: List<RoomDirectoryApi.Room>): String =
    RoomDirectoryApi.groupByBuilding(rooms).keys.firstOrNull().orEmpty()

/** 网络/会话失败翻译成人话 */
private fun describe(e: Exception): String = when (e) {
    is OffCampusException -> "现在进不去教务系统：校外要连校园网或学校 VPN。"
    is SessionExpiredException -> "登录态失效了，去「我的」重新登录一次。"
    is java.net.UnknownHostException -> "网络没通（DNS 解析失败）。"
    is java.net.SocketTimeoutException -> "连接教务超时，稍后再试。"
    else -> "${e.javaClass.simpleName}: ${e.message}"
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
