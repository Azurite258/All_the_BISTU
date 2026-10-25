package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.AuthException
import com.bistu.allthebistu.data.auth.SessionExpiredException
import com.bistu.allthebistu.ui.schedule.LoadErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 「同步失败」的文案翻译。
 *
 * 背景：之前界面上只有一句干巴巴的「同步失败」，
 * 用户没法区分是登录过期、没连校园网、还是教务系统挂了。
 */
class LoadErrorTextTest {

    @Test
    fun `会话失效提示重新登录`() {
        val e = SessionExpiredException("接口返回登录页，会话已失效（HTTP 200）")
        assertEquals("登录已失效，请重新登录", LoadErrorText.describe(e))
        assertTrue(LoadErrorText.needsRelogin(e))
    }

    @Test
    fun `业务失败不能被当成掉登录`() {
        // 以前按 message.contains("登录") 判断，
        // 「没有获取到课程数据（可能登录已过期）」这类文案会被误判成会话失效，
        // 结果一进课表就被清 Cookie、踢回登录页。
        val noCourse = AuthException("没有获取到课程数据（可能登录已过期）")
        assertFalse(LoadErrorText.needsRelogin(noCourse))
        assertFalse(LoadErrorText.describe(noCourse).contains("重新登录"))

        val apiFail = AuthException("课表接口返回失败（code=500），请稍后重试")
        assertFalse(LoadErrorText.needsRelogin(apiFail))
    }

    @Test
    fun `域名解析失败算网络问题`() {
        assertTrue(LoadErrorText.describe(UnknownHostException("jwxt.bistu.edu.cn")).contains("网络"))
        assertFalse(LoadErrorText.needsRelogin(UnknownHostException("x")))
    }

    @Test
    fun `超时和连接失败分开描述`() {
        val timeout = LoadErrorText.describe(SocketTimeoutException("timeout"))
        val refused = LoadErrorText.describe(ConnectException("refused"))
        assertTrue(timeout.contains("超时"))
        assertTrue(refused.contains("校园网"))
    }

    @Test
    fun `非 JSON 内容提示教务系统异常`() {
        val e = IllegalStateException("接口返回了非 JSON 内容：<html>...")
        assertTrue(LoadErrorText.describe(e).contains("维护"))
    }

    @Test
    fun `未知异常保留原始信息便于反馈`() {
        val e = RuntimeException("something broke")
        val text = LoadErrorText.describe(e)
        assertTrue(text.contains("something broke"))
        assertTrue(LoadErrorText.detail(e).contains("RuntimeException"))
    }

    @Test
    fun `空异常不会崩`() {
        assertEquals("未知错误", LoadErrorText.describe(null))
        assertEquals("没有更多信息", LoadErrorText.detail(null))
    }
}
