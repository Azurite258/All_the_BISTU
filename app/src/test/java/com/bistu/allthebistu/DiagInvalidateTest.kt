package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.cookieDomainCovers
import com.bistu.allthebistu.data.auth.isSessionCookieName
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「诊断：模拟教务会话过期」点了提示「当前没有教务会话」的回归测试。
 *
 * 原因：旧实现用 `Cookie.matches(url)` 找教务会话，而 matches **连 path 一起校验**。
 * jwxt 的会话 Cookie 是 `domain=jwxt.bistu.edu.cn, path=/jwapp/`，
 * 传给 matches 的却是根路径 `https://jwxt.bistu.edu.cn/`，path 对不上 → 永远 false。
 * 于是这个诊断入口一次都没生效过；同一个错误也让 hasJwxtSession() 一直报 false。
 */
class DiagInvalidateTest {

    private val jwxtCookie = Cookie.Builder()
        .name("GS_SESSIONID")
        .value("4a7fd24c6c0d4c221d743f54a4306f77")
        .domain("jwxt.bistu.edu.cn")
        .path("/jwapp/")
        .build()

    @Test
    fun `Cookie_matches 会因 path 不匹配而漏掉教务会话`() {
        // 记录旧实现为什么失效：不是名字的问题，是 path
        assertFalse(jwxtCookie.matches("https://jwxt.bistu.edu.cn/".toHttpUrl()))
    }

    @Test
    fun `只比 domain 就能认出教务会话`() {
        assertTrue(cookieDomainCovers(jwxtCookie.domain, "jwxt.bistu.edu.cn"))
    }

    @Test
    fun `sso 域的 JSESSIONID 不算教务会话`() {
        assertTrue(isSessionCookieName("JSESSIONID"))
        assertFalse(cookieDomainCovers("sso.bistu.edu.cn", "jwxt.bistu.edu.cn"))
    }

    @Test
    fun `父域 bistu_edu_cn 覆盖教务域`() {
        assertTrue(cookieDomainCovers(".bistu.edu.cn", "jwxt.bistu.edu.cn"))
    }

    @Test
    fun `空域名和无关域都不算`() {
        assertFalse(cookieDomainCovers("", "jwxt.bistu.edu.cn"))
        assertFalse(cookieDomainCovers("info.bistu.edu.cn", "jwxt.bistu.edu.cn"))
        // 别把 eviljwxt.bistu.edu.cn 当成 jwxt
        assertFalse(cookieDomainCovers("jwxt.bistu.edu.cn", "eviljwxt.bistu.edu.cn"))
    }

    @Test
    fun `只有会话相关的 Cookie 名才算`() {
        assertTrue(isSessionCookieName("GS_SESSIONID"))
        assertFalse(isSessionCookieName("route"))
        assertFalse(isSessionCookieName("_WEU"))
    }
}
