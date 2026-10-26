package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.AuthException
import com.bistu.allthebistu.data.auth.OffCampusException
import com.bistu.allthebistu.data.auth.SessionExpiredException
import com.bistu.allthebistu.data.net.RETRYABLE_HTTP_CODES
import com.bistu.allthebistu.data.net.RetryPolicy
import com.bistu.allthebistu.data.net.TransientHttpException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * 网络重试策略的回归测试。
 *
 * 加这层重试的直接动机：0.14 只有「一次成功或一次失败」，
 * 校内 Wi-Fi 漫游、WAF 偶发 502、教务只发叶子证书导致的随机握手失败，
 * 任意一次抖动都会变成界面上的「刷新失败」，用户只能自己下拉再试。
 *
 * 但重试的边界必须比「重试」本身更谨慎：
 * 把 401 或业务失败也重试，只会让掉线判定和 prompt 更迟钝。
 */
class RetryPolicyTest {

    @Test
    fun `抖动类失败会重试`() {
        assertTrue(RetryPolicy.isTransient(SocketTimeoutException("read timed out")))
        assertTrue(RetryPolicy.isTransient(ConnectException("Connection refused")))
        assertTrue(RetryPolicy.isTransient(SocketException("Connection reset")))
        assertTrue(RetryPolicy.isTransient(UnknownHostException("jwxt.bistu.edu.cn")))
        assertTrue(RetryPolicy.isTransient(EOFException("unexpected end of stream")))
        // 教务站点只发叶子证书，Android 侧随机 Trust anchor not found 就是它
        assertTrue(RetryPolicy.isTransient(SSLHandshakeException("Trust anchor for certification path not found")))
        // OkHttp 的 callTimeout 抛 InterruptedIOException
        assertTrue(RetryPolicy.isTransient(InterruptedIOException("timeout")))
    }

    @Test
    fun `会话失效不重试，交给换票流程`() {
        assertFalse(RetryPolicy.isTransient(SessionExpiredException("教务接口返回 401")))
    }

    @Test
    fun `业务失败不重试`() {
        assertFalse(RetryPolicy.isTransient(AuthException("课表接口返回失败（code=1）")))
    }

    @Test
    fun `校外拦截不重试`() {
        assertFalse(RetryPolicy.isTransient(OffCampusException("被转到 sso")))
    }

    @Test
    fun `协程取消不重试`() {
        assertFalse(RetryPolicy.isTransient(CancellationException("页面已关闭")))
    }

    @Test
    fun `服务端临时故障码会重试`() {
        assertTrue(RetryPolicy.isTransient(TransientHttpException(503, "HTTP 503")))
        assertEquals(setOf(408, 425, 429, 500, 502, 503, 504), RETRYABLE_HTTP_CODES)
    }

    @Test
    fun `次数用尽就不再重试`() {
        val e = SocketTimeoutException("timeout")
        assertTrue(RetryPolicy.shouldRetry(e, attempt = 1, elapsedMs = 0))
        assertTrue(RetryPolicy.shouldRetry(e, attempt = 2, elapsedMs = 500))
        assertFalse(RetryPolicy.shouldRetry(e, attempt = RetryPolicy.MAX_ATTEMPTS, elapsedMs = 500))
    }

    @Test
    fun `超出时间预算不再重试`() {
        // 连接超时本身就吃掉 20 秒，再重试会让用户盯着一分多钟的转圈
        val e = ConnectException("timeout")
        assertFalse(RetryPolicy.shouldRetry(e, attempt = 1, elapsedMs = 29_800))
        assertTrue(RetryPolicy.shouldRetry(e, attempt = 1, elapsedMs = 1_000))
    }

    @Test
    fun `退避时间递增`() {
        assertEquals(400L, RetryPolicy.delayFor(1))
        assertEquals(1_200L, RetryPolicy.delayFor(2))
        assertEquals(0L, RetryPolicy.delayFor(3))
    }

    @Test
    fun `界面层只补快速失败的抖动`() {
        val e = SSLHandshakeException("Trust anchor for certification path not found")
        // 刚切回校园网、刚解锁时的那种秒失败 → 值得再试一次
        assertTrue(RetryPolicy.shouldRetryUi(e, elapsedMs = 300))
        // 网络真的不通（连接超时 20 秒才失败）→ 不再让用户多等一轮
        assertFalse(RetryPolicy.shouldRetryUi(e, elapsedMs = 20_000))
    }

    @Test
    fun `界面层不补会话与业务失败`() {
        assertFalse(RetryPolicy.shouldRetryUi(SessionExpiredException("401"), elapsedMs = 100))
        assertFalse(RetryPolicy.shouldRetryUi(AuthException("code=1"), elapsedMs = 100))
    }
}
