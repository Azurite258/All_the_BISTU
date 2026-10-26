package com.bistu.allthebistu.data.net

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 「这次失败要不要再来一遍」的统一策略。
 *
 * 为什么需要：老代码一次请求失败就结束，任何瞬时抖动都直接变成界面上的
 * 「刷新失败」。校内 Wi-Fi 漫游、教学楼信号切换、教务前置 WAF 偶发 502、
 * 以及教务站点**只发叶子证书**导致的随机 `SSLHandshakeException`，
 * 全都是「再试一次就好」的情况，却都被当成终态。
 *
 * 边界同样重要 —— 不是所有失败都值得重试：
 *  - 401/403：会话问题，重试只会再 401 一次，该走换票或让用户重登
 *  - 业务 code 非 0、JSON 解析失败：重试结果一样，纯浪费用户时间
 *  - 校外 302：路径问题，不是抖动
 *  - 协程取消：必须立刻退出，否则界面切换后还在偷偷联网
 */
internal object RetryPolicy {

    /** 最多试几次（含第一次）。3 次足够覆盖抖动，又不至于让用户干等太久 */
    const val MAX_ATTEMPTS = 3

    /**
     * 总时间预算。
     *
     * 这条比「次数」更关键：网络彻底不通时，连接超时本身就要 20 秒，
     * 若无脑重试 3 次，用户要盯着转圈一分多钟。所以按时间兜底 ——
     * 快失败（握手失败、502、连接被拒）能拿到余下的重试机会，
     * 慢失败（超时）基本只试一次，与改动前的耗时持平，不会更差。
     */
    const val TOTAL_BUDGET_MS = 30_000L

    /** 第 [attempt] 次失败后等多久再试（attempt 从 1 开始） */
    fun delayFor(attempt: Int): Long = when (attempt) {
        1 -> 400L
        2 -> 1_200L
        else -> 0L
    }

    /**
     * @param error     这次抛出的异常
     * @param attempt   当前是第几次尝试（1 表示第一次）
     * @param elapsedMs 从第一次开始到现在已经花掉的时间
     */
    fun shouldRetry(error: Throwable, attempt: Int, elapsedMs: Long): Boolean {
        if (attempt >= MAX_ATTEMPTS) return false
        if (elapsedMs + delayFor(attempt) >= TOTAL_BUDGET_MS) return false
        return isTransient(error)
    }

    /**
     * 界面层要不要再补一次重试。
     *
     * 和 [shouldRetry] 的区别：这里判的是「整次加载」而不是单条请求。
     * 只补「快速失败」（几秒内就挂）的抖动 —— 那种往往是刚切回 Wi-Fi、
     * 刚解锁、路由表还没更新；慢失败则说明网络确实不通，
     * 再等一轮只会让用户多盯两倍时间的转圈。
     */
    fun shouldRetryUi(error: Throwable, elapsedMs: Long): Boolean =
        isTransient(error) && elapsedMs < 5_000L

    /** 这个异常是不是「可能自己会好」的类型 */
    fun isTransient(error: Throwable): Boolean = when (error) {
        // 服务端临时故障 / 限流：同一请求稍后大概率成功
        is TransientHttpException -> true

        // 连接层抖动：DNS 抽风、连接被拒、连接被重置、TLS 握手失败
        is SocketTimeoutException -> true
        is ConnectException -> true
        is SocketException -> true
        is UnknownHostException -> true
        is SSLException -> true

        // 响应体被截断 / 中途断开
        is EOFException -> true

        // OkHttp 的 callTimeout 抛的是 InterruptedIOException，
        // 但 Suspend 取消也走这个类型，必须排在后面单独排除
        is InterruptedIOException -> !error.isCancellation()

        else -> false
    }

    private fun Throwable.isCancellation(): Boolean =
        this is kotlinx.coroutines.CancellationException ||
                cause is kotlinx.coroutines.CancellationException
}

/**
 * 服务端明确返回了「待会儿再来」的状态码（502/503/504 等）。
 *
 * 和 [AuthException] 分开，才能让重试策略放行它、同时继续把
 * 业务失败（接口返回 code 非 0）当成终态，避免无意义的重试。
 */
class TransientHttpException(val code: Int, message: String) : Exception(message)

/** 这些状态码重试有意义；4xx 中的参数/权限问题一律不重试 */
internal val RETRYABLE_HTTP_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
