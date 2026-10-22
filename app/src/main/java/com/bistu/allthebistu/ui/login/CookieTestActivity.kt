package com.bistu.allthebistu.ui.login

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Cookie 接口验证页。
 *
 * 目的：验证 WebView 拿到的 Cookie 能否调通课表接口。
 * 这是登录功能的最后一环——如果通了，白屏就不再是阻塞问题。
 */
class CookieTestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "CookieTest"
        private const val BASE = "https://jwxt.bistu.edu.cn"
        private const val SCHEDULE_API =
            "$BASE/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do"
        private const val TERM_CODE = "2026-2027-1"
        private const val CAMPUS_CODE = "10"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var result by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(false) }
            var cookie by remember { mutableStateOf("") }
            val scope = rememberCoroutineScope()
            val context = LocalContext.current

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text("Cookie 接口验证", fontSize = 20.sp)
                Spacer(Modifier.height(12.dp))

                Text("当前 Cookie:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = cookie.ifEmpty { "（未获取）" },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        // 从 WebView CookieManager 读取
                        cookie = android.webkit.CookieManager.getInstance()
                            .getCookie(BASE) ?: ""
                        Log.d(TAG, "读取 Cookie: ${cookie.take(100)}")
                    }) {
                        Text("读取 Cookie")
                    }

                    Button(
                        enabled = !loading && cookie.isNotBlank(),
                        onClick = {
                            loading = true
                            result = null
                            scope.launch {
                                result = testScheduleApi(cookie)
                                loading = false
                            }
                        }
                    ) {
                        Text(if (loading) "请求中…" else "测试接口")
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (loading) {
                    CircularProgressIndicator()
                }

                result?.let {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    /** 用 Cookie 调课表接口 */
    private suspend fun testScheduleApi(cookie: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        val body = FormBody.Builder()
            .add("XNXQDM", TERM_CODE)
            .add("XQDM", CAMPUS_CODE)
            .build()

        val request = Request.Builder()
            .url(SCHEDULE_API)
            .addHeader("Cookie", cookie)
            .addHeader("Referer", "$BASE/jwapp/sys/kbapp/*default/index.do?THEME=indigo")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
            )
            .post(body)
            .build()

        try {
            val response = client.newCall(request).execute()
            val text = response.body?.string() ?: ""

            Log.d(TAG, "HTTP ${response.code}, 响应长度 ${text.length}")
            Log.d(TAG, "响应前 500 字符: ${text.take(500)}")

            buildString {
                appendLine("HTTP ${response.code}")
                appendLine("响应长度: ${text.length} 字符")
                appendLine()

                // 尝试解析 JSON
                if (text.contains("\"code\"")) {
                    val codeMatch = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    val code = codeMatch?.groupValues?.get(1)
                    appendLine("code = $code")

                    if (code == "0") {
                        val count = Regex("\"courseName\"").findAll(text).count()
                        appendLine("✅ 接口调用成功！")
                        appendLine("检测到 $count 个 courseName 字段")
                        appendLine()
                        appendLine("响应前 800 字符:")
                        appendLine(text.take(800))
                    } else {
                        appendLine("⚠️ 接口返回失败")
                        appendLine(text.take(500))
                    }
                } else {
                    appendLine("❌ 响应不是 JSON（可能未登录，返回了 HTML 登录页）")
                    appendLine(text.take(500))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求失败", e)
            "❌ 请求失败: ${e.message}"
        }
    }
}
