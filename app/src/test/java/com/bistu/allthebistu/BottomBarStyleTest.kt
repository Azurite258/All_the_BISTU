package com.bistu.allthebistu

import com.bistu.allthebistu.data.prefs.BottomBarStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 底栏风格的默认值与脏数据收敛。
 *
 * 这里守的是两件事：
 *  1. 默认必须是扁平（0.14 之前是光效，改回来了，不能被谁顺手改回去）
 *  2. 洗数据的方向必须朝着默认值收，而不是朝着「历史的那个默认」
 *     —— 否则存储里一个越界值就能让某台机器永远显示玻璃，且没有任何入口能解释
 */
class BottomBarStyleTest {

    @Test
    fun `默认是扁平`() {
        assertEquals(BottomBarStyle.FLAT, BottomBarStyle.DEFAULT)
    }

    @Test
    fun `两档取值互不重叠`() {
        assertEquals(0, BottomBarStyle.FLAT)
        assertEquals(1, BottomBarStyle.GLASS)
    }

    @Test
    fun `合法值原样返回`() {
        assertEquals(BottomBarStyle.FLAT, BottomBarStyle.sanitize(BottomBarStyle.FLAT))
        assertEquals(BottomBarStyle.GLASS, BottomBarStyle.sanitize(BottomBarStyle.GLASS))
    }

    @Test
    fun `脏数据收敛到默认的扁平`() {
        listOf(-1, 2, 99, Int.MIN_VALUE, Int.MAX_VALUE).forEach { dirty ->
            assertEquals("脏值 $dirty 应回到扁平", BottomBarStyle.FLAT, BottomBarStyle.sanitize(dirty))
        }
    }

    @Test
    fun `标签跟着值走`() {
        assertEquals("扁平", BottomBarStyle.label(BottomBarStyle.FLAT))
        assertEquals("光效", BottomBarStyle.label(BottomBarStyle.GLASS))
        assertEquals("扁平", BottomBarStyle.label(99))
    }
}
