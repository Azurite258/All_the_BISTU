package com.bistu.allthebistu

import com.bistu.allthebistu.data.library.LibrarySeatApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 图书馆座位余量的响应解析。
 *
 * 这个接口（`/ic-web/seatDevice/resvStatus`）是全馆座位唯一的公开数据源，
 * 学校要是改了返回结构，界面就会开始显示「--」。这些用例把**实际抓下来的**
 * 成功样本钉住，另外把几种失败形态也钉住——失败要显示错误、不能把 0 当余量。
 */
class LibrarySeatApiTest {

    private val at = 1761550000000L

    @Test
    fun `实测响应能解析出空座和已占`() {
        // 2026-10-27 从公网实测拿到的真实响应
        val json = """{"code":0,"message":"查询成功","data":{"available":1939,"used":340},"count":0,"vals":null}"""

        val snap = LibrarySeatApi.parse(json, at)

        assertNull(snap.error)
        assertEquals(1939, snap.available)
        assertEquals(340, snap.used)
        assertEquals(at, snap.atMillis)
    }

    @Test
    fun `总数是空座加已占`() {
        val snap = LibrarySeatApi.parse(
            """{"code":0,"message":"查询成功","data":{"available":1939,"used":340}}""", at
        )
        assertEquals(2279, snap.total)
    }

    @Test
    fun `占用率按已占除以总数`() {
        val snap = LibrarySeatApi.parse(
            """{"code":0,"message":"查询成功","data":{"available":750,"used":250}}""", at
        )
        assertEquals(0.25f, snap.occupancy, 0.0001f)
    }

    @Test
    fun `总数是零的时候占用率不能炸成 NaN`() {
        val snap = LibrarySeatApi.parse(
            """{"code":0,"message":"查询成功","data":{"available":0,"used":0}}""", at
        )
        assertNull(snap.error)
        assertEquals(0f, snap.occupancy, 0.0001f)
    }

    @Test
    fun `未登录不当作余量为零`() {
        // 这套系统的统一「未登录」返回；这个接口本来不该出现，
        // 真出现了说明鉴权改了，要显示错误而不是 0 个空座
        val snap = LibrarySeatApi.parse(
            """{"code":300,"message":"用户未登录，请重新登录","data":null,"count":0,"vals":null}""", at
        )
        assertNotNull(snap.error)
        assertEquals(0, snap.total)
    }

    @Test
    fun `其它业务错误码带出原始 message`() {
        val snap = LibrarySeatApi.parse(
            """{"code":500,"message":"系统繁忙，请稍后重试","data":null}""", at
        )
        assertEquals("系统繁忙，请稍后重试", snap.error)
    }

    @Test
    fun `缺字段不当成零`() {
        val snap = LibrarySeatApi.parse(
            """{"code":0,"message":"查询成功","data":{"available":120}}""", at
        )
        assertNotNull(snap.error)
    }

    @Test
    fun `没有 data 也算失败`() {
        val snap = LibrarySeatApi.parse("""{"code":0,"message":"查询成功","data":null}""", at)
        assertNotNull(snap.error)
    }

    @Test
    fun `返回不是 JSON 时不崩`() {
        val snap = LibrarySeatApi.parse("<html><body>error page!</body></html>", at)
        assertNotNull(snap.error)
        assertEquals(0, snap.total)
    }
}
