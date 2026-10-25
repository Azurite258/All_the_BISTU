package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.CasResult
import com.bistu.allthebistu.data.auth.resolveCasLanding
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「什么情况下才允许把用户踢下线」的回归测试。
 *
 * 背景：v0.14 只要教务请求失败就清 Cookie。结果是
 * 「过一会重进 → 无法刷新 → 再进一次就变成未登录」，
 * 而头像、昵称、课表缓存全留在本地，界面成了
 * 「已经退出登录，头像还是本人的照片」。
 *
 * 后来改成「有 TGC 就不登出」，又走向另一个极端：
 * TGC 是**会话 Cookie**，客户端永远不过期，于是服务端早已作废、
 * App 却一直显示「教务系统会话没能建立」，用户永远等不到「请重新登录」。
 *
 * 现在的判定只认换票落点（见 [resolveCasLanding]）：
 *  - 落在教务且探活通过 → 正常
 *  - 落在统一身份认证首页 → TGC 真没了，才允许清登录态
 *  - 其它落点（校外 / WAF / 维护 / 超时）→ 登录态是好的，不许清
 */
class SessionExpiryTest {

    @Test
    fun `落在教务且探活通过是正常会话`() {
        assertEquals(CasResult.Ok, resolveCasLanding("jwxt.bistu.edu.cn", probeOk = true))
    }

    @Test
    fun `落在教务但探活失败按网络问题处理`() {
        // 拿到票却被教务拒掉，属于教务侧异常：不清登录态，提示重试即可
        assertEquals(
            CasResult.Unreachable,
            resolveCasLanding("jwxt.bistu.edu.cn", probeOk = false)
        )
    }

    @Test
    fun `被踢回统一身份认证首页才算真过期`() {
        // 实测：无 TGC 时 sso/login?service=... 302 到 sso 首页
        assertEquals(CasResult.NoTicket, resolveCasLanding("sso.bistu.edu.cn", probeOk = false))
    }

    @Test
    fun `落在别处一律按网络问题处理`() {
        // 校外 WAF / VPN 门户 / 维护页，都不是「账号失效」
        assertEquals(
            CasResult.Unreachable,
            resolveCasLanding("webvpn.bistu.edu.cn", probeOk = false)
        )
        assertEquals(CasResult.Unreachable, resolveCasLanding("uc.bistu.edu.cn", probeOk = false))
    }
}
