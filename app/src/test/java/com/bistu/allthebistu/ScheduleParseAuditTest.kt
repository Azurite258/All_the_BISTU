package com.bistu.allthebistu

import com.bistu.allthebistu.data.ScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「课表少一门课」专项测试。
 *
 * 现场：教务网页上周一有三门课（工科化学 1-2 节、程序设计基础 3-5 节、
 * 国家安全教育 8-9 节），App 的周视图里只有两门，3-5 节那门整块消失。
 *
 * 这些用例把「消失」的几种可能路径都钉住：
 *  ① 跨三节（beginSection=3, endSection=5）能不能正常展开
 *  ② week 位图的不同长度 / 写法会不会解出空集
 *  ③ 多段周次（同一门课不同周次不同教室）会不会被整段丢弃
 *
 * 如果这些都过，说明解析器没问题，问题一定在服务端返回的真实字段格式上，
 * 那就必须拿到原始 JSON（已经在 fetchSchedule 里落盘成 schedule_raw_*.txt）。
 */
class ScheduleParseAuditTest {

    private fun json(arranged: String, notArranged: String = "[]"): String = """
        {
          "code": "0",
          "datas": {
            "getMyScheduleDetail": {
              "subTitles": [],
              "termName": "2026-2027学年 第一学期",
              "arrangedList": [$arranged],
              "notArrangeList": $notArranged
            }
          }
        }
    """.trimIndent()

    /** 第 8 周有课：12 位位图，第 8 位为 '1' */
    private val week8 = "000000010000"

    @Test
    fun `跨三节的课程不会被丢掉`() {
        val raw = json(
            """
            {"courseName":"工科化学","courseCode":"C1","week":"$week8","dayOfWeek":1,
             "beginSection":1,"endSection":2,"beginTime":"08:00","endTime":"09:35",
             "placeName":"GXB-406","weeksAndTeachers":"1-16周[讲授]/张老师[主讲]"},
            {"courseName":"程序设计基础(C语言)C","courseCode":"C2","week":"$week8","dayOfWeek":1,
             "beginSection":3,"endSection":5,"beginTime":"09:50","endTime":"12:15",
             "placeName":"XXB-602","weeksAndTeachers":"1-16周[讲授]/李老师[主讲]"},
            {"courseName":"国家安全教育","courseCode":"C3","week":"$week8","dayOfWeek":1,
             "beginSection":8,"endSection":9,"beginTime":"15:20","endTime":"16:55",
             "placeName":"WLA-505","weeksAndTeachers":"1-16周[讲授]/王老师[主讲]"}
            """
        )

        val data = ScheduleParser.parse(raw, "2026-2027-1")

        assertEquals("三门课一条都不能少", 3, data.courses.size)
        val c = data.courses.first { it.name.contains("程序设计") }
        assertEquals(3, c.beginSection)
        assertEquals(5, c.endSection)
        assertEquals(3, c.sectionCount)
        assertTrue("第 8 周应该能看到这门课", c.isInWeek(8))
        assertTrue("节次合法，应该能画进网格", c.isArranged)
    }

    @Test
    fun `位图长度变化不影响周次解析`() {
        // 服务端不同版本给过 12 位 / 16 位 / 18 位，第 8 周都必须解成 8
        listOf("000000010000", "0000000100000000", "000000010000000000").forEach { bitmap ->
            val weeks = ScheduleParser.parseWeekBitmap(bitmap)
            assertEquals("位图 $bitmap 应解析出第 8 周", setOf(8), weeks)
        }
    }

    @Test
    fun `同一门课多段周次不同教室会拆成多条而不是丢弃`() {
        val raw = json(
            """
            {"courseName":"程序设计基础(C语言)C","courseCode":"C2","week":"",
             "dayOfWeek":1,"beginSection":3,"endSection":5,"beginTime":"09:50","endTime":"12:15",
             "placeName":"XXB-602; XXC-201",
             "weeksAndTeachers":"1-8周[讲授]/李老师[主讲]; 9-16周[实验]/李老师[主讲]"}
            """
        )

        val data = ScheduleParser.parse(raw, "2026-2027-1")

        assertEquals("应该拆成两段（理论 + 实验）", 2, data.courses.size)
        assertTrue("第 8 周要有课", data.courses.any { it.isInWeek(8) })
        assertTrue("第 12 周要有课", data.courses.any { it.isInWeek(12) })
    }

    @Test
    fun `week 字段给成文本时要按文本解析而不是变成错误周次`() {
        // 正方个别实例把 week 给成 "3-5" 这种文本而不是位图。
        // 旧实现按位图解析："3-5" 里一个 '1' 都没有 → 空集 →
        // 这门课任何一周都不显示，现象就是「课表少了一门课」。
        assertEquals(setOf(3, 4, 5), ScheduleParser.parseWeekBitmap("3-5"))
        assertEquals(setOf(1, 3, 5), ScheduleParser.parseWeekBitmap("1,3,5"))
        assertEquals((3..16).toSet(), ScheduleParser.parseWeekBitmap("3-16周"))
    }

    @Test
    fun `标准位图仍然按位图解析`() {
        // 纯 0/1 的字符串必须走位图分支，不能误判成文本
        assertEquals(setOf(8), ScheduleParser.parseWeekBitmap("000000010000"))
        assertEquals((3..12).toSet(), ScheduleParser.parseWeekBitmap("0011111111110"))
    }
}
