package com.example.study1.ui.login

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.study1.ui.theme.Study1Theme

/**
 * WebView 登录页（可行性验证版）。
 *
 * 流程：
 *  1. 加载学校统一身份认证页面
 *  2. 用户手动输入账号密码登录
 *  3. 监听页面跳转，检测到进入教务系统后提取 Cookie
 *  4. 把 Cookie 返回给调用方
 *
 * 这样完全绕开了 SM2 加密、验证码、MFA 等复杂逻辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
class WebViewLoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebViewLogin"

        /** 学校统一身份认证地址 */
        const val LOGIN_URL = "https://uc.bistu.edu.cn"

        /** 登录成功后跳转到的域名（教务系统） */
        const val TARGET_HOST = "jwxt.bistu.edu.cn"

        /** 结果 extra：登录后拿到的 Cookie */
        const val EXTRA_COOKIE = "cookie"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许 WebView 保存 Cookie（重要：登录态靠它）
        CookieManager.getInstance().setAcceptCookie(true)

        setContent {
            Study1Theme {
                var statusText by remember { mutableStateOf("正在加载登录页…") }
                var cookieResult by remember { mutableStateOf<String?>(null) }
                var webViewRef by remember { mutableStateOf<WebView?>(null) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("登录（验证版）", fontSize = 16.sp) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
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
                    ) {
                        // ── 状态栏 ──
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = statusText,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (cookieResult != null) {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "✅ 已获取 Cookie（${cookieResult!!.length} 字符）",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Row {
                                        Button(onClick = {
                                            // 把 Cookie 返回给上一个页面
                                            val data = Intent().apply {
                                                putExtra(EXTRA_COOKIE, cookieResult)
                                            }
                                            setResult(RESULT_OK, data)
                                            finish()
                                        }) {
                                            Text("使用此登录态")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = {
                                            webViewRef?.reload()
                                        }) {
                                            Text("重新加载")
                                        }
                                    }
                                }
                            }
                        }

                        // ── WebView ──
                        AndroidView(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webViewRef = this

                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        // 保持登录状态
                                        CookieManager.getInstance().setAcceptCookie(true)
                                        // 自适应屏幕
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        // 允许混合内容（部分学校系统可能用 HTTP）
                                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                                        // 允许缩放
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        // 关键：伪装成 Chrome 移动版 UA
                                        // 学校 SPA 可能检测 WebView 的 "; wv" 标记并拒绝渲染
                                        userAgentString =
                                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                            "Chrome/145.0.0.0 Mobile Safari/537.36"
                                    }
                                    // 允许第三方 Cookie（CAS 跨域登录需要）
                                    CookieManager.getInstance()
                                        .setAcceptThirdPartyCookies(this, true)

                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(
                                            view: WebView,
                                            url: String
                                        ) {
                                            super.onPageFinished(view, url)
                                            Log.d(TAG, "页面加载完成: $url")
                                            statusText = "当前页面: $url"

                                            // 检查是否已经进入教务系统（说明登录成功）
                                            if (url.contains(TARGET_HOST)) {
                                                val cookie = CookieManager.getInstance()
                                                    .getCookie(url)
                                                Log.d(TAG, "Cookie: ${cookie?.take(200)}")

                                                if (!cookie.isNullOrBlank()) {
                                                    cookieResult = cookie
                                                    statusText = "✅ 登录成功！已进入教务系统"
                                                } else {
                                                    statusText = "⚠️ 已跳转但未取到 Cookie"
                                                }
                                            }
                                        }

                                        override fun shouldOverrideUrlLoading(
                                            view: WebView,
                                            request: WebResourceRequest
                                        ): Boolean {
                                            val url = request.url.toString()
                                            Log.d(TAG, "跳转: $url")
                                            // 让 WebView 自己处理所有跳转
                                            return false
                                        }
                                    }

                                    loadUrl(LOGIN_URL)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CookieManager.getInstance().flush()
    }
}
