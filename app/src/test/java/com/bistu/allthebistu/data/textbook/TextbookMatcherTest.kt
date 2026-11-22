package com.bistu.allthebistu.data.textbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教材文件名 → 课程名 的匹配规则。
 *
 * 这些用例都来自真实会遇到的教材文件名（带版本号、书名号、扫描版后缀），
 * 改动 [TextbookMatcher] 时先看这里会不会红。
 */
class TextbookMatcherTest {

    private val courses = listOf(
        "高等数学A", "线性代数", "大学物理", "大学英语", "思想道德与法治",
        "管理学原理", "现代音乐赏析", "体育"
    )

    @Test
    fun `带版本号和上册的文件名能匹配`() {
        assertEquals(
            "高等数学A",
            TextbookMatcher.bestCourse("高等数学A（第七版）上册.pdf", courses)
        )
    }

    @Test
    fun `书名号和扫描版噪声不影响匹配`() {
        assertEquals(
            "线性代数",
            TextbookMatcher.bestCourse("《线性代数》同济六版扫描版.pdf", courses)
        )
    }

    @Test
    fun `课程名整串出现在文件名里即可`() {
        assertEquals(
            "大学物理",
            TextbookMatcher.bestCourse("大学物理 电磁学部分 电子版.pdf", courses)
        )
    }

    @Test
    fun `课程名带尾巴字母数字时退一步匹配`() {
        // 文件名只有「高等数学」，课程名是「高等数学A」
        assertEquals(
            "高等数学A",
            TextbookMatcher.bestCourse("高等数学 习题册.pdf", courses)
        )
    }

    @Test
    fun `课程名带括号数字时退一步匹配`() {
        // 教务系统的真实写法：高等数学(2)，教材文件只写「高等数学」
        val real = listOf("高等数学(2)", "大学体育(1)", "基础英语")
        assertEquals(
            "高等数学(2)",
            TextbookMatcher.bestCourse("高等数学A（第七版）上册.pdf", real)
        )
        assertEquals(
            "大学体育(1)",
            TextbookMatcher.bestCourse("大学体育 篮球分册.pdf", real)
        )
    }

    @Test
    fun `多个课程都能命中时取最长的那个`() {
        val list = listOf("物理", "大学物理")
        assertEquals("大学物理", TextbookMatcher.bestCourse("大学物理实验.pdf", list))
    }

    @Test
    fun `匹配不上时返回空串`() {
        assertEquals("", TextbookMatcher.bestCourse("英语四级词汇.pdf", courses))
    }

    @Test
    fun `空文件名不会崩`() {
        assertEquals("", TextbookMatcher.bestCourse("", courses))
        assertEquals("", TextbookMatcher.bestCourse(".pdf", courses))
    }

    @Test
    fun `大小写和空格不影响匹配`() {
        assertTrue(
            TextbookMatcher.bestCourse("linear algebra  习题.pdf", listOf("Linear Algebra")).isNotBlank()
        )
    }

    @Test
    fun `归一化只留汉字字母数字`() {
        assertEquals("高等数学a第七版", TextbookMatcher.normalize("高等数学A（第七版）"))
    }

    @Test
    fun `格式化大小`() {
        assertEquals("未知大小", Textbook.formatSize(0))
        assertEquals("1 KB", Textbook.formatSize(200))
        assertEquals("42.3 MB", Textbook.formatSize((42.3 * 1024 * 1024).toLong()))
    }
}
