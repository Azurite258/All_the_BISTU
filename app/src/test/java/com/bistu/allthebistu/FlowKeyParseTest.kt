package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.parseFlowKeyFromSetCookies
import com.bistu.allthebistu.data.auth.parseFlowKeyValue
import com.bistu.allthebistu.data.auth.resolveFlowKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder

/**
 * flowKey 解析测试。
 *
 * 对应线上问题：其他人登录报 **180040 Flowkey未找到**。
 * 该错误只有一个来源 —— 登录请求里的 flowKey 为空或不属于当前 SSO 会话，
 * 所以「能不能从响应头里正确取出 flowKey」直接决定登录成败。
 *
 * 这里锁死三种情况：能取到、取不到返回空串（绝不抛异常）、真实样例可解析。
 */
class FlowKeyParseTest {

    /** 按学校真实格式编码：外层 URL 编码一次的 JSON */
    private fun encode(json: String): String = URLEncoder.encode(json, "UTF-8")

    private fun realCookieValue(flowKey: String = "flow.a0bfe032e0e32a1deef6ceeaf0a5737b") = encode(
        """{"code":600901,"msg":"没找到TGC","data":{""" +
                """"flowKey":"$flowKey",""" +
                """"service":"https%3A%2F%2Fuc.bistu.edu.cn%2Fapi%2Flogin",""" +
                """"serviceName":"统一身份认证个人中心","mfa":null},""" +
                """"timestamp":1792917887829}"""
    )

    // ── parseFlowKeyValue ──

    @Test
    fun `parses flowKey from real cookie payload`() {
        val key = parseFlowKeyValue(realCookieValue())
        assertEquals("flow.a0bfe032e0e32a1deef6ceeaf0a5737b", key)
    }

    @Test
    fun `blank value returns empty`() {
        assertEquals("", parseFlowKeyValue(""))
        assertEquals("", parseFlowKeyValue("   "))
    }

    @Test
    fun `malformed percent encoding returns empty instead of throwing`() {
        // 老版本这里会抛 IllegalArgumentException，把一次登录重试直接变成崩溃
        assertEquals("", parseFlowKeyValue("flowKey=%ZZ"))
    }

    @Test
    fun `non json value returns empty`() {
        assertEquals("", parseFlowKeyValue(encode("not a json")))
    }

    @Test
    fun `json without flowKey returns empty`() {
        assertEquals("", parseFlowKeyValue(encode("""{"code":600901,"data":{}}""")))
        assertEquals("", parseFlowKeyValue(encode("""{"code":600901}""")))
    }

    // ── parseFlowKeyFromSetCookies ──

    @Test
    fun `finds cookie info among other set-cookie headers`() {
        val headers = listOf(
            "JSESSIONID=98783F8C6EB1166C422B42A7B40A4840; Path=/; HttpOnly",
            "JUMP_REASON=COOKIE_NOT_TGC; Domain=sso.bistu.edu.cn; Path=/",
            "COOKIE_INFO=${realCookieValue()}; Domain=sso.bistu.edu.cn; Path=/",
            "_75d92=924a5dcab0aaf4c8; Path=/; Secure; HttpOnly"
        )
        assertEquals(
            "flow.a0bfe032e0e32a1deef6ceeaf0a5737b",
            parseFlowKeyFromSetCookies(headers)
        )
    }

    @Test
    fun `missing cookie info returns empty`() {
        val headers = listOf(
            "JSESSIONID=ABC; Path=/; HttpOnly",
            "JUMP_REASON=COOKIE_NOT_TGC; Domain=sso.bistu.edu.cn; Path=/"
        )
        assertEquals("", parseFlowKeyFromSetCookies(headers))
        assertEquals("", parseFlowKeyFromSetCookies(emptyList()))
    }

    @Test
    fun `empty cookie info value returns empty`() {
        // SSO 清理登录态时会下发 COOKIE_INFO= 空值，不能当成有效 flowKey
        assertEquals("", parseFlowKeyFromSetCookies(listOf("COOKIE_INFO=; Max-Age=0; Path=/")))
    }

    // ── resolveFlowKey：这条决定「用哪个 flowKey 去登录」──
    //
    // 线上现象：A 同学能登，B 同学一登就 180040。
    // 原因是缓存了上一轮的 flowKey，服务端换发后仍拿旧的去登录。

    @Test
    fun `jar value wins over stale cache`() {
        val jar = realCookieValue("flow.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        assertEquals(
            "flow.bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            resolveFlowKey(jar, "flow.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        )
    }

    @Test
    fun `missing jar entry falls back to cache`() {
        assertEquals("flow.cached", resolveFlowKey(null, "flow.cached"))
        assertEquals("", resolveFlowKey(null, ""))
    }

    @Test
    fun `cleared jar value must not fall back to cache`() {
        // 服务端主动作废会话：此时退回缓存＝必然 180040，只能返回空让上层重建
        assertEquals("", resolveFlowKey("", "flow.stale"))
        assertEquals("", resolveFlowKey(encode("""{"code":600901,"data":{}}"""), "flow.stale"))
    }
}
