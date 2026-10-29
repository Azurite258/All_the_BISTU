package com.bistu.allthebistu

import com.bistu.allthebistu.data.auth.TestAccount
import com.bistu.allthebistu.data.card.PortalCardApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试账号（00000000 / 00000000）的守卫用例。
 *
 * 这个账号是本地模拟的入口，一旦凭据判断写错，真实登录就会被劫持；
 * 余额显示也依赖 [PortalCardApi.formatMoney] 对 ∞ 的处理。
 */
class TestAccountTest {

    @Test
    fun `只有 8 个 0 的学号和密码能通过`() {
        assertTrue(TestAccount.matches("00000000", "00000000"))
        // 位数不对 / 混了字母 / 有空格都不该放行
        assertFalse(TestAccount.matches("0000000", "00000000"))
        assertFalse(TestAccount.matches("000000000", "00000000"))
        assertFalse(TestAccount.matches("00000000", "0000000"))
        assertFalse(TestAccount.matches("00000000 ", "00000000"))
        assertFalse(TestAccount.matches("202211258027", "00000000"))
        assertFalse(TestAccount.matches("00000000", "00000001"))
    }

    @Test
    fun `余额是正无穷并且显示成无穷符号`() {
        val overview = TestAccount.cardOverview()
        assertEquals(Double.POSITIVE_INFINITY, overview.balance!!, 0.0)
        assertEquals("¥∞", PortalCardApi.formatMoney(overview.balance!!))
        // 流水里的余额也跟着是 ∞，避免「卡里无穷、流水里却有具体数字」的自相矛盾
        assertTrue(overview.records.isNotEmpty())
        overview.records.forEach {
            assertTrue("流水余额也应为 ∞", it.balance.isInfinite())
        }
    }

    @Test
    fun `随机身份的格式正确`() {
        val profile = TestAccount.newProfile()
        assertEquals(12, profile.studentNo.length)
        assertTrue(profile.studentNo.startsWith("2022"))
        assertTrue(profile.studentNo.all { it.isDigit() })
        assertTrue(profile.nickname.length >= 2)
        assertTrue(profile.college.isNotBlank())
        assertTrue(profile.major.isNotBlank())
        assertTrue(profile.className.endsWith("班"))
    }

    @Test
    fun `学业报告是合理数值`() {
        val report = TestAccount.studyReport()
        assertEquals(160.0, report.creditsRequired, 0.0)
        assertTrue("已修学分为正", report.creditsEarned > 0)
        assertTrue("已修不应超过要求", report.creditsEarned <= report.creditsRequired)
        assertTrue("已选学分为正", report.creditsSelection > 0)
        assertTrue("毕业进度在 0~1 之间", report.earnedProgress in 0f..1f)
        assertEquals(5, report.categories.size)
        assertTrue(report.categories.all { it.name.isNotBlank() })
    }
}
