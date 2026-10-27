package com.bistu.allthebistu

import com.bistu.allthebistu.data.classroom.RoomScheduleParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课程时间地点表」的解析。
 *
 * 这份数据是空教室的唯一原料（教务没有「给我空教室」的接口），
 * 字段名是拼音缩写且各校不统一，所以这里钉两件事：
 * 1. 真实响应壳（`datas.rwsjdd.rows`）能取出来
 * 2. 认不出的行**丢掉并计数**，绝不猜——猜错就是把有课的教室说成空的
 */
class RoomScheduleParserTest {

    private val envelope = """
        {"code":"0","msg":"","datas":{"rwsjdd":{"total":2,"rows":[
          {"JASMC":"A-101","JXLMC":"文理楼A","XQ":"3","KSDJ":"3","JSDJ":"4","SKZC":"1-16周","KCMC":"高等数学"},
          {"JASMC":"A-102","JXLMC":"文理楼A","XQ":"3","KSDJ":"5","JSDJ":"6","SKZC":"1-8,10-16周","KCMC":"大学英语"}
        ]}}}
    """.trimIndent()

    // ── 外壳 ─────────────────────────────────────────────

    @Test
    fun `从 datas_rwsjdd_rows 取数据`() {
        val payload = RoomScheduleParser.parse(envelope)
        assertEquals(2, payload.rows.size)
        assertEquals(2, payload.total)
    }

    @Test
    fun `裸 rows 也能取`() {
        val payload = RoomScheduleParser.parse("""{"code":"0","rows":[{"JASMC":"B-201"}]}""")
        assertEquals(1, payload.rows.size)
    }

    @Test
    fun `业务失败要抛出来而不是当空数据`() {
        // 会话过期时教务会回非 0 的 code，静默当成「0 间空教室」会误导用户
        try {
            RoomScheduleParser.parse("""{"code":"1","msg":"登录已失效","datas":null}""")
            throw AssertionError("应该抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("登录已失效") == true)
        }
    }

    // ── 行 → 占用记录 ─────────────────────────────────────

    @Test
    fun `一整行能解析成占用记录`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        assertEquals(2, parsed.lessons.size)
        assertEquals(0, parsed.unmapped)

        val first = parsed.lessons[0]
        assertEquals("A-101", first.room)
        assertEquals("文理楼A", first.building)
        assertEquals(3, first.dayOfWeek)
        assertEquals(3, first.beginSection)
        assertEquals(4, first.endSection)
        assertTrue(5 in first.weeks)
        assertTrue(17 !in first.weeks)
    }

    @Test
    fun `跳段周次不会被解析成连续周`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        val second = parsed.lessons[1]
        assertTrue(9 !in second.weeks)
        assertTrue(10 in second.weeks)
    }

    @Test
    fun `没有教室名的行丢掉并计数`() {
        val json = """{"code":"0","datas":{"rwsjdd":{"rows":[
            {"XQ":"3","KSDJ":"1","JSDJ":"2","SKZC":"1-16"},
            {"JASMC":"C-301","XQ":"3","KSDJ":"1","JSDJ":"2","SKZC":"1-16"}
        ]}}}"""
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(json))
        assertEquals(1, parsed.lessons.size)
        assertEquals(1, parsed.unmapped)
    }

    @Test
    fun `周次解析不出来的行也要丢掉`() {
        // 没周次就不知道哪几周占着，算成「每周都占」会漏教室，算成「不占」会骗人
        val json = """{"code":"0","datas":{"rwsjdd":{"rows":[
            {"JASMC":"C-301","XQ":"3","KSDJ":"1","JSDJ":"2"}
        ]}}}"""
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(json))
        assertEquals(0, parsed.lessons.size)
        assertEquals(1, parsed.unmapped)
    }

    @Test
    fun `首行字段名会被记下来`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        assertTrue(parsed.sampleKeys.contains("JASMC"))
    }

    @Test
    fun `教学楼和教室清单去重`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        assertEquals(listOf("文理楼A"), parsed.buildings)
        assertEquals(2, parsed.rooms.size)
    }

    // ── 星期 ─────────────────────────────────────────────

    @Test
    fun `数字星期`() {
        assertEquals(3, RoomScheduleParser.parseDay("3"))
    }

    @Test
    fun `周三这种写法`() {
        assertEquals(3, RoomScheduleParser.parseDay("周三"))
    }

    @Test
    fun `星期天和周日都算 7`() {
        assertEquals(7, RoomScheduleParser.parseDay("星期日"))
        assertEquals(7, RoomScheduleParser.parseDay("周天"))
    }

    @Test
    fun `星期超出范围不认`() {
        assertNull(RoomScheduleParser.parseDay("9"))
        assertNull(RoomScheduleParser.parseDay(""))
        assertNull(RoomScheduleParser.parseDay(null))
    }

    // ── 节次 ─────────────────────────────────────────────

    @Test
    fun `开始结束节字段`() {
        val row = JSONObject("""{"KSDJ":"3","JSDJ":"4"}""")
        assertEquals(3 to 4, RoomScheduleParser.parseSections(row))
    }

    @Test
    fun `字符串区间写法`() {
        val row = JSONObject("""{"JC":"第3-4节"}""")
        assertEquals(3 to 4, RoomScheduleParser.parseSections(row))
    }

    @Test
    fun `单节写法`() {
        val row = JSONObject("""{"JC":"5"}""")
        assertEquals(5 to 5, RoomScheduleParser.parseSections(row))
    }

    @Test
    fun `只有大节序号时按两小节换算`() {
        val row = JSONObject("""{"DJ":"2"}""")
        assertEquals(3 to 4, RoomScheduleParser.parseSections(row))
    }

    @Test
    fun `节次字段都没有就不认`() {
        assertNull(RoomScheduleParser.parseSections(JSONObject("""{"JASMC":"A-101"}""")))
    }

    @Test
    fun `节次顺序反了不认`() {
        val row = JSONObject("""{"KSDJ":"5","JSDJ":"3"}""")
        assertNull(RoomScheduleParser.parseSections(row))
    }

    // ── 楼栋反推 ─────────────────────────────────────────

    @Test
    fun `文理楼教室号能反推出楼栋`() {
        assertEquals("文理楼A", RoomScheduleParser.buildingOf("WLA-605"))
        assertEquals("文理楼C", RoomScheduleParser.buildingOf("WLC-305(语)"))
    }

    @Test
    fun `信息楼教室号能反推出楼栋`() {
        assertEquals("信息楼D", RoomScheduleParser.buildingOf("XXD-202(智)"))
    }

    @Test
    fun `工学楼和工程训练中心`() {
        assertEquals("工学楼A", RoomScheduleParser.buildingOf("GXA-301(智)"))
        assertEquals("工程训练中心", RoomScheduleParser.buildingOf("SJA-201"))
    }

    @Test
    fun `认不出的教室号返回空串而不是瞎猜`() {
        assertEquals("", RoomScheduleParser.buildingOf("学生发展中心光影剧场"))
        assertEquals("", RoomScheduleParser.buildingOf(""))
    }

    @Test
    fun `课表里有楼栋字段时优先用字段而不是反推`() {
        val json = """{"code":"0","datas":{"rwsjdd":{"rows":[
            {"JASMC":"WLA-101","JXLMC":"文理楼A座","XQ":"3","KSDJ":"1","JSDJ":"2","SKZC":"1-16"}
        ]}}}"""
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(json))
        assertEquals("文理楼A座", parsed.lessons[0].building)
    }

    @Test
    fun `没有楼栋字段时用教室号反推`() {
        val json = """{"code":"0","datas":{"rwsjdd":{"rows":[
            {"JASMC":"WLA-101","XQ":"3","KSDJ":"1","JSDJ":"2","SKZC":"1-16"}
        ]}}}"""
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(json))
        assertEquals("文理楼A", parsed.lessons[0].building)
    }

    @Test
    fun `教室课表那种字段名也能认`() {
        // jslb（教室课表）用的是 SKXQ/SKZC/JC/JASMC 这一套
        val json = """{"code":"0","datas":{"rows":[
            {"JASMC":"WLA-101","JXLDM":"文理楼A","SKXQ":"2","JC":"5-6","SKZC":"001111111111","SFPK":"1"}
        ]}}"""
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(json))
        assertEquals(1, parsed.lessons.size)
        val l = parsed.lessons[0]
        assertEquals(2, l.dayOfWeek)
        assertEquals(5 to 6, l.beginSection to l.endSection)
        assertEquals((3..12).toSet(), l.weeks)
    }

    // ── 端到端：解析完直接能查空教室 ──────────────────────

    @Test
    fun `解析结果能直接喂给空教室反查`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        val empty = com.bistu.allthebistu.data.classroom.EmptyRoomFinder.findEmpty(
            lessons = parsed.lessons,
            allRooms = parsed.rooms,
            week = 5, dayOfWeek = 3, fromSection = 3, toSection = 4
        )
        assertEquals(listOf("A-102"), empty)
    }

    @Test
    fun `没课的时段两间都空着`() {
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        val empty = com.bistu.allthebistu.data.classroom.EmptyRoomFinder.findEmpty(
            lessons = parsed.lessons,
            allRooms = parsed.rooms,
            week = 5, dayOfWeek = 3, fromSection = 9, toSection = 10
        )
        assertEquals(listOf("A-101", "A-102"), empty)
    }

    @Test
    fun `非空结果不该被断言吞掉`() {
        // 防止上面的用例因为选择器写错而「恰好通过」
        val parsed = RoomScheduleParser.toLessons(RoomScheduleParser.parse(envelope))
        assertNotNull(parsed.lessons.firstOrNull())
    }
}
