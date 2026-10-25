package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.LoginResult
import com.bistu.allthebistu.data.auth.OffCampusException
import com.bistu.allthebistu.data.auth.SessionExpiredException
import com.bistu.allthebistu.ui.schedule.LoadErrorText
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「校外进不去教务」这条路径的回归测试。
 *
 * 背景：v0.14 里校外请求会被教务 302 踢回统一身份认证，
 * 但代码把这件事和「会话过期」混在一起，用户看到的是「登录已失效」，
 * 反复重登也没用 —— 其实账号是好的，只是没连校园网。
 *
 * 更麻烦的是 casLogin() 的判定写成了
 * `isLoggedIn() || getAllCookiesHeader().contains("JSESSIONID")`，
 * SSO 域自己就带 JSESSIONID，于是永远返回 true，
 * 日志一直显示「CAS 成功」，实际教务会话根本没建起来。
 */
class OffCampusTest {

    @Test
    fun `校外被拦不能当成掉登录`() {
        val e = OffCampusException(
            "教务系统把请求转到了 sso.bistu.edu.cn，说明这台设备现在进不去教务系统。"
        )

        // 关键：不能清登录态。清了用户重登也一样进不去，只会更困惑
        assertFalse(LoadErrorText.needsRelogin(e))
        assertTrue(LoadErrorText.isNetworkBlocked(e))

        val text = LoadErrorText.describe(e)
        assertTrue(text.contains("校园网"))
        assertFalse(text.contains("重新登录"))
    }

    @Test
    fun `真会话失效仍然要求重新登录`() {
        // 反过来也要锁住：不能因为修校外问题，把「真过期」也放过
        val e = SessionExpiredException("教务接口返回 401，登录态已失效")
        assertTrue(LoadErrorText.needsRelogin(e))
        assertFalse(LoadErrorText.isNetworkBlocked(e))
        assertEquals("登录已失效，请重新登录", LoadErrorText.describe(e))
    }

    @Test
    fun `SSO 成功但教务没连上，文案要说清是网络`() {
        val r = LoginResult(
            code = 666666,
            message = "success",
            rawJson = JSONObject(),
            jwxtReady = false
        )

        // 账号密码是对的，所以 isSuccess 仍为 true —— 不该拦着用户进 App
        assertTrue(r.isSuccess)
        assertTrue(r.isLoggedInButNoJwxt)
        assertTrue(r.friendlyMessage.contains("校园网"))
        assertTrue(r.friendlyMessage.contains("教务"))
    }

    @Test
    fun `教务也通了就是普通登录成功`() {
        val r = LoginResult(
            code = 666666,
            message = "success",
            rawJson = JSONObject(),
            jwxtReady = true
        )

        assertTrue(r.isSuccess)
        assertFalse(r.isLoggedInButNoJwxt)
        assertEquals("登录成功", r.friendlyMessage)
    }
}
