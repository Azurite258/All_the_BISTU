package com.example.study1.ui.login

import android.os.Bundle
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.study1.data.auth.BistuAuth
import com.example.study1.data.auth.JwxtApi
import com.example.study1.ui.theme.Study1Theme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局登录状态持有者。
 *
 * 用 mutableStateOf 让 Compose 能观察到变化，登录成功后主界面自动刷新。
 */
object AuthHolder {
    var auth: BistuAuth? = null
        private set

    /** Compose 可观察的登录状态 */
    var isLoggedIn by mutableStateOf(false)
        private set

    fun setAuth(newAuth: BistuAuth) {
        auth = newAuth
        isLoggedIn = newAuth.isLoggedIn()
    }

    fun clear() {
        auth?.clearCookies()
        auth = null
        isLoggedIn = false
    }
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

                                // 保存到全局，供主界面使用
                                AuthHolder.setAuth(auth)
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
