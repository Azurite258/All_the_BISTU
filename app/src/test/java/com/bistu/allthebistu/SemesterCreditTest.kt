package com.bistu.allthebistu

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.ui.study.distinctCourseCount
import com.bistu.allthebistu.ui.study.semesterCreditOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 学期概览卡里的学分/门数统计。
 *
 * 课表里同一门课会被拆成多个课块（例如「基础英语」3-5 周在 A 楼、
 * 6-13 周在 B 楼），直接 sum 会把学分算两遍。
 */
class SemesterCreditTest {

    private fun course(
        name: String,
        code: String,
        credit: String,
        room: String = "教室1"
    ) = Course(
        name = name,
        code = code,
        credit = credit,
        teacher = "教师A",
        room = room,
        campus = "",
        weeks = setOf(1),
        dayOfWeek = 1,
        beginSection = 1,
        endSection = 2,
        beginTime = "08:00",
        endTime = "09:35",
        colorHex = "#FFFFFF"
    )

    @Test
    fun `同一门课拆成两个课块时学分只算一次`() {
        val courses = listOf(
            course("基础英语", "EN001", "3.0", "A101"),
            course("基础英语", "EN001", "3.0", "B202"),
            course("高等数学(1)", "MA001", "5.5")
        )
        assertEquals(8.5, semesterCreditOf(courses), 0.001)
        assertEquals(2, distinctCourseCount(courses))
    }

    @Test
    fun `没有课程码时按课名去重`() {
        val courses = listOf(
            course("形势与政策", "", "1"),
            course("形势与政策", "", "1")
        )
        assertEquals(1.0, semesterCreditOf(courses), 0.001)
        assertEquals(1, distinctCourseCount(courses))
    }

    @Test
    fun `学分字段为空或异常时按零处理`() {
        val courses = listOf(
            course("A", "A1", ""),
            course("B", "B1", "abc"),
            course("C", "C1", "2")
        )
        assertEquals(2.0, semesterCreditOf(courses), 0.001)
    }

    @Test
    fun `空课表返回零`() {
        assertEquals(0.0, semesterCreditOf(emptyList()), 0.001)
        assertEquals(0, distinctCourseCount(emptyList()))
    }
}
