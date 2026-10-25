package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.parseCampusIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校区解析测试。
 *
 * 对应线上问题：**有些同学能登录，但课表永远是空的**。
 * 原因之一是 `getMyScheduledCampus.do` 的返回没解析出来，
 * 老代码此时会静默退回 `listOf("10")` —— 而 10 是沙河校区，
 * 别的校区的学生于是只查沙河，拿回空课表，还被当成「登录过期」清掉会话。
 */
class CampusParseTest {

    @Test
    fun `parses datas as flat array`() {
        val json = """
            {"code":"0","datas":[
              {"id":"10","name":"沙河校区"},
              {"id":"11","name":"小营校区"}
            ]}
        """.trimIndent()
        assertEquals(listOf("10", "11"), parseCampusIds(json))
    }

    @Test
    fun `parses datas wrapped in rows`() {
        // 正方有些接口是 datas:{rows:[...]} 的包法
        val json = """
            {"code":"0","datas":{"rows":[
              {"XQDM":"10","XQMC":"沙河校区"},
              {"XQDM":"11","XQMC":"小营校区"}
            ]}}
        """.trimIndent()
        assertEquals(listOf("10", "11"), parseCampusIds(json))
    }

    @Test
    fun `numeric id is coerced to string`() {
        val json = """{"code":"0","datas":[{"id":10}]}"""
        assertEquals(listOf("10"), parseCampusIds(json))
    }

    @Test
    fun `duplicated campus codes are collapsed`() {
        val json = """{"code":"0","datas":[{"id":"10"},{"id":"10"}]}"""
        assertEquals(listOf("10"), parseCampusIds(json))
    }

    @Test
    fun `unparsable response returns empty instead of guessing campus 10`() {
        // 这是关键：以前解析失败会返回 listOf("10")，让非沙河的学生永远拉不到课表
        assertTrue(parseCampusIds("""{"code":"0","datas":[]}""").isEmpty())
        assertTrue(parseCampusIds("""{"code":"0"}""").isEmpty())
        assertTrue(parseCampusIds("not a json").isEmpty())
        assertTrue(parseCampusIds("").isEmpty())
    }
}
