package com.bistu.allthebistu

import com.bistu.allthebistu.data.privacy.UserDataWipers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 退出登录时的扩展清除器注册表。
 *
 * 退出登录要清的东西会越来越多（校园卡、学籍、缴费…），
 * 这里守住两个行为：
 *  1. 注册了就会被执行
 *  2. 某个模块抛异常，不能连累其他模块——宁可清掉一部分，也不能全留着
 */
class UserDataWipersTest {

    @Before
    fun setUp() = UserDataWipers.resetForTest()

    @After
    fun tearDown() = UserDataWipers.resetForTest()

    @Test
    fun `注册的清除器都会被执行`() = runBlocking {
        var cards = false
        var profile = false
        UserDataWipers.register("校园卡") { cards = true }
        UserDataWipers.register("学籍信息") { profile = true }

        val result = UserDataWipers.runAll()

        assertTrue(cards)
        assertTrue(profile)
        assertEquals(listOf("校园卡", "学籍信息"), result.cleared)
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `某个模块失败不影响其他模块`() = runBlocking {
        var done = false
        UserDataWipers.register("会炸的模块") { error("数据库被锁") }
        UserDataWipers.register("校园卡") { done = true }

        val result = UserDataWipers.runAll()

        assertTrue("后面的模块必须继续执行", done)
        assertEquals(listOf("校园卡"), result.cleared)
        assertEquals("数据库被锁", result.failed["会炸的模块"])
    }

    @Test
    fun `同名注册会覆盖旧的`() = runBlocking {
        var oldCalled = false
        var newCalled = false
        UserDataWipers.register("课表") { oldCalled = true }
        UserDataWipers.register("课表") { newCalled = true }

        UserDataWipers.runAll()

        assertTrue(newCalled)
        assertTrue("旧实现不应再被调用", !oldCalled)
        assertEquals(listOf("课表"), UserDataWipers.names())
    }

    @Test
    fun `没有注册任何模块时返回空结果`() = runBlocking {
        val result = UserDataWipers.runAll()
        assertTrue(result.cleared.isEmpty())
        assertTrue(result.failed.isEmpty())
    }
}
