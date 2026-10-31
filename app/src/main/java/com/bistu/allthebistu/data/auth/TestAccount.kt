package com.bistu.allthebistu.data.auth

import com.bistu.allthebistu.data.CreditCategory
import com.bistu.allthebistu.data.StudyReport
import com.bistu.allthebistu.data.card.PortalCardApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 本地测试账号（管理员演示用）。
 *
 * 这个账号**完全不触碰学校服务器**：不出网、不建 SSO 会话、不写 Cookie，
 * 登录后所有数据（昵称、学号、一卡通、学业报告）都是本地随机生成的，
 * 只为把界面点亮，方便在没有校园网 / 没有真实身份的时候走完整流程。
 *
 * 想关掉时只改这一处：[ENABLED] 置 false 即可，登录页会自动回落到真实 CAS。
 * 发布给普通用户前务必置 false —— 否则任何人输入 8 个 0 都能进到一页假数据。
 */
object TestAccount {

    /** 总开关。置 false 后 [matches] 恒为 false，其余函数不会被调用 */
    const val ENABLED = true

    const val ID = "00000000"
    const val PASSWORD = "00000000"

    /** 是否是测试账号的凭据（学号 + 密码都必须是 8 个 0） */
    fun matches(id: String, password: String): Boolean =
        ENABLED && id == ID && password == PASSWORD

    /** 随机出来的一套身份信息 */
    data class Profile(
        val nickname: String,
        val studentNo: String,
        val college: String,
        val major: String,
        val className: String,
    )

    // ────────────────────────────────────────────────────────────
    // 随机素材
    // ────────────────────────────────────────────────────────────

    /**
     * 测试账号的昵称是**固定**的，不再从姓名库里随机拼。
     *
     * 原因：随机拼出来的「张子墨」「王语嫣」这类名字，很容易跟学校里真人重名，
     * 截图发到公开仓库 / README 里会引起误会（2026-10-31 用户要求）。
     * 「雪霁」不是常见姓氏组合，一眼能看出是占位名。
     */
    const val NICKNAME = "雪霁"

    private val COLLEGES = listOf(
        "计算机学院", "信息与通信工程学院", "自动化学院", "机电工程学院",
        "经济管理学院", "理学院", "外国语学院"
    )

    private val MAJORS = listOf(
        "计算机科学与技术", "软件工程", "网络工程", "数据科学与大数据技术",
        "通信工程", "电子信息工程", "自动化", "人工智能"
    )

    private val MERCHANTS = listOf(
        "第一食堂", "第二食堂", "教工餐厅", "校园超市（东区）",
        "图书馆复印", "浴室热水", "水果店", "咖啡厅", "网络中心网费（微信）"
    )

    private fun pick(list: List<String>) = list[Random.nextInt(list.size)]

    /** 每次登录都换一套随机身份，方便反复测界面 */
    fun newProfile(): Profile {
        val no = buildString {
            append("2022")
            repeat(8) { append(Random.nextInt(0, 10)) }
        }
        return Profile(
            nickname = NICKNAME,
            studentNo = no,
            college = pick(COLLEGES),
            major = pick(MAJORS),
            className = "计科" + Random.nextInt(21, 26) + "-" + Random.nextInt(1, 5) + "班",
        )
    }

    // ────────────────────────────────────────────────────────────
    // 一卡通
    // ────────────────────────────────────────────────────────────

    /**
     * 测试账号的一卡通数据。
     *
     * 余额固定是**正无穷**（界面显示 ∞），其余（本月消费、流水、状态、有效期）
     * 每次打开生活页都重新随机，这样反复进出能看到不同的排版效果。
     */
    fun cardOverview(): PortalCardApi.Overview {
        val now = System.currentTimeMillis()
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

        val records = (0 until Random.nextInt(6, 11)).map { i ->
            val merchant = pick(MERCHANTS)
            // 网费充值记成收入，其余记成支出
            val isTopUp = merchant.contains("网费")
            val amount: Double = if (isTopUp) {
                Random.nextInt(2, 6) * 20.0
            } else {
                val raw = Random.nextInt(5, 90) + Random.nextDouble()
                -(Math.round(raw * 100) / 100.0)
            }
            PortalCardApi.CardRecord(
                merchant = merchant,
                time = formatter.format(Date(now - i * Random.nextLong(3600_000L, 86_400_000L))),
                amount = amount,
                // 余额是无穷，流水里也保持一致，一眼能看出是测试账号
                balance = Double.POSITIVE_INFINITY,
                rawAmount = Math.abs(amount),
            )
        }

        val topUp = records.firstOrNull { it.amount > 0 }

        return PortalCardApi.Overview(
            balance = Double.POSITIVE_INFINITY,
            monthSpent = Math.round(Random.nextDouble(80.0, 460.0) * 100) / 100.0,
            records = records,
            totalPages = 1,
            log = "测试账号：本地模拟数据，未发起任何真实请求",
            status = "正常",
            expiry = "20" + Random.nextInt(30, 36) + "-0" + Random.nextInt(1, 9) + "-15",
            networkTopUp = topUp?.let {
                PortalCardApi.UtilityTopUp(
                    amount = it.amount,
                    lastTime = it.time,
                    merchant = it.merchant
                )
            },
        )
    }

    // ────────────────────────────────────────────────────────────
    // 学业完成情况
    // ────────────────────────────────────────────────────────────

    private fun round1(v: Double) = Math.round(v * 10) / 10.0

    /** 随机一份学业报告：要求学分固定，已修/已选随机浮动 */
    fun studyReport(): StudyReport {
        val required = 160.0
        val earned = round1(Random.nextDouble(78.0, 132.0))
        val selection = round1(Random.nextDouble(8.0, 26.0))

        val names = listOf("通识教育", "专业教育", "专业拓展", "实践教学", "素质拓展")
        val categories = names.map { name ->
            CreditCategory(
                name = name,
                required = round1(Random.nextDouble(10.0, 55.0)),
                earned = round1(Random.nextDouble(5.0, 40.0)),
                selection = round1(Random.nextDouble(0.0, 8.0)),
            )
        }

        return StudyReport(
            programCode = "2022" + Random.nextInt(100000, 999999),
            creditsRequired = required,
            creditsEarned = earned,
            creditsSelection = selection,
            categories = categories,
            fetchedAt = System.currentTimeMillis(),
        )
    }
}
