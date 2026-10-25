package com.bistu.allthebistu.ui.profile

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.bistu.allthebistu.data.prefs.AppSettings
import com.bistu.allthebistu.data.prefs.AvatarStore
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.data.prefs.FontLevel
import com.bistu.allthebistu.data.prefs.SettingsStore
import com.bistu.allthebistu.data.prefs.ThemeMode
import com.bistu.allthebistu.ui.components.DiscreteSlider
import com.bistu.allthebistu.ui.schedule.CourseColors
import com.bistu.allthebistu.ui.schedule.CourseTypography
import com.bistu.allthebistu.ui.theme.HyperSpacing

/**
 * 「我的」页。
 *
 * 这里同时承担两件事：
 *  1. 账号身份（头像、昵称、学号脱敏）——原来四个 Tab 里唯一没有内容的页面
 *  2. **全局设置入口**——顶栏那个齿轮已经拿掉了，主题/配色/字体都收在这里
 *
 * 字体档位在这里是全局的：除了课表（它按列宽反算字号，见 ScheduleGrid 的说明），
 * 整个 App 的文字都会跟着缩放，具体倍率见 [com.bistu.allthebistu.data.prefs.AppFontScale]。
 *
 * 头像和昵称**只存本机**，退出登录时由 UserDataCleaner 一并清掉。
 */
@Composable
fun ProfileScreen(
    settings: AppSettings,
    isLoggedIn: Boolean,
    onThemeModeChange: (Int) -> Unit,
    onColorSchemeChange: (Int) -> Unit,
    onFontLevelChange: (Int) -> Unit,
    onNicknameChange: (String) -> Unit,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var showAccountSheet by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 换完头像文件已变，靠这个版本号把「重新解码」这件事推给重组
    var avatarVersion by remember { mutableIntStateOf(0) }
    val avatar = remember(avatarVersion) { AvatarStore.load(context) }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(
                start = HyperSpacing.pageHorizontal,
                end = HyperSpacing.pageHorizontal,
                top = 14.dp
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "我的",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 2.dp)
        )

        AccountCard(
            avatar = avatar,
            nickname = settings.nickname,
            isLoggedIn = isLoggedIn,
            onClick = {
                if (isLoggedIn) showAccountSheet = true else onLogin()
            }
        )

        // ══════ 外观 ══════
        SectionLabel("外观")

        SurfaceCard {
            SettingRowTitle("主题模式")
            Spacer(Modifier.height(8.dp))
            ThemeSegmented(
                current = settings.themeMode,
                onChange = onThemeModeChange
            )

            Divider()

            SettingRowTitle("课程配色")
            Spacer(Modifier.height(8.dp))
            ColorSchemeRow(
                current = settings.colorScheme,
                isDark = isDark,
                onChange = onColorSchemeChange
            )

            Divider()

            FontLevelBlock(
                level = settings.fontLevel,
                onChange = onFontLevelChange
            )
        }

        // ══════ 其他 ══════
        SectionLabel("其他")

        SurfaceCard {
            ActionRow(
                title = "关于与致谢",
                trailing = versionName(context),
                onClick = { showAbout = true }
            )
            Divider()
            ActionRow(
                title = "AI 学习助手",
                trailing = "后续开放",
                enabled = false,
                onClick = {}
            )
        }

        if (isLoggedIn) {
            Spacer(Modifier.height(2.dp))
            SurfaceCard {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { showLogoutConfirm = true }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "退出登录",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 底部留白要按「有没有那两条东西」分情况：
        // 未登录时除了悬浮导航栏，还有一条登录引导条压在上面（MainActivity 里那条，
        // 高度约 60dp + contentBottom），只按导航栏留白的话滚到底也还是被它盖住。
        Spacer(Modifier.height(if (isLoggedIn) 104.dp else 200.dp))
    }

    if (showAccountSheet) {
        AccountSheet(
            nickname = settings.nickname,
            avatar = avatar,
            onPickAvatar = { avatarVersion++ },
            onDismiss = { showAccountSheet = false },
            onSave = { newNickname ->
                onNicknameChange(newNickname)
                showAccountSheet = false
            }
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = {
                Text(
                    "退出会清除本机保存的全部个人信息：登录状态、记住的学号、课表缓存、" +
                        "一卡通数据、自建头像和昵称。\n\n" +
                        "清除后需要重新登录才能同步，确定退出吗？",
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text("退出并清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 账号卡
// ══════════════════════════════════════════════════════════════

/**
 * 账号卡。
 *
 * 用主色蓝而不是校园卡的橙金：校园卡是**身份凭证**（模仿实体卡），
 * 这张是**账号状态**，两者混在一起会看不出区别。
 *
 * 学号只显示中间脱敏的 4 位。看全号没什么必要——真要填学号的时候，
 * 学生自己记得住；把全号常年摆在首页反而容易被截图带出去。
 */
@Composable
private fun AccountCard(
    avatar: android.graphics.Bitmap?,
    nickname: String,
    isLoggedIn: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val studentNo = if (isLoggedIn) rememberStudentNo() else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isLoggedIn) primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像：有自定义图就用图，没有就退回「北」字
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isLoggedIn) Color.White.copy(alpha = 0.24f)
                        else MaterialTheme.colorScheme.surface
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar.asImageBitmap(),
                        contentDescription = "头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Text(
                        text = "北",
                        fontSize = 16.sp,
                        color = if (isLoggedIn) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 编辑角标：只在登录态显示，暗示「点头像可以换」
            if (isLoggedIn) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(primary)
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            if (isLoggedIn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "已登录",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    // 昵称和「点击设置昵称」共用同一个位置：设了显示昵称，没设显示引导语
                    Text(
                        text = if (nickname.isBlank()) " · 点击设置昵称" else " · $nickname",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (studentNo.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = maskStudentNo(studentNo),
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            } else {
                Text(
                    text = "未登录",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "点此登录，同步真实课表",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Text(
            text = "›",
            fontSize = 18.sp,
            color = if (isLoggedIn) Color.White.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 学号只在内存里读一次，登录状态变了才重读 */
@Composable
private fun rememberStudentNo(): String {
    val context = LocalContext.current
    val isLoggedIn = com.bistu.allthebistu.ui.login.AuthHolder.isLoggedIn
    return remember(isLoggedIn) {
        if (isLoggedIn) com.bistu.allthebistu.data.auth.SessionStore(context).loadUsername() else ""
    }
}

/** `202211258027` → `2022****8027`。太短的原样返回，避免遮成全星号 */
private fun maskStudentNo(raw: String): String {
    if (raw.length <= 6) return raw
    return raw.take(4) + "****" + raw.takeLast(4)
}

// ══════════════════════════════════════════════════════════════
// 账号设置弹层（头像 + 昵称）
// ══════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    nickname: String,
    avatar: android.graphics.Bitmap?,
    onPickAvatar: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState()

    var input by remember { mutableStateOf(nickname) }
    var noNickname by remember { mutableStateOf(nickname.isBlank()) }
    var pickFailed by remember { mutableStateOf(false) }

    // Photo Picker：不需要任何存储权限，选完由 AvatarStore 复制进私有目录
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (AvatarStore.save(context, uri)) onPickAvatar() else pickFailed = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HyperSpacing.pageHorizontal)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = "账号设置",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "头像和昵称只存在本机，不会同步到学校系统",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (avatar != null) {
                        Image(
                            bitmap = avatar.asImageBitmap(),
                            contentDescription = "头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Text("北", fontSize = 20.sp, color = Color.White)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = "更换头像",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            picker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = if (pickFailed) "这张图读不出来，换一张试试"
                        else "从相册选，自动裁成圆形",
                        fontSize = 11.sp,
                        color = if (pickFailed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Text(
                text = "昵称",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )

            NicknameField(
                value = input,
                dimmed = noNickname,
                onValueChange = {
                    // 一开始打字就说明用户想设昵称，自动把「不设置昵称」取消掉。
                    // 反过来如果把它做成「禁用输入框」，用户得先点一下那个选项才能打字，
                    // 多一步纯属添堵。
                    noNickname = false
                    input = it.take(SettingsStore.NICKNAME_MAX_LENGTH)
                }
            )

            Spacer(Modifier.height(12.dp))

            // 「不设置昵称」是一个正式选项，不是清空输入框的副作用
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        noNickname = !noNickname
                        if (noNickname) input = ""
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "不设置昵称",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "卡片上只显示「已登录」",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                RadioDot(selected = noNickname)
            }

            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable {
                        onSave(if (noNickname) "" else input.trim())
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "保存",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    }
}

/**
 * 昵称输入框。
 *
 * 用 [OutlinedTextField] 而不是手搓 BasicTextField：在 ModalBottomSheet 里
 * BasicTextField 拿不到焦点（点上去只出现 sheet 的 Drag handle 提示，键盘不起），
 * 换成 M3 的输入框后它自己处理焦点与 IME，行为才正常。
 * 外观靠 colors 调成「无边框 + 浅底」，和周围的圆角卡片一致。
 */
@Composable
private fun NicknameField(
    value: String,
    dimmed: Boolean,
    onValueChange: (String) -> Unit,
) {
    val textColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    else MaterialTheme.colorScheme.onSurface

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        placeholder = {
            Text(
                text = "例如：小林",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        },
        textStyle = TextStyle(fontSize = 14.sp, color = textColor),
        trailingIcon = {
            Text(
                text = "${value.length}/${SettingsStore.NICKNAME_MAX_LENGTH}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

/** 单选圆点（和 RadioButton 视觉一致，但不用占 48dp 的最小点击区） */
@Composable
private fun RadioDot(selected: Boolean) {
    val color = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(1.5.dp, color, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 外观设置
// ══════════════════════════════════════════════════════════════

/** 主题三段选择。比三个 RadioButton 占的纵向空间少一半 */
@Composable
private fun ThemeSegmented(current: Int, onChange: (Int) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to "跟随系统",
        ThemeMode.LIGHT to "浅色",
        ThemeMode.DARK to "深色"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { (mode, label) ->
            val selected = current == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface
                        else Color.Transparent
                    )
                    .clickable { onChange(mode) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 三套配色：每个都是「色卡 + 名字」，选中的加勾 */
@Composable
private fun ColorSchemeRow(current: Int, isDark: Boolean, onChange: (Int) -> Unit) {
    val options = listOf(
        CourseColorScheme.SCHOOL to "学校",
        CourseColorScheme.MORANDI to "莫兰迪",
        CourseColorScheme.DOPAMINE to "多巴胺"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (scheme, label) ->
            val selected = current == scheme
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        shape = RoundedCornerShape(10.dp)
                    )
                    .clickable { onChange(scheme) }
                    .padding(vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    CourseColors.sampleSwatches(scheme, isDark).take(4).forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(c)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(11.dp)
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * 字体档位。
 *
 * 标题写「全局字体大小」而不是「课表字体」——它现在真的影响整个 App。
 * 下面的预览块仍然是课表样张（因为课表是最容易看出差别的界面），
 * 但要包一层 fontScale=1，否则预览会比课表里实际渲染的大一圈。
 */
@Composable
private fun FontLevelBlock(level: Int, onChange: (Int) -> Unit) {
    val safe = FontLevel.sanitize(level)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "字体大小",
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${FontLevel.label(safe)} · 课名 ${CourseTypography.charsPerLine(safe)} 字/行",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary
        )
    }

    DiscreteSlider(
        value = safe,
        onValueChange = onChange,
        steps = 3,
        modifier = Modifier.padding(top = 4.dp)
    )

    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        listOf("小", "中", "大").forEachIndexed { index, label ->
            Text(
                text = label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = when (index) {
                    0 -> TextAlign.Start
                    1 -> TextAlign.Center
                    else -> TextAlign.End
                },
                modifier = Modifier.weight(1f)
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    // 课表样张：必须关掉全局 fontScale，否则「大」档下预览比实际课表还大
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = 1f)
    ) {
        FontPreview(level = safe)
    }
}

/**
 * 课表样张：按真实列宽渲染一个「2 节课」的课块，和课表里看到的完全同构。
 *
 * 不做「1 节课」的预览，是因为那种课块在课表里本来就不显示时间和教室
 * （56dp 放不下三行），预览要能代表实际效果才不会误导。
 */
@Composable
private fun FontPreview(level: Int) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val columnWidth = CourseTypography.columnWidthDp(screenWidthDp)
    val chars = CourseTypography.charsPerLine(level)
    val titleSp = CourseTypography.titleSizeSp(columnWidth, chars)

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
                .height(112.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(bg)
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
            text = "样张按手机真实列宽渲染，和课表里一模一样",
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 通用小组件
// ══════════════════════════════════════════════════════════════

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun SurfaceCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content
    )
}

@Composable
private fun SettingRowTitle(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    )
}

@Composable
private fun ActionRow(
    title: String,
    trailing: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 14.sp,
            color = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.weight(1f)
        )
        Text(
            text = trailing,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.55f
            )
        )
        if (enabled) {
            Text(
                text = " ›",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Text(
                    text = "All the BISTU · v${versionName(context)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "北京信息科技大学学生自制的课程表 App。" +
                        "课表、成绩、一卡通数据都直接来自学校系统，不经过任何第三方服务器。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "登录使用学校统一身份认证（CAS + 国密 SM2），" +
                        "密码只在登录那一瞬使用，不会被保存。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "感谢为这个项目提供反馈的同学。",
                    fontSize = 12.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 读安装包的 versionName，不在代码里硬编码版本号 */
private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "—"
}.getOrDefault("—")
