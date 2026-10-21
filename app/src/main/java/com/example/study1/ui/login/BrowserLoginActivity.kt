package com.example.study1.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 备选方案：用系统浏览器（Custom Tabs）登录。
 *
 * 思路：
 *  WebView 会被学校页面检测并拒绝渲染（白屏），
 *  但系统浏览器（Chrome）是真实浏览器环境，不会有这个问题。
 *
 *  用户点按钮 → 跳转系统 Chrome → 在 Chrome 里正常登录
 *  → 登录成功后，Chrome 里存了 Cookie
 *  → 但 App 无法直接读取 Chrome 的 Cookie（沙箱隔离）
 *
 *  所以这条路需要配合「手动复制 Cookie」或「用 deeplink 回传」。
 */
class BrowserLoginActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BrowserLogin"

        /** 教务系统入口（会自动跳转到 CAS） */
        private const val LOGIN_URL = "https://jwxt.bistu.edu.cn"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("备选方案：系统浏览器登录", fontSize = 20.sp)

                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("方案说明", fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "WebView 被学校页面检测到，导致白屏无法登录。\n\n" +
                                    "改用系统 Chrome 浏览器登录，不受检测影响。\n\n" +
                                    "登录成功后，需要手动复制 Cookie 回到 App。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(LOGIN_URL))
                        // 优先用 Chrome
                        intent.setPackage("com.android.chrome")
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Chrome 没装就用默认浏览器
                            intent.setPackage(null)
                            startActivity(intent)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("用系统浏览器打开教务系统")
                }

                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("手动复制 Cookie 的步骤", fontSize = 15.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "1. 在浏览器里正常登录教务系统\n\n" +
                                    "2. 按 F12 → Network → 刷新页面\n\n" +
                                    "3. 找任意一个 jwapp 请求\n\n" +
                                    "4. 复制 Request Headers 里的 Cookie 值\n\n" +
                                    "5. 粘贴到下面的输入框",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "说明：手机上没有 F12，这个方案更适合「先在电脑浏览器登录，" +
                            "复制 Cookie 后发到手机」的调试场景。",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
