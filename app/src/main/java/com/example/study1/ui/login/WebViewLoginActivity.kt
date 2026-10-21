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

        /**
         * 备选入口：直接进教务系统，让教务系统自己跳转到 CAS。
         * 有时 SPA 登录页在 WebView 里渲染失败，但从业务系统入口跳转能成功。
         */
        const val ALT_LOGIN_URL = "https://jwxt.bistu.edu.cn"

        /** 登录成功后跳转到的域名（教务系统） */
        const val TARGET_HOST = "jwxt.bistu.edu.cn"

        /** 结果 extra：登录后拿到的 Cookie */
        const val EXTRA_COOKIE = "cookie"

        /**
         * 反检测脚本。
         *
         * 学校登录页会检测 WebView 环境，检测到后 Vue 组件不渲染（白屏）。
         * 这个脚本抹掉常见特征：
         *  - window.chrome.webview（Android WebView 特有）
         *  - navigator.webdriver（自动化标志）
         *  - 伪造 plugins / languages（无头环境常见缺失）
         */
        private val ANTI_DETECT_JS = """
            (function() {
                try {
                    // 1. 抹掉 Android WebView 标记
                    if (window.chrome && window.chrome.webview) {
                        delete window.chrome.webview;
                    }
                    // 2. 抹掉 webdriver 标记
                    try {
                        Object.defineProperty(navigator, 'webdriver', {
                            get: function() { return false; },
                            configurable: true
                        });
                    } catch(e) {}
                    // 3. 伪造 plugins（真浏览器有，无头环境没有）
                    try {
                        Object.defineProperty(navigator, 'plugins', {
                            get: function() { return [1,2,3,4,5]; },
                            configurable: true
                        });
                    } catch(e) {}
                    // 4. 伪造 languages
                    try {
                        Object.defineProperty(navigator, 'languages', {
                            get: function() { return ['zh-CN','zh','en']; },
                            configurable: true
                        });
                    } catch(e) {}
                    // 5. 抹掉 standalone 模式标记
                    try {
                        Object.defineProperty(navigator, 'standalone', {
                            get: function() { return false; },
                            configurable: true
                        });
                    } catch(e) {}
                    // 6. 保证有 window.chrome 对象（部分检测会查它）
                    if (!window.chrome) {
                        window.chrome = { runtime: {} };
                    }
                } catch(e) {
                    console.log('anti-detect error: ' + e);
                }
            })();
        """.trimIndent()

        /**
         * 修复白屏的 CSS 补丁。
         *
         * 问题：登录页把表单放在 iframe 里，而 iframe 高度是 100%，
         * 父容器 #app 的高度却是 0 —— 结果 iframe 高度 0，内容不可见。
         *
         * 修复：强制给 html/body/#app/iframe 设置 100% 高度。
         */
        private val FIX_HEIGHT_CSS = """
            (function() {
                try {
                    var style = document.createElement('style');
                    style.id = 'dsh-height-fix';
                    style.textContent = [
                        'html, body { height: 100% !important; min-height: 100% !important; overflow: auto !important; }',
                        '#app { height: 100% !important; min-height: 100vh !important; }',
                        '.app-box, .main-warp, .main { height: 100% !important; min-height: 100vh !important; }',
                        '#iframeObj { height: 100% !important; min-height: calc(100vh - 0px) !important; display: block !important; }',
                        'iframe { min-height: 100vh !important; display: block !important; }'
                    ].join('\n');
                    (document.head || document.documentElement).appendChild(style);
                    console.log('[DSH] 高度修复已注入');

                    // 如果 #app 还是 0 高度，直接设内联样式
                    var app = document.getElementById('app');
                    if (app) {
                        app.style.height = '100vh';
                        app.style.minHeight = '100vh';
                        console.log('[DSH] #app 高度已强制设置');
                    }

                    // 修正 iframe
                    var iframe = document.getElementById('iframeObj');
                    if (iframe) {
                        iframe.style.height = '100vh';
                        iframe.style.minHeight = '100vh';
                        iframe.style.display = 'block';
                        console.log('[DSH] iframe 高度已强制设置');
                    }
                } catch(e) {
                    console.log('[DSH] 高度修复失败: ' + e);
                }
            })();
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 允许 WebView 保存 Cookie（重要：登录态靠它）
        CookieManager.getInstance().setAcceptCookie(true)

        // 启用 WebView 远程调试：电脑 Chrome 打开 chrome://inspect 即可调试
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

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

                                            // ── 关键：注入高度修复 CSS ──
                                            // 学校登录页把表单放在 iframe 里，
                                            // 但 #app 父容器高度为 0，导致 iframe 不可见（白屏）
                                            view.evaluateJavascript(FIX_HEIGHT_CSS) { r ->
                                                Log.d(TAG, "高度修复结果: $r")
                                            }

                                            // ── 诊断：检查 DOM 是否渲染出内容 ──
                                            view.evaluateJavascript(
                                                """
                                                (function() {
                                                    var b = document.body;
                                                    var info = {
                                                        url: location.href,
                                                        title: document.title,
                                                        bodyLen: b ? b.innerHTML.length : -1,
                                                        bodyText: b ? b.innerText.substring(0, 100) : '',
                                                        childCount: b ? b.children.length : -1,
                                                        readyState: document.readyState
                                                    };
                                                    // 输出前 500 字符的 HTML，看真实结构
                                                    window.__dsh_dom = b ? b.innerHTML.substring(0, 500) : '';
                                                    // 检查每个子元素的可见性
                                                    var vis = [];
                                                    if (b) {
                                                        for (var i = 0; i < b.children.length; i++) {
                                                            var el = b.children[i];
                                                            var cs = window.getComputedStyle(el);
                                                            vis.push({
                                                                tag: el.tagName,
                                                                id: el.id || '',
                                                                cls: (el.className || '').substring(0, 40),
                                                                display: cs.display,
                                                                visibility: cs.visibility,
                                                                opacity: cs.opacity,
                                                                w: el.offsetWidth,
                                                                h: el.offsetHeight
                                                            });
                                                        }
                                                    }
                                                    window.__dsh_vis = JSON.stringify(vis);
                                                    return JSON.stringify(info);
                                                })()
                                                """.trimIndent()
                                            ) { result ->
                                                Log.d(TAG, "DOM 诊断: $result")

                                                val bodyLen = Regex("\"bodyLen\":(\\d+)")
                                                    .find(result)?.groupValues?.get(1)?.toIntOrNull()
                                                if (bodyLen != null && bodyLen < 100) {
                                                    statusText = "⚠️ 白屏：DOM 内容为空（${bodyLen} 字节）"
                                                }

                                                // 再取一次 HTML 结构和可见性
                                                view.evaluateJavascript("window.__dsh_dom") { dom ->
                                                    Log.d(TAG, "DOM 前 500 字符: $dom")
                                                }
                                                view.evaluateJavascript("window.__dsh_vis") { vis ->
                                                    Log.d(TAG, "子元素可见性: $vis")
                                                }
                                            }

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

                                        // 捕获 SSL 错误
                                        override fun onReceivedSslError(
                                            view: WebView,
                                            handler: android.webkit.SslErrorHandler,
                                            error: android.net.http.SslError
                                        ) {
                                            Log.e(TAG, "SSL 错误: ${error.url} - ${error.primaryError}")
                                            handler.proceed()   // 测试用：忽略证书错误
                                        }

                                        // 捕获 HTTP 错误
                                        override fun onReceivedHttpError(
                                            view: WebView,
                                            request: WebResourceRequest,
                                            errorResponse: android.webkit.WebResourceResponse
                                        ) {
                                            Log.e(
                                                TAG,
                                                "HTTP 错误: ${request.url} -> ${errorResponse.statusCode}"
                                            )
                                        }
                                    }

                                    // 捕获 Console 消息（关键：能看到 JS 报错）
                                    webChromeClient = object : android.webkit.WebChromeClient() {
                                        override fun onConsoleMessage(
                                            consoleMessage: android.webkit.ConsoleMessage
                                        ): Boolean {
                                            Log.d(
                                                TAG,
                                                "Console[${consoleMessage.messageLevel()}]: " +
                                                        "${consoleMessage.message()} " +
                                                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                                            )
                                            return true
                                        }
                                    }

                                    // ── 关键：注入反检测脚本 ──
                                    // 学校页面会检测 WebView 特征，检测到就不渲染 Vue 组件。
                                    // 这里在页面加载前把常见特征抹掉。
                                    evaluateJavascript(ANTI_DETECT_JS, null)

                                    // 从教务系统入口进，让它自己跳转到 CAS 登录页
                                    // （直接加载 SPA 登录页在 WebView 里渲染失败）
                                    loadUrl(ALT_LOGIN_URL)
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
