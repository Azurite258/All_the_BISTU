package com.bistu.allthebistu

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import com.bistu.allthebistu.data.Source
import com.bistu.allthebistu.data.prefs.AppFontScale
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.data.prefs.SettingsStore
import com.bistu.allthebistu.data.prefs.ThemeMode
import com.bistu.allthebistu.data.reminder.CourseCalendarSync
import com.bistu.allthebistu.data.reminder.CourseReminderScheduler
import com.bistu.allthebistu.data.reminder.ReminderPlanner
import com.bistu.allthebistu.ui.LifeScreen
import com.bistu.allthebistu.ui.profile.ProfileScreen
import com.bistu.allthebistu.ui.reminder.ReminderSettingsSheet
import com.bistu.allthebistu.ui.study.StudyScreen
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.NativeLoginActivity
import com.bistu.allthebistu.ui.navigation.FloatingBottomBar
import com.bistu.allthebistu.ui.navigation.MainTab
import com.bistu.allthebistu.ui.schedule.ScheduleScreen
import com.bistu.allthebistu.ui.schedule.ScheduleViewModel
import com.bistu.allthebistu.ui.theme.AppWallpaper
import com.bistu.allthebistu.ui.theme.HyperSpacing
import com.bistu.allthebistu.ui.theme.LocalUiMetrics
import com.bistu.allthebistu.ui.theme.Study1Theme
import com.bistu.allthebistu.ui.theme.WallpaperBackdrop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 应用唯一入口。
 *
 * 这一层现在只做三件事：
 *  1. 装主题、读设置
 *  2. 在 4 个 Tab 之间切换 + 悬浮导航
 *  3. 处理登录引导条、设置页、占位提示这些「跨 Tab 的界面骨架」
 *
 * 课表的数据与状态都在 [ScheduleViewModel] 里，不再堆在 Activity 中。
 */
class MainActivity : ComponentActivity() {

    /**
     * 课表状态持有者。
     *
     * 用 Activity 作用域：旋屏、切换深色模式都会经历重组甚至重建，
     * ViewModel 能把这些场景下的课表数据和加载状态都保住，不重新请求网络。
     */
    private val scheduleViewModel: ScheduleViewModel by lazy {
        ViewModelProvider(this)[ScheduleViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val settingsStore = remember { SettingsStore(context) }
            val settings by settingsStore.settings.collectAsState(initial = AppSettings())

            Study1Theme(
                themeMode = settings.themeMode,
                uiStyle = settings.uiStyle
            ) {
                // 全局字体缩放：把「字体档位」翻译成 fontScale 乘到系统的 fontScale 上。
                // 用 CompositionLocal 而不是逐个改 sp，是因为全项目有几百处 fontSize，
                // 逐个改既不现实也必然会漏；这样一处覆盖，所有 sp 一起生效。
                val baseDensity = LocalDensity.current
                val appDensity = remember(
                    baseDensity.density,
                    baseDensity.fontScale,
                    settings.fontLevel
                ) {
                    Density(
                        density = baseDensity.density,
                        fontScale = baseDensity.fontScale * AppFontScale.of(settings.fontLevel)
                    )
                }
                CompositionLocalProvider(LocalDensity provides appDensity) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        // 设了壁纸就把底色让出来，交给下面的 WallpaperBackdrop
                        color = if (AppWallpaper.enabled) Color.Transparent
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WallpaperBackdrop(scrim = settings.wallpaperScrim)
                            AppRoot(
                                viewModel = scheduleViewModel,
                                settings = settings,
                                settingsStore = settingsStore
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRoot(
    viewModel: ScheduleViewModel,
    settings: AppSettings,
    settingsStore: SettingsStore
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    // 壁纸文件在私有目录里，启动时读一次（之后由外观页维护）
    LaunchedEffect(Unit) { AppWallpaper.refresh(context) }

    // ── 纯界面状态：不属于课表数据，留在 Compose 里 ──
    var currentTab by rememberSaveable { mutableStateOf(MainTab.SCHEDULE) }
    // 顶栏「添加日程」「日程提醒」的提示弹窗（功能尚未实现，先给明确反馈）
    var pendingFeature by remember { mutableStateOf<String?>(null) }
    // 课程提醒设置弹窗（顶栏和「我的」页两个入口共用同一个）
    var showReminderSheet by remember { mutableStateOf(false) }
    // 滑块去抖任务：拖动时每跨一档都会回调，不能每次都写库 + 重排闹钟
    var advanceJob by remember { mutableStateOf<Job?>(null) }

    /** 跳转登录页（登录成功后 AuthHolder 会变，下面的 LaunchedEffect 会自动刷新课表） */
    fun openLogin() {
        viewModel.dismissLoginPrompt()
        context.startActivity(Intent(context, NativeLoginActivity::class.java))
    }

    // AuthHolder.isLoggedIn 是 Compose 状态：从登录页返回后这里能立刻观察到变化
    val authLoggedIn = AuthHolder.isLoggedIn
    LaunchedEffect(authLoggedIn) { viewModel.onAuthChanged(authLoggedIn) }

    // 课表从网络刷新成功 → 把系统日历那份也跟着更新。
    // 不更新的话，用户改了课、换了教室，日历里还是开学时那份 —— 比不同步更糟。
    // 只在 NETWORK 来源时触发：缓存命中、示例数据都不算「课表变了」。
    LaunchedEffect(uiState.loadResult?.timestamp) {
        val result = uiState.loadResult
        if (result?.source == Source.NETWORK) {
            CourseCalendarSync.syncIfEnabled(context)
        }
    }

    // ── 深色模式 ──
    val systemDark = isSystemInDarkTheme()
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> systemDark
    }

    // 未实现功能的统一提示，避免点了没反应
    pendingFeature?.let { feature ->
        AlertDialog(
            onDismissRequest = { pendingFeature = null },
            title = { Text(feature) },
            text = { Text("这个功能还在开发中，敬请期待。") },
            confirmButton = {
                TextButton(onClick = { pendingFeature = null }) { Text("知道了") }
            }
        )
    }

    // ── 主框架：内容区 + 悬浮导航栏 ──
    Box(modifier = Modifier.fillMaxSize()) {

        when (currentTab) {
            MainTab.SCHEDULE -> ScheduleScreen(
                uiState = uiState,
                settings = settings,
                isDark = isDark,
                onRefresh = viewModel::refresh,
                onAddEvent = { pendingFeature = "添加日程" },
                onReminderSettings = { showReminderSheet = true },
                onOpenLogin = { openLogin() }
            )

            MainTab.STUDY -> StudyScreen(
                uiState = uiState,
                colorScheme = settings.colorScheme,
                isDark = isDark,
                report = uiState.studyReport
            )

            MainTab.LIFE -> LifeScreen()
            MainTab.PROFILE -> ProfileScreen(
                settings = settings,
                isLoggedIn = uiState.isLoggedIn,
                onUiStyleChange = { style -> scope.launch { settingsStore.setUiStyle(style) } },
                onThemeModeChange = { mode -> scope.launch { settingsStore.setThemeMode(mode) } },
                onColorSchemeChange = { scheme -> scope.launch { settingsStore.setColorScheme(scheme) } },
                onFontLevelChange = { level -> scope.launch { settingsStore.setFontLevel(level) } },
                onBottomBarStyleChange = { style ->
                    scope.launch { settingsStore.setBottomBarStyle(style) }
                },
                onNicknameChange = { name -> scope.launch { settingsStore.setNickname(name) } },
                onWallpaperScrimChange = { v ->
                    scope.launch { settingsStore.setWallpaperScrim(v) }
                },
                onLogin = { openLogin() },
                onLogout = { viewModel.logout() }
            )
        }

        // 未登录 → 每次启动在底部提示一次（不记忆、不打扰已登录用户）
        AnimatedVisibility(
            visible = uiState.showLoginPrompt && !uiState.isLoggedIn,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                // 与课表卡片的底部留白保持一致，两条底边才在同一条线上
                .padding(bottom = LocalUiMetrics.current.contentBottom)
        ) {
            LoginPromptBar(
                onLogin = { openLogin() },
                onDismiss = { viewModel.dismissLoginPrompt() }
            )
        }

        // 悬浮导航栏（底部加 insets，避开手势导航条/虚拟按键）
        FloatingBottomBar(
            currentTab = currentTab,
            onTabSelected = { currentTab = it },
            style = settings.bottomBarStyle,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )
    }

    // ── 课程提醒设置 ──
    // 顶栏和「我的」页两个入口共用这一个弹窗，避免两处 UI 各写一遍、
    // 以后改文案还得改两个地方
    if (showReminderSheet) {
        ReminderSettingsSheet(
            settings = settings,
            onDismiss = { showReminderSheet = false },
            onEnabledChange = { enabled ->
                scope.launch {
                    settingsStore.setReminderEnabled(enabled)
                    if (enabled) {
                        CourseReminderScheduler.rescheduleAsync(context)
                        toast(context, "已开启，未来一周的课会按设置提前提醒")
                    } else {
                        CourseReminderScheduler.cancelAll(context)
                        toast(context, "已关闭课程提醒")
                    }
                }
            },
            onAdvanceChange = { minutes ->
                // 拖滑块时每跨一档都会回调。写 DataStore、撤销+重排闹钟、
                // 重写整套日历事件都是重活，等手指停下来再做
                advanceJob?.cancel()
                advanceJob = scope.launch {
                    delay(ADVANCE_DEBOUNCE_MS)
                    settingsStore.setReminderAdvanceMinutes(minutes)
                    CourseReminderScheduler.rescheduleAsync(context)
                    // 提前量变了，日历里每条事件的 VALARM 也得跟着改，否则两边说法不一致
                    if (settings.calendarSyncEnabled) {
                        CourseCalendarSync.sync(context, ReminderPlanner.sanitizeAdvance(minutes))
                    }
                }
            },
            onCalendarSyncChange = { enabled ->
                scope.launch {
                    settingsStore.setCalendarSyncEnabled(enabled)
                    if (enabled) {
                        val result = CourseCalendarSync.sync(context, settings.reminderAdvanceMinutes)
                        toast(
                            context,
                            if (result.ok) {
                                "已写入系统日历：${result.eventCount} 条课程"
                            } else {
                                "同步失败：${result.reason}"
                            }
                        )
                    } else {
                        val cleared = CourseCalendarSync.clear(context)
                        toast(context, "已清掉日历里的 $cleared 条课程")
                    }
                }
            }
        )
    }
}

/** 提前量滑块的落盘去抖：拖动过程中不写盘，停手后一次性生效 */
private const val ADVANCE_DEBOUNCE_MS = 400L

private fun toast(context: android.content.Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

/**
 * 底部登录引导条（未登录时展示一次）。
 *
 * 视觉上要能看出是「浮在课表上方的一层」，但底色和课表卡片一样都是纯白时
 * 会糊成一片，所以这里用两件事拉开层次（HyperOS 不靠阴影，靠色差和描边）：
 *  1. 底色换成 surfaceVariant，比卡片深一档
 *  2. 加 0.5dp 发丝描边，给边缘一个明确的边界
 */
@Composable
private fun LoginPromptBar(
    onLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = HyperSpacing.pageHorizontal)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 0.5.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                shape = shape
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "还没登录",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "登录后可同步真实课表，要去登录吗？",
                fontSize = 11.sp,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = onDismiss) {
            Text("稍后", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = onLogin,
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("去登录", fontSize = 13.sp)
        }
    }
}
