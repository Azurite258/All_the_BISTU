package com.bistu.allthebistu

import com.bistu.allthebistu.data.ScheduleParser
import com.bistu.allthebistu.ui.study.semesterCreditOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 未排课课程（notArrangeList）的解析。
 *
 * 教务返回里除了 arrangedList（有星期/节次的课），还有 notArrangeList
 * （安全教育、职业规划这类线上课）和 practiceList（实践环节）。
 * 它们没有上课时间，**不能画进课表**，但学分要算——漏掉就会少算学分。
 */
class NotArrangedParseTest {

    private val json = """
    {
      "code": "0",
      "datas": {
        "getMyScheduleDetail": {
          "subTitles": [],
          "arrangedList": [
            {
              "courseName": "管理学原理", "courseCode": "MGT101", "credit": "3.0",
              "weeksAndTeachers": "3-12周[讲授]/雷丙寅[主讲]",
              "placeName": "XXD-202", "week": "001111111111",
              "dayOfWeek": 1, "beginSection": 3, "endSection": 4,
              "beginTime": "09:50", "endTime": "11:25", "color": "#FFF0CC"
            }
          ],
          "notArrangeList": [
            {
              "courseName": "大学生安全知识教育", "courseCode": "UNIV100", "credit": "1.0",
              "weeksAndTeachers": "1-7周[讲授]/郑珊珊[主讲]",
              "placeName": "", "week": "1111111",
              "dayOfWeek": null, "beginSection": null, "endSection": null
            }
          ]
        }
      }
    }
    """.trimIndent()

    @Test
    fun `未排课课程会进课程列表但标记为未排课`() {
        val data = ScheduleParser.parse(json, "2026-2027-1")

        assertEquals(2, data.courses.size)

        val arranged = data.courses.first { it.code == "MGT101" }
        assertTrue("已排课应标记 isArranged", arranged.isArranged)

        val online = data.courses.first { it.code == "UNIV100" }
        assertFalse("未排课不应标记 isArranged", online.isArranged)
        assertEquals("未排课也要保留学分", "1.0", online.credit)
        assertEquals(setOf(1, 2, 3, 4, 5, 6, 7), online.weeks)
    }

    @Test
    fun `学分统计要把未排课算进去`() {
        val data = ScheduleParser.parse(json, "2026-2027-1")
        assertEquals(4.0, semesterCreditOf(data.courses), 0.001)
    }
}
