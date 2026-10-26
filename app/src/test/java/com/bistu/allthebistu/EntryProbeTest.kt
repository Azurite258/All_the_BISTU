package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.isEntryAlive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「入口探活」判定的回归测试。
 *
 * 背景（真机日志 2026-10-26）：
 * ```
 * 11:12:06  autoLogin: 恢复 15 个 Cookie，isLoggedIn=true
 * 11:12:07  ensureSession: 入口落点=jwxt.bistu.edu.cn, HTTP 401
 * 11:12:07  POST cxmrxnxq.do → HTTP 401
 * 11:12:07  ScheduleRepository: 会话已失效 → clearAll → wipeAll
 * ```
 * 教务会话闲置失效时，正方回的是 **401**，而 401 的最终落点 host
 * 仍然是 `jwxt.bistu.edu.cn`。旧判定只比较 host，于是把 401 认成
 * 「会话可用」，跳过了 TGC 换票；紧接着业务接口再 401，
 * 异常一路抛成 SessionExpiredException，仓储层据此清掉登录态、
 * 课表缓存、头像 —— 用户看到的就是「用着用着就自己退出登录了」。
 */
class EntryProbeTest {

    private val JWXT = "jwxt.bistu.edu.cn"

    @Test
    fun `401 即使落在教务域也必须判为会话不可用`() {
        // 真机现场：401 + jwxt 落点。旧实现返回 true，直接导致掉登录
        assertFalse(isEntryAlive(401, JWXT, """{"msg":"未登录"}"""))
    }

    @Test
    fun `403 同样不可用`() {
        assertFalse(isEntryAlive(403, JWXT, ""))
    }

    @Test
    fun `200 且落在教务域是可用会话`() {
        assertTrue(isEntryAlive(200, JWXT, "<!DOCTYPE html><html><body>kbapp</body></html>"))
    }

    @Test
    fun `入口是单页应用 HTML 时不能误判成登录页`() {
        // 入口页面本来就是 HTML；用「是不是 HTML」判登录页会让每次冷启动
        // 都白换一次票（这条防的是修复本身引入的回归）
        val body = "<html><head><script src=\"/jwapp/js/index.js\"></script></head></html>"
        assertTrue(isEntryAlive(200, JWXT, body))
    }

    @Test
    fun `200 但被换成统一身份认证登录页时不可用`() {
        assertFalse(isEntryAlive(200, JWXT, "<html><body>统一身份认证</body></html>"))
    }

    @Test
    fun `落点被重定向到 sso 时不可用`() {
        assertFalse(isEntryAlive(200, "sso.bistu.edu.cn", "<html></html>"))
    }

    @Test
    fun `200 空响应体仍算可用`() {
        // 空体不代表失效；真失效会有 401 或登录页标记
        assertTrue(isEntryAlive(200, JWXT, ""))
    }
}
