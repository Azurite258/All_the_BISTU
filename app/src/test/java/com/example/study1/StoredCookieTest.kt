package com.example.study1

import com.example.study1.data.auth.StoredCookie
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cookie 序列化 / 反序列化往返测试。
 *
 * 这是 Cookie 持久化的核心正确性保证：
 * 落盘的数据必须能原样还原成 OkHttp Cookie，且域名、路径、过期时间、
 * secure/httpOnly/hostOnly 等属性不能丢失——否则还原后 Cookie 不会匹配请求。
 */
class StoredCookieTest {

    private fun sampleCookie(
        name: String = "JSESSIONID",
        value: String = "ABC123",
        domain: String = "jwxt.bistu.edu.cn",
        path: String = "/",
        expiresAt: Long = Long.MIN_VALUE,
        secure: Boolean = true,
        httpOnly: Boolean = true,
        hostOnly: Boolean = false
    ): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .apply { if (hostOnly) hostOnlyDomain(domain) else domain(domain) }
        .path(path)
        .apply { if (secure) secure() }
        .apply { if (httpOnly) httpOnly() }
        .apply { if (expiresAt != Long.MIN_VALUE) expiresAt(expiresAt) }
        .build()

    @Test
    fun `round trip preserves all attributes`() {
        val original = sampleCookie()
        val stored = StoredCookie.from(original)

        assertEquals("JSESSIONID", stored.name)
        assertEquals("ABC123", stored.value)
        assertEquals("jwxt.bistu.edu.cn", stored.domain)
        assertEquals("/", stored.path)
        assertTrue(stored.secure)
        assertTrue(stored.httpOnly)

        val restored = stored.toCookie()
        assertNotNull(restored)
        assertEquals(original.name, restored!!.name)
        assertEquals(original.value, restored.value)
        assertEquals(original.domain, restored.domain)
        assertEquals(original.path, restored.path)
        assertEquals(original.secure, restored.secure)
        assertEquals(original.httpOnly, restored.httpOnly)
        assertEquals(original.hostOnly, restored.hostOnly)
    }

    @Test
    fun `round trip through json`() {
        val original = sampleCookie(name = "TGC", value = "TGT-999", path = "/cas")
        val json = StoredCookie.from(original).toJson()
        val parsed = StoredCookie.fromJson(json)

        assertNotNull(parsed)
        assertEquals("TGC", parsed!!.name)
        assertEquals("TGT-999", parsed.value)
        assertEquals("/cas", parsed.path)
    }

    @Test
    fun `hostOnly cookie round trip`() {
        val original = sampleCookie(hostOnly = true)
        val restored = StoredCookie.from(original).toCookie()
        assertNotNull(restored)
        assertTrue(restored!!.hostOnly)
    }

    @Test
    fun `session cookie never expires`() {
        val stored = StoredCookie.from(sampleCookie(expiresAt = Long.MIN_VALUE))
        assertFalse(stored.isExpired())
    }

    @Test
    fun `persistent cookie expiry respected`() {
        val past = System.currentTimeMillis() - 60_000
        val stored = StoredCookie.from(sampleCookie(expiresAt = past))
        assertTrue(stored.isExpired())

        val future = System.currentTimeMillis() + 60_000
        val alive = StoredCookie.from(sampleCookie(expiresAt = future))
        assertFalse(alive.isExpired())
    }

    @Test
    fun `restored cookie matches original url`() {
        val url = "https://jwxt.bistu.edu.cn/jwapp/sys/kbapp/api/x.do".toHttpUrl()
        val original = sampleCookie()
        val restored = StoredCookie.from(original).toCookie()

        assertNotNull(restored)
        assertTrue("还原后的 Cookie 应能匹配原始域名", restored!!.matches(url))
    }

    @Test
    fun `malformed json returns null`() {
        val bad = org.json.JSONObject("""{"path":"/"}""")  // 缺 name/value/domain
        assertNull(StoredCookie.fromJson(bad))
    }
}
