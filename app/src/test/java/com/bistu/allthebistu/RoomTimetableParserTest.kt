package com.bistu.allthebistu

import com.bistu.allthebistu.data.classroom.RoomTimetableParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「按教室查课表」响应解析。
 *
 * 外层键名不确定（`datas.<接口名>`），所以解析器递归找 `arrangedList`；
 * 行内字段按实测样本（个人课表同款结构）钉住。
 * 核心要求：**解析不出来就返回空，绝不瞎猜**——宁可说「这间有数据但没课」，
 * 也不能把有课的教室算成空的。
 */
class RoomTimetableParserTest {

    private val room = "XXD-202"

    private val sample = """
    {"code":"0","datas":{"getScheduleDetail":{
      "termName":"2026-2027-1",
      "arrangedList":[
        {"courseName":"管理学原理","dayOfWeek":2,"beginSection":3,"endSection":4,
         "week":"001111111111","placeName":"XXD-202(智)","beginTime":"09:50","endTime":"11:25"},
        {"courseName":"高等数学(1)","dayOfWeek":5,"beginSection":1,"endSection":2,
         "week":"0011111111","placeName":"XXD-202(智)"}
      ]
    }}}
    """.trimIndent()

    @Test
    fun `能解析出占用时段`() {
        val lessons = RoomTimetableParser.parse(sample, room)
        assertEquals(2, lessons.size)
        assertEquals(2, lessons[0].dayOfWeek)
        assertEquals(3..4, lessons[0].beginSection..lessons[0].endSection)
        assertEquals((3..12).toSet(), lessons[0].weeks)
    }

    @Test
    fun `每行都归给被查询的教室`() {
        // placeName 是 "XXD-202(智)"，带后缀；不能拿它当 key，否则和教室清单里的
        // "XXD-202" 对不上，差集永远匹配不到，所有教室都会被算成空
        val lessons = RoomTimetableParser.parse(sample, room)
        assertTrue(lessons.all { it.room == room })
    }

    @Test
    fun `外层键名换了也能找到 arrangedList`() {
        val json = """{"code":"0","datas":{"whatever":{"deep":{"arrangedList":[
            {"dayOfWeek":1,"beginSection":1,"endSection":2,"week":"111111111111"}
        ]}}}}"""
        assertEquals(1, RoomTimetableParser.parse(json, room).size)
    }

    @Test
    fun `这间教室没课就是空列表`() {
        val json = """{"code":"0","datas":{"getScheduleDetail":{"arrangedList":[]}}}"""
        assertTrue(RoomTimetableParser.parse(json, room).isEmpty())
    }

    @Test
    fun `没有 arrangedList 也当没课而不是崩`() {
        assertTrue(RoomTimetableParser.parse("""{"code":"0","datas":{}}""", room).isEmpty())
    }

    @Test
    fun `业务失败要抛异常`() {
        try {
            RoomTimetableParser.parse("""{"code":"1","msg":"会话失效"}""", room)
            throw AssertionError("应该抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("会话失效") == true)
        }
    }

    @Test
    fun `缺节次的行丢掉`() {
        val json = """{"code":"0","datas":{"x":{"arrangedList":[
            {"dayOfWeek":1,"week":"111111111111"},
            {"dayOfWeek":1,"beginSection":3,"endSection":4,"week":"111111111111"}
        ]}}}"""
        assertEquals(1, RoomTimetableParser.parse(json, room).size)
    }

    @Test
    fun `周次解析不出来就丢掉而不是当成每周有课`() {
        val json = """{"code":"0","datas":{"x":{"arrangedList":[
            {"dayOfWeek":1,"beginSection":1,"endSection":2,"week":"莫名其妙"}
        ]}}}"""
        assertTrue(RoomTimetableParser.parse(json, room).isEmpty())
    }

    @Test
    fun `week 为空时退回 weeksAndTeachers 里的周次`() {
        val json = """{"code":"0","datas":{"x":{"arrangedList":[
            {"dayOfWeek":1,"beginSection":1,"endSection":2,"week":"",
             "weeksAndTeachers":"3-12周[讲授]/某某[主讲]"}
        ]}}}"""
        val lessons = RoomTimetableParser.parse(json, room)
        assertEquals((3..12).toSet(), lessons[0].weeks)
    }

    @Test
    fun `星期超出范围丢掉`() {
        val json = """{"code":"0","datas":{"x":{"arrangedList":[
            {"dayOfWeek":8,"beginSection":1,"endSection":2,"week":"111111111111"}
        ]}}}"""
        assertTrue(RoomTimetableParser.parse(json, room).isEmpty())
    }
}
