package com.bistu.allthebistu.ui.schedule

import com.bistu.allthebistu.data.auth.AuthException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把加载失败的技术异常翻译成用户能懂的一句话。
 *
 * 之前这里只有一个「同步失败」的标签，用户完全看不出是网络问题、
 * 校园网限制，还是登录态失效——排查全靠抓日志。
 */
object LoadErrorText {

    /** 一句话概括 */
    fun describe(e: Throwable?): String {
        if (e == null) return "未知错误"
        val msg = e.message.orEmpty()

        return when {
            e is AuthException && (msg.contains("登录") || msg.contains("会话")) ->
                "登录已失效，请重新登录"

            e is UnknownHostException ->
                "连不上教务系统域名，请检查网络"

            e is SocketTimeoutException ->
                "教务系统响应超时"

            e is ConnectException ->
                "无法连接教务系统（校外访问通常要连校园网或 VPN）"

            e is SSLException ->
                "HTTPS 握手失败（可能是网络代理或校园网证书问题）"

            msg.contains("非 JSON") ->
                "接口返回了非预期内容，可能是教务系统维护中"

            else -> "同步失败：${msg.take(80).ifBlank { e.javaClass.simpleName }}"
        }
    }

    /** 详细原因（弹窗里展开给用户看，便于反馈问题） */
    fun detail(e: Throwable?): String {
        if (e == null) return "没有更多信息"
        return buildString {
            append(e.javaClass.simpleName)
            e.message?.let { append(": ").append(it.take(300)) }
        }
    }

    /** 是否是「需要重新登录」这类问题 */
    fun needsRelogin(e: Throwable?): Boolean =
        e is AuthException && (e.message.orEmpty().contains("登录") ||
                e.message.orEmpty().contains("会话"))
}
