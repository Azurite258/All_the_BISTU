package com.bistu.allthebistu

import com.bistu.allthebistu.data.card.PortalCardApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 一卡通流水的解析。
 *
 * 这里守的核心是一条：**余额 = 最新一笔的 KNYE，绝不能按接口返回的先后顺序取**。
 * 文档写的是倒序，但接口实际给过升序页；按位置取就会拿到几周前的余额，
 * 表现是「余额怎么刷都不变，但本月消费是对的」（2026-10-31 用户实机发现）。
 */
class PortalCardParseTest {

    private fun rec(merchant: String, time: String, jyjeCent: Int, knyeCent: Int): String =
        """{"SHMC":"$merchant","JYRQ":"$time","JYJE":$jyjeCent,"KNYE":$knyeCent,
           "ZHZTMC":"正常","YXQ":"2046-08-09"}"""

    private fun wrap(records: List<String>, maxPage: Int = 3): String =
        """{"e":0,"m":"操作成功","d":{"data":[${records.joinToString(",")}],"max_page":$maxPage}}"""

    /** 升序（最老在前）：必须仍然取到最新那笔的余额 15.00 */
    @Test
    fun `升序页取到最新余额`() {
        val json = wrap(
            listOf(
                rec("一食堂", "2026-10-01 12:00:00", 1000, 1000),   // 消费 10 元 → 余 10 元
                rec("一食堂", "2026-10-05 12:00:00", 100, 900),     // 消费 1 元  → 余 9 元
                rec("微信充值", "2026-10-09 08:00:00", 600, 1500),  // 充值 6 元  → 余 15 元
            )
        )
        val (records, pages) = PortalCardApi.parseRecords(json)

        assertEquals(3, pages)
        assertEquals("2026-10-09 08:00:00", records.first().time)
        assertEquals(15.00, records.first().balance, 0.001)
    }

    /** 倒序（文档说的那种）：结果必须一模一样 */
    @Test
    fun `倒序页取到最新余额`() {
        val json = wrap(
            listOf(
                rec("微信充值", "2026-10-09 08:00:00", 600, 1500),
                rec("一食堂", "2026-10-05 12:00:00", 100, 900),
                rec("一食堂", "2026-10-01 12:00:00", 1000, 1000),
            )
        )
        val (records, _) = PortalCardApi.parseRecords(json)

        assertEquals("2026-10-09 08:00:00", records.first().time)
        assertEquals(15.00, records.first().balance, 0.001)
    }

    /** 收支方向靠「本条余额 − 更早一条余额」，升序时也不能反 */
    @Test
    fun `升序页收支方向正确`() {
        val json = wrap(
            listOf(
                rec("一食堂", "2026-10-01 12:00:00", 1000, 1000),
                rec("一食堂", "2026-10-05 12:00:00", 100, 900),
                rec("微信充值", "2026-10-09 08:00:00", 600, 1500),
            )
        )
        val (records, _) = PortalCardApi.parseRecords(json)

        // 最新一笔是充值（余额从 9 → 15）
        assertTrue("最新一笔应判为收入，实际 ${records[0].amount}", records[0].amount > 0)
        assertEquals(6.00, records[0].amount, 0.001)
        // 中间一笔是消费（10 → 9）
        assertEquals(-1.00, records[1].amount, 0.001)
        // 最老一笔没有参照，按支出处理
        assertTrue(records[2].amount < 0)
    }

    /** 金额单位是分，别忘 /100 */
    @Test
    fun `分换算成元`() {
        val json = wrap(listOf(rec("一食堂", "2026-10-01 12:00:00", 1654, 20050)))
        val (records, _) = PortalCardApi.parseRecords(json)

        // 只有一笔时没有"更早一条"作参照，方向按支出口径处理，所以是负的
        assertEquals(-16.54, records[0].amount, 0.001)
        assertEquals(200.50, records[0].balance, 0.001)
    }

    /** 没有 d 外层也要能解析（接口两种形态都出现过） */
    @Test
    fun `无外层 d 也能解析`() {
        val json = """{"data":[${rec("一食堂", "2026-10-01 12:00:00", 100, 500)}],"max_page":1}"""
        val (records, pages) = PortalCardApi.parseRecords(json)

        assertEquals(1, pages)
        assertEquals(5.00, records[0].balance, 0.001)
    }
}
