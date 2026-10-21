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
import androidx.compose.ui.Alignment
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

/**
 * 原生登录页（CAS + SM2）。
 *
 * 替代 WebView 方案——WebView 会被学校页面检测导致白屏，
 * 原生实现完全绕过前端检测。
 */
class NativeLoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Study1Theme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NativeLoginScreen(onClose = { finish() })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeLoginScreen(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val auth = remember { BistuAuth() }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf<List<String>>(emptyList()) }
    var success by remember { mutableStateOf(false) }

    fun addLog(msg: String) {
        logs = (logs + "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}] $msg").takeLast(50)
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
            // ── 输入区 ──
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
                            text = if (showPassword) "隐藏" else "显示",
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
                        addLog("❌ 学号和密码不能为空")
                        return@Button
                    }
                    loading = true
                    logs = emptyList()
                    scope.launch {
                        try {
                            val authWithLog = BistuAuth { msg ->
                                // 把日志同步到 UI（用普通变量，重组时更新）
                                android.util.Log.d("NativeLogin", msg)
                            }
                            // 直接用 auth 实例（带日志回调不方便，改用静态日志）
                            val result = auth.fullLogin(username, password)
                            addLog("登录返回: code=${result.code}, message=${result.message}")

                            if (result.isSuccess) {
                                addLog("✅ 登录成功！")
                                addLog("Cookie 数: ${auth.getAllCookiesHeader().length} 字符")

                                // 立即测试拉课表
                                addLog("正在获取课表…")
                                val api = JwxtApi(auth) { msg ->
                                    android.util.Log.d("JwxtApi", msg)
                                }
                                val schedule = api.fetchSchedule()
                                addLog("✅ 课表获取成功！")
                                addLog("共 ${schedule.courses.size} 条课程")
                                addLog("班级: ${schedule.className}")
                                addLog("学生: ${schedule.studentName}")
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

            // ── 日志区 ──
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

            if (success) {
                Text(
                    text = "✅ 登录成功，可以返回课表页了",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
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
