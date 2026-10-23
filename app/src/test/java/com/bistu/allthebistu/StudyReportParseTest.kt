package com.bistu.allthebistu

import com.bistu.allthebistu.data.StudyReportParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 学业完成查询（queryXyzhbx.do）的解析。
 *
 * 结构照抄真实响应：顶层 datas.queryXyzhbx.fanbx，
 * 里面既有总学分，也有 children 组成的一级分类。
 */
class StudyReportParseTest {

    private val json = """
    {
      "code": "0",
      "datas": {
        "queryXyzhbx": {
          "checkType": "04",
          "fanbx": {
            "educationalProgramCode": "2026070101",
            "creditsRequired": 132.0,
            "creditsEarned": 0.0,
            "creditsOutOfProgram": 0.0,
            "creditsTaken": 0.0,
            "creditsSelection": 29.25,
            "children": [
              { "courseCategoryName": "通识教育", "creditsRequired": 62.0, "creditsEarned": 0.0, "creditsSelection": 24.25 },
              { "courseCategoryName": "专业教育", "creditsRequired": 70.0, "creditsEarned": 0.0, "creditsSelection": 5.0 },
              { "courseCategoryName": "专业拓展", "creditsRequired": 15.0, "creditsEarned": 0.0, "creditsSelection": 0.0 }
            ]
          }
        }
      }
    }
    """.trimIndent()

    @Test
    fun `解析总学分与已选`() {
        val report = StudyReportParser.parse(json)
        assertEquals(132.0, report.creditsRequired, 0.001)
        assertEquals(0.0, report.creditsEarned, 0.001)
        assertEquals(29.25, report.creditsSelection, 0.001)
        assertEquals("2026070101", report.programCode)
    }

    @Test
    fun `解析三个一级分类`() {
        val report = StudyReportParser.parse(json)
        assertEquals(listOf("通识教育", "专业教育", "专业拓展"), report.categories.map { it.name })
        assertEquals(62.0, report.categories[0].required, 0.001)
        assertEquals(24.25, report.categories[0].selection, 0.001)
    }

    @Test
    fun `毕业进度按已修算`() {
        val report = StudyReportParser.parse(json)
        assertEquals(0f, report.earnedProgress, 0.001f)
    }

    @Test
    fun `接口报错时抛异常`() {
        val bad = """{"code":"-1","msg":"未登录"}"""
        assertThrows(IllegalArgumentException::class.java) {
            StudyReportParser.parse(bad)
        }
    }

    @Test
    fun `缺少 fanbx 时抛异常`() {
        val bad = """{"code":"0","datas":{"queryXyzhbx":{}}}"""
        assertThrows(IllegalStateException::class.java) {
            StudyReportParser.parse(bad)
        }
    }
}
