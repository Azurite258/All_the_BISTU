package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.shouldExpireSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「什么情况下才允许把用户踢下线」的回归测试。
 *
 * 背景：v0.14 只要教务请求失败就清 Cookie。
 * 结果是「过一会重进 → 无法刷新 → 再进一次就变成未登录」，
 * 而头像、昵称、课表缓存全留在本地，界面成了
 * 「已经退出登录，头像还是本人的照片」。
 *
 * 现在的判定线：**统一身份认证（TGC）还在，就不许登出。**
 * 教务连不上、校外被拦、教务维护，都是这台设备当下进不去，
 * 不是账号失效；清掉会话只会让用户反复重登。
 */
class SessionExpiryTest {

    @Test
    fun `TGC 还在时续期失败不能登出`() {
        // 最典型的校外场景：SSO 凭据好好的，教务那边进不去
        assertFalse(shouldExpireSession(tgc = "TGT-12345", hasJwxtSession = false))
    }

    @Test
    fun `TGC 和教务会话都没了才算真过期`() {
        assertTrue(shouldExpireSession(tgc = null, hasJwxtSession = false))
        assertTrue(shouldExpireSession(tgc = "", hasJwxtSession = false))
        assertTrue(shouldExpireSession(tgc = "   ", hasJwxtSession = false))
    }

    @Test
    fun `教务会话还在时不能因为 TGC 缺失就登出`() {
        // 罕见：SSO 域 Cookie 被清掉，但 jwxt 的 JSESSIONID 仍然可用。
        // 这时候还能正常拉课表，登出纯属自找麻烦。
        assertFalse(shouldExpireSession(tgc = null, hasJwxtSession = true))
    }

    @Test
    fun `两个都在时正常保留登录态`() {
        assertFalse(shouldExpireSession(tgc = "TGT-1", hasJwxtSession = true))
    }
}
