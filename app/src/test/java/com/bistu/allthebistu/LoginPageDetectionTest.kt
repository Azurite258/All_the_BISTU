package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.isLikelyLoginPage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话失效的识别。
 *
 * 用户报的现象：登录后杀掉后台再进，主页显示「示例数据 · 同步失败」，
 * 但界面上又显示已登录。根因是教务系统会话过期时返回 200 + 登录页 HTML，
 * 而旧代码把它当普通解析异常，既不清登录态也不提示重新登录。
 */
class LoginPageDetectionTest {

    @Test
    fun `正常 JSON 不会被误判`() {
        assertFalse(isLikelyLoginPage("""{"code":"0","datas":{}}"""))
        assertFalse(isLikelyLoginPage("""  [{"a":1}]"""))
    }

    @Test
    fun `HTML 登录页算会话失效`() {
        assertTrue(isLikelyLoginPage("<html><body>请登录</body></html>"))
        assertTrue(isLikelyLoginPage("\n\n  <!DOCTYPE html><html>..."))
        assertTrue(isLikelyLoginPage("<form action=\"/cas/login\">"))
    }

    @Test
    fun `统一身份认证页面算会话失效`() {
        assertTrue(isLikelyLoginPage("北信科统一身份认证平台"))
        assertTrue(isLikelyLoginPage("Redirecting to CASTGC ..."))
        assertTrue(isLikelyLoginPage("https://sso.bistu.edu.cn/login?service=https%3A%2F%2Fjwxt"))
    }

    @Test
    fun `空响应不算登录页`() {
        assertFalse(isLikelyLoginPage(""))
        assertFalse(isLikelyLoginPage("   "))
    }

    @Test
    fun `JSON 里出现 login 字段也不算登录页`() {
        // 以 { 开头的响应永远是接口数据，不能被内部字段干扰
        assertFalse(isLikelyLoginPage("""{"code":"0","msg":"please login first"}"""))
    }
}
