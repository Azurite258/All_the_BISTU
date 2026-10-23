package com.bistu.allthebistu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.data.prefs.FontLevel
import com.bistu.allthebistu.data.prefs.ThemeMode
import com.bistu.allthebistu.ui.components.DiscreteSlider
import com.bistu.allthebistu.ui.schedule.CourseColors
import com.bistu.allthebistu.ui.schedule.CourseTypography

/**
 * 设置页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onThemeModeChange: (Int) -> Unit,
    onColorSchemeChange: (Int) -> Unit,
    onFontLevelChange: (Int) -> Unit = {},
    onBack: () -> Unit,
    isLoggedIn: Boolean = false,
    onLogout: () -> Unit = {}
) {
    // 拦截系统返回键：返回课表而不是退出 App
    BackHandler { onBack() }

    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = {
                Text(
                    "退出会清除本机保存的全部个人信息：" +
                            "登录状态、记住的学号、课表缓存（含姓名/学号/专业）" +
                            "以及后续校园卡等数据。\n\n" +
                            "清除后需要重新登录才能同步，确定退出吗？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text("退出并清除") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ══════ 主题模式 ══════
            SectionTitle("外观主题")

            ThemeOption(
                title = "跟随系统",
                subtitle = "随手机深色模式自动切换",
                selected = settings.themeMode == ThemeMode.SYSTEM,
                onClick = { onThemeModeChange(ThemeMode.SYSTEM) }
            )
            ThemeOption(
                title = "浅色模式",
                subtitle = "始终使用浅色主题",
                selected = settings.themeMode == ThemeMode.LIGHT,
                onClick = { onThemeModeChange(ThemeMode.LIGHT) }
            )
            ThemeOption(
                title = "深色模式",
                subtitle = "始终使用深色主题，夜间护眼",
                selected = settings.themeMode == ThemeMode.DARK,
                onClick = { onThemeModeChange(ThemeMode.DARK) }
            )

            Spacer(Modifier.height(24.dp))

            // ══════ 课程配色 ══════
            SectionTitle("课程配色")

            ColorSchemeOption(
                title = "学校配色",
                subtitle = "使用教务系统分配的颜色",
                scheme = CourseColorScheme.SCHOOL,
                selected = settings.colorScheme == CourseColorScheme.SCHOOL,
                onClick = { onColorSchemeChange(CourseColorScheme.SCHOOL) }
            )
            ColorSchemeOption(
                title = "莫兰迪色系",
                subtitle = "低饱和灰调，久看不累",
                scheme = CourseColorScheme.MORANDI,
                selected = settings.colorScheme == CourseColorScheme.MORANDI,
                onClick = { onColorSchemeChange(CourseColorScheme.MORANDI) }
            )
            ColorSchemeOption(
                title = "多巴胺色系",
                subtitle = "高饱和亮色，像小米 15 的自选色",
                scheme = CourseColorScheme.DOPAMINE,
                selected = settings.colorScheme == CourseColorScheme.DOPAMINE,
                onClick = { onColorSchemeChange(CourseColorScheme.DOPAMINE) }
            )

            Spacer(Modifier.height(24.dp))

            // ══════ 课表字体 ══════
            SectionTitle("课表字体")

            FontLevelSelector(
                level = settings.fontLevel,
                onChange = onFontLevelChange
            )

            Spacer(Modifier.height(24.dp))

            // ══════ 账号 ══════
            SectionTitle("账号")

            if (isLoggedIn) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showLogoutConfirm = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ExitToApp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "退出登录",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "清除课表、学号等本机全部个人信息",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "当前未登录，登录后可同步真实课表。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "提示：切换配色或字体后，课表会立即生效。",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun ThemeOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ColorSchemeOption(
    title: String,
    subtitle: String,
    scheme: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 色卡预览
        val swatchDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CourseColors.sampleSwatches(scheme, swatchDark).forEach { c ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c)
                        .border(0.5.dp, Color(0x33000000), RoundedCornerShape(3.dp))
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 课表字体挡位选择。
 *
 * 用 Slider 而不是三个单选，是因为「字号」天生是连续量，
 * 拖动时能直观看到预览块的变化；但落点只有 3 个（steps = 1），
 * 不会出现半档这种奇怪的值。
 */
@Composable
private fun FontLevelSelector(
    level: Int,
    onChange: (Int) -> Unit
) {
    val safeLevel = FontLevel.sanitize(level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "字体大小",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${FontLevel.label(safeLevel)} · 课名一行 ${CourseTypography.charsPerLine(safeLevel)} 个字",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        DiscreteSlider(
            value = safeLevel,
            onValueChange = onChange,
            steps = 3,
            modifier = Modifier.padding(top = 6.dp)
        )

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                text = "小",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "中",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
                text = "大",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }

        Spacer(Modifier.height(12.dp))

        FontPreview(level = safeLevel)
    }
}

/**
 * 预览：按真实列宽渲染一个「2 节课」的课块，和课表里看到的完全同构
 * （课名 + 时间 + 教室三行）。
 *
 * 不做「1 节课」的预览是因为那种课块在课表里本来就不显示时间和教室
 * （56dp 放不下三行），预览要能代表实际效果才不会被误导。
 */
@Composable
private fun FontPreview(level: Int) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val columnWidth = CourseTypography.columnWidthDp(screenWidthDp)
    val chars = CourseTypography.charsPerLine(level)
    val titleSp = CourseTypography.titleSizeSp(columnWidth, chars)

    // 跟随当前主题，避免深色模式下预览块依然是浅色
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val sample = "现代前沿传感技术"
    val bg = CourseColors.backgroundOf(
        originalHex = "#D3EAFD",
        courseName = sample,
        scheme = CourseColorScheme.SCHOOL,
        isDark = isDark
    )
    val fg = CourseColors.textColorOf(bg, isDark)
    val sub = CourseColors.subTextColorOf(bg, isDark)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(columnWidth.dp)
                // 2 节课的高度 = 2 × 56dp，和课表一致
                .height(112.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
                // 和 ScheduleGrid 的 CourseBlock 同样的内缩：外层 1.5dp + 内层 2dp
                .padding(1.5.dp)
        ) {
            Column(modifier = Modifier.padding(2.dp)) {
                Text(
                    text = sample,
                    fontSize = titleSp.sp,
                    lineHeight = (titleSp * 1.3f).sp,
                    fontWeight = FontWeight.Medium,
                    color = fg,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "09:50",
                    fontSize = (titleSp * 0.8f).sp,
                    lineHeight = (titleSp * 0.8f * 1.25f).sp,
                    color = sub.copy(alpha = 0.8f),
                    maxLines = 1
                )
                Text(
                    text = "@教室1",
                    fontSize = (titleSp * 0.9f).sp,
                    lineHeight = (titleSp * 0.9f * 1.25f).sp,
                    color = sub,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = "预览按手机真实列宽渲染，和课表里一模一样",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}
