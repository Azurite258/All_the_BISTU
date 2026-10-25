package com.bistu.allthebistu.ui.login

import android.content.Context
import android.os.Bundle
import com.bistu.allthebistu.data.log.AppLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.auth.BistuAuth
import com.bistu.allthebistu.data.auth.JwxtApi
import com.bistu.allthebistu.data.auth.SessionStore
import com.bistu.allthebistu.ui.schedule.LoadErrorText
import com.bistu.allthebistu.ui.theme.Study1Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局登录状态持有者。
 *
 * 用 mutableStateOf 让 Compose 能观察到变化，登录成功后主界面自动刷新。
 *
 * 会话持久化：
 *  - 登录成功后把 Cookie 写入 [SessionStore]，冷启动时由 [restore] 还原。
 *  - 退出登录时同时清空内存与磁盘。
 */
object AuthHolder {
    var auth: BistuAuth? = null
        private set

    /** Compose 可观察的登录状态 */
    var isLoggedIn by mutableStateOf(false)
        private set

    /** 会话是否已从磁盘恢复过（避免重复恢复） */
    private var restored = false

    /** 当前会话是否来自持久化恢复（用于区分「刚登录」和「启动恢复」） */
    var restoredFromDisk: Boolean = false
        private set

    /**
     * 登录成功后调用：更新内存状态并落盘。
     */
    fun setAuth(newAuth: BistuAuth, context: Context? = null) {
        auth = newAuth
        isLoggedIn = newAuth.isLoggedIn()
        restoredFromDisk = false
        // 落盘：保存 Cookie 供下次冷启动使用
        val ctx = context ?: appContext
        if (ctx != null && isLoggedIn) {
            val count = newAuth.exportCookies().size
            SessionStore(ctx).save(newAuth.exportCookies())
            AppLog.d("AuthHolder", "setAuth: 已持久化 $count 个 Cookie")
        }
    }

    /**
     * App 启动时调用：从磁盘恢复会话。
     *
     * @return 恢复成功返回 true（此时 isLoggedIn 已为 true）
     */
    fun restore(context: Context): Boolean {
        if (restored) return isLoggedIn
        restored = true
        appContext = context.applicationContext

        val stored = SessionStore(context).load()
        if (stored.isEmpty()) {
            AppLog.d("AuthHolder", "restore: 没有可恢复的会话")
            return false
        }

        val newAuth = BistuAuth()
        val count = newAuth.restoreCookies(stored)
        if (count == 0) {
            AppLog.d("AuthHolder", "restore: Cookie 全部还原失败，清理存储")
            SessionStore(context).clear()
            return false
        }

        auth = newAuth
        isLoggedIn = newAuth.isLoggedIn()
        restoredFromDisk = isLoggedIn
        AppLog.d("AuthHolder", "restore: 还原 $count 个 Cookie, isLoggedIn=$isLoggedIn")
        return isLoggedIn
    }

    /**
     * 重新把当前 Cookie 落盘（例如同步成功后刷新有效期）。
     */
    fun persist(context: Context? = null) {
        val ctx = context ?: appContext ?: return
        val a = auth ?: return
        if (a.isLoggedIn()) {
            SessionStore(ctx).save(a.exportCookies())
        }
    }

    /**
     * 自动登录：尝试用已持久化的 Cookie 静默恢复登录态。
     *
     * 与 [restore] 的区别：
     *  - [restore] 只在 App 启动时调一次，用于还原内存中的 BistuAuth
     *  - [autoLogin] 是「确保登录可用」的入口
     *
     * **不做网络校验**（刻意的）：校验会产生一次请求，
     * 网络差时（连接超时 20s）会把首屏卡住。这里只负责把 Cookie 装回内存，
     * 真正的有效性由随后的 [ScheduleRepository.loadSchedule] 判定——
     * 它失败时会触发 onSessionExpired 回调清理会话。
     *
     * 注意：**不会重新输入账号密码**。不保存密码是刻意设计（安全优先）。
     */
    fun autoLogin(context: Context): AutoLoginResult {
        appContext = context.applicationContext

        // ① 内存里已经有可用会话 → 直接复用
        val existing = auth
        if (existing != null && existing.isLoggedIn()) {
            AppLog.d("AuthHolder", "autoLogin: 内存会话可用，直接复用")
            return AutoLoginResult.Success(reusedFromMemory = true)
        }

        // ② 尝试从磁盘恢复
        val stored = SessionStore(context).load()
        if (stored.isEmpty()) {
            AppLog.d("AuthHolder", "autoLogin: 没有持久化会话，需要手动登录")
            return AutoLoginResult.NeedManual(reason = "没有保存的登录状态")
        }

        val newAuth = BistuAuth()
        val count = newAuth.restoreCookies(stored)
        if (count == 0) {
            AppLog.d("AuthHolder", "autoLogin: Cookie 还原失败，需要手动登录")
            SessionStore(context).clear()
            return AutoLoginResult.NeedManual(reason = "登录状态已损坏")
        }

        // ③ 装上即可，不阻塞首屏。有效性交给后续请求判定。
        auth = newAuth
        isLoggedIn = newAuth.isLoggedIn()
        restoredFromDisk = isLoggedIn
        AppLog.d("AuthHolder", "autoLogin: 恢复 $count 个 Cookie，isLoggedIn=$isLoggedIn")
        return if (isLoggedIn) {
            AutoLoginResult.Success(reusedFromMemory = false)
        } else {
            AutoLoginResult.NeedManual(reason = "登录状态不完整")
        }
    }

    /** 退出登录：清内存 + 清磁盘 */
    fun clear(context: Context? = null) {
        auth?.clearCookies()
        auth = null
        isLoggedIn = false
        restoredFromDisk = false
        // 复位恢复标记：退出后若用户重新登录并重启，仍应能正常恢复
        restored = false
        val ctx = context ?: appContext
        if (ctx != null) SessionStore(ctx).clearAll()
        AppLog.d("AuthHolder", "clear: 已退出登录")
    }

    /** Application Context（由 restore / setAuth 注入） */
    private var appContext: Context? = null
}

/** 自动登录结果 */
sealed interface AutoLoginResult {
    /** 登录可用 */
    data class Success(
        /** true 表示直接复用了内存中的会话，没有走磁盘 */
        val reusedFromMemory: Boolean
    ) : AutoLoginResult

    /** 需要用户手动登录 */
    data class NeedManual(val reason: String) : AutoLoginResult
}

/**
 * 原生登录页（CAS + SM2）。
 *
 * 登录成功后把 BistuAuth 存到 AuthHolder，主界面刷新即可拉真实课表。
 */
class NativeLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Study1Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NativeLoginScreen(
                        onClose = { finish() },
                        onSuccess = { finish() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeLoginScreen(
    onClose: () -> Unit,
    onSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val auth = remember { BistuAuth() }

    // 预填上次登录的学号（只记学号，不保存密码）
    var username by remember { mutableStateOf(SessionStore(context).loadUsername()) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var success by remember { mutableStateOf(false) }

    fun addLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        logs = (logs + "[$time] $msg").takeLast(40)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录教务系统") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 学号 ──
            Text("学号", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !loading && !success,
                placeholder = { Text("请输入学号") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next
                )
            )

            // ── 密码 ──
            Text("密码", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !loading && !success,
                placeholder = { Text("请输入密码") },
                visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            if (showPassword) "隐藏" else "显示",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )

            // ── 登录按钮 ──
            Button(
                onClick = {
                    if (username.isBlank() || password.isBlank()) {
                        addLog("学号和密码不能为空")
                        return@Button
                    }
                    loading = true
                    logs = emptyList()
                    scope.launch {
                        try {
                            addLog("正在建立 SSO 会话…")
                            // 学号去掉首尾空白（复制粘贴常带空格）；
                            // 密码不能 trim —— 有的密码本身就以空格开头/结尾，
                            // 一 trim 就变成「密码错误」，而用户看不出问题在哪
                            val result = auth.fullLogin(username.trim(), password)
                            addLog("登录返回: code=${result.code} ${result.message}")
                            addLog(
                                "会话状态: flowKey " +
                                        auth.getFlowKey().let {
                                            if (it.isBlank()) "缺失" else "${it.length} 字符"
                                        }
                            )

                            if (result.isSuccess) {
                                addLog("✅ 统一身份认证登录成功！")

                                // 立即验证能否拉课表。
                                // 注意：这一步失败**不能**让整次登录算失败 ——
                                // 校外连不上教务是常态（教务只对校园网开放），
                                // 但 SSO 会话是有效的，存下来，连上校园网后能自动同步。
                                if (result.jwxtReady) {
                                    addLog("教务系统会话已建立，正在获取课表…")
                                    runCatching { JwxtApi(auth).fetchSchedule() }
                                        .onSuccess { schedule ->
                                            addLog("✅ 课表获取成功！")
                                            addLog("共 ${schedule.courses.size} 条课程")
                                            addLog("学生: ${schedule.studentName}")
                                            addLog("班级: ${schedule.className}")
                                            addLog("学期: ${schedule.termCode}")
                                        }
                                        .onFailure { e ->
                                            addLog("⚠️ 课表暂时没拉到：${LoadErrorText.describe(e)}")
                                        }
                                } else {
                                    addLog("⚠️ 已登录，但教务系统没连上")
                                    addLog("提示: 教务系统只对校园网开放，校外请先连校园网或学校 VPN。")
                                    addLog("登录状态已保存，连上校园网后打开 App 会自动同步。")
                                }

                                // 保存到全局并持久化，供主界面使用 & 下次免登录
                                AuthHolder.setAuth(auth, context)
                                // 记住学号（只记学号，不保存密码）
                                SessionStore(context).saveUsername(username.trim())
                                addLog("✅ 会话已保存，下次启动免登录")
                                success = true
                            } else {
                                addLog("❌ ${result.friendlyMessage}")
                                if (result.isSessionIssue) {
                                    addLog("提示: 若连校园网，请先在浏览器完成上网认证；关闭 VPN/代理后再试")
                                }
                            }
                        } catch (e: Exception) {
                            addLog("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
                            AppLog.e("NativeLogin", "登录异常", e)
                        } finally {
                            loading = false
                        }
                    }
                },
                enabled = !loading && !success,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("登录中…")
                } else {
                    Text("登录")
                }
            }

            // ── 成功后按钮 ──
            if (success) {
                Button(
                    onClick = onSuccess,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("进入课表")
                }
            }

            // ── 日志 ──
            if (logs.isNotEmpty()) {
                HorizontalDivider()
                Text("执行日志", fontSize = 13.sp)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        logs.forEach { line ->
                            Text(
                                text = line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = "说明：密码只在本机内存中用于 SM2 加密，不会保存、不会上传。",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
