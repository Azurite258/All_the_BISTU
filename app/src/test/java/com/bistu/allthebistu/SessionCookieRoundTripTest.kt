package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.StoredCookie
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话 Cookie（无过期时间）的落盘 / 还原。
 *
 * 排查「登录后杀后台再进，课表变示例数据」时专门验过这条链路：
 * 若会话 Cookie 还原失败，冷启动后就会「看起来已登录、接口全失败」。
 * 结论是这段是好的，问题在别处，但这条不变量值得一直守住。
 */
class SessionCookieRoundTripTest {

    @Test
    fun `会话 Cookie 往返后仍然存在`() {
        val original = Cookie.Builder()
            .name("JSESSIONID")
            .value("ABC123")
            .domain("jwxt.bistu.edu.cn")
            .path("/")
            .build()

        println("original.persistent=${original.persistent} expiresAt=${original.expiresAt}")

        val stored = StoredCookie.from(original)
        println("stored.expiresAt=${stored.expiresAt} isExpired=${stored.isExpired()}")

        val roundTrip = stored.toCookie()
        println("roundTrip=$roundTrip")

        assertNotNull("会话 Cookie 还原失败，冷启动后会丢失", roundTrip)
        assertEquals("ABC123", roundTrip!!.value)
        assertTrue(roundTrip.matches("https://jwxt.bistu.edu.cn/module/schedule".toHttpUrlOrNull()!!))
    }
}
