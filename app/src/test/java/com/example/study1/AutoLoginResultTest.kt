package com.example.study1

import com.example.study1.ui.login.AutoLoginResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动登录结果类型的行为测试。
 *
 * 注：AuthHolder.autoLogin 依赖 Android Context 与 SharedPreferences，
 * 属于仪器测试范畴；这里只验证结果类型自身的语义，
 * 真实的恢复链路已在模拟器上用注入会话验证。
 */
class AutoLoginResultTest {

    @Test
    fun `success from memory is distinguishable`() {
        val a = AutoLoginResult.Success(reusedFromMemory = true)
        val b = AutoLoginResult.Success(reusedFromMemory = false)
        assertTrue(a.reusedFromMemory)
        assertTrue(!b.reusedFromMemory)
        assertNotEquals(a, b)
    }

    @Test
    fun `need manual carries reason`() {
        val r = AutoLoginResult.NeedManual("登录已过期，请重新登录")
        assertEquals("登录已过期，请重新登录", r.reason)
    }

    @Test
    fun `success and need manual are different types`() {
        val success: AutoLoginResult = AutoLoginResult.Success(true)
        val needManual: AutoLoginResult = AutoLoginResult.NeedManual("x")
        assertTrue(success is AutoLoginResult.Success)
        assertTrue(needManual is AutoLoginResult.NeedManual)
        assertTrue(success !is AutoLoginResult.NeedManual)
    }

    @Test
    fun `when expression is exhaustive`() {
        val results = listOf(
            AutoLoginResult.Success(true),
            AutoLoginResult.NeedManual("no session")
        )
        val labels = results.map { r ->
            when (r) {
                is AutoLoginResult.Success -> "ok"
                is AutoLoginResult.NeedManual -> "manual"
            }
        }
        assertEquals(listOf("ok", "manual"), labels)
    }
}
