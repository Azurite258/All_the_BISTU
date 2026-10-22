package com.example.study1.ui.login

import android.content.Context
import android.os.Bundle
import android.util.Log
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
import com.example.study1.data.auth.BistuAuth
import com.example.study1.data.auth.JwxtApi
import com.example.study1.data.auth.SessionStore
import com.example.study1.ui.theme.Study1Theme
import kotlinx.coroutines.launch
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
            Log.d("AuthHolder", "setAuth: 已持久化 $count 个 Cookie")
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
            Log.d("AuthHolder", "restore: 没有可恢复的会话")
            return false
        }

        val newAuth = BistuAuth()
        val count = newAuth.restoreCookies(stored)
        if (count == 0) {
            Log.d("AuthHolder", "restore: Cookie 全部还原失败，清理存储")
            SessionStore(context).clear()
            return false
        }

        auth = newAuth
        isLoggedIn = newAuth.isLoggedIn()
        restoredFromDisk = isLoggedIn
        Log.d("AuthHolder", "restore: 还原 $count 个 Cookie, isLoggedIn=$isLoggedIn")
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

    /** 退出登录：清内存 + 清磁盘 */
    fun clear(context: Context? = null) {
        auth?.clearCookies()
        auth = null
        isLoggedIn = false
        restoredFromDisk = false
        // 复位恢复标记：退出后若用户重新登录并重启，仍应能正常恢复
        restored = false
        val ctx = context ?: appContext
        if (ctx != null) SessionStore(ctx).clear()
        Log.d("AuthHolder", "clear: 已退出登录")
    }

    /** Application Context（由 restore / setAuth 注入） */
    private var appContext: Context? = null
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

    var username by remember { mutableStateOf("") }
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
                            val result = auth.fullLogin(username, password.trim())
                            addLog("登录返回: code=${result.code} ${result.message}")

                            if (result.isSuccess) {
                                addLog("✅ 登录成功！")

                                // 立即验证能否拉课表
                                addLog("正在获取课表…")
                                val api = JwxtApi(auth)
                                val schedule = api.fetchSchedule()

                                addLog("✅ 课表获取成功！")
                                addLog("共 ${schedule.courses.size} 条课程")
                                addLog("学生: ${schedule.studentName}")
                                addLog("班级: ${schedule.className}")
                                addLog("学期: ${schedule.termCode}")

                                // 保存到全局并持久化，供主界面使用 & 下次免登录
                                AuthHolder.setAuth(auth, context)
                                addLog("✅ 会话已保存，下次启动免登录")
                                success = true
                            } else {
                                addLog("❌ 登录失败: ${result.message}")
                            }
                        } catch (e: Exception) {
                            addLog("❌ 异常: ${e.javaClass.simpleName}: ${e.message}")
                            android.util.Log.e("NativeLogin", "登录异常", e)
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
