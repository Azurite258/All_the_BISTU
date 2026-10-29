package com.bistu.allthebistu.data.card

import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.auth.BistuAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 校园一卡通（数据源：信息门户网关）。
 *
 * ## 为什么走门户
 * 一卡通本体的「信息科大 E 卡通」在微信企业号里，走微信 OAuth，App 拿不到会话；
 * 但信息门户 `info.bistu.edu.cn` 聚合了一卡通数据，且走**学校 CAS**，
 * 可以复用 [BistuAuth] 已有的 SSO 会话。
 *
 * ## 接口（全部从门户前端 bundle 里核对过）
 * ```
 * 0. GET /manage/common/cas_login/1?to_bind=0&redirect=...
 *      门户 302 → sso.bistu.edu.cn/login?service=... → 凭 TGC 换 ticket → 种 SESS_ID
 *
 * 1. GET /personal/frontend/data/items?type=personal_data
 *      → {e,m,d:{data:[{key,name,id,...}]}}，取 key="card.spentMonth" 的 id
 *
 * 2. GET /personal/frontend/data/detail?id={id}
 *      → d.data = {name:"一卡通消费(当月)", value:<分>, unit:"元"}
 *      ⚠ value 单位是**分**，门户前端自己做了 /100
 *
 * 3. GET /bistu/frontend/oa/card_records?page=1&pageSize=30
 *      → d.data = [{SHMC 商户, JYRQ 交易时间, JYJE 金额(分), KNYE 卡内余额(分), ...}]
 *      → d.max_page 总页数
 *      门户没有独立的「余额」接口，余额取自**最新一笔记录的 KNYE**。
 * ```
 *
 * ## 只读
 * 充值、挂失、缴费这些一律不碰，只查余额和流水。
 *
 * ## 网费充值（2026-10-24 补）
 * 门户 `items` 只返回 5 张数据卡（未读邮件 / 工资 / 手机号 / 一卡通消费 / 知会），
 * **没有**网费的余额接口。但网费充值会写进一卡通流水
 * （实测：`2026-10-09 网络中心网费（微信） ¥20.00`），
 * 于是按商户关键词从流水中汇总「本月充值合计」，见 [findTopUp]。
 * 这是**充值金额**，不是余额，界面上按「网费充值」标注，不要混。
 *
 * 电费同类方案**已拆除**：E 卡通要微信企业号 OAuth 换的 openid，App 拿不到，
 * 卡面摆了也只能显示「--」，见交接文档 17.3。
 */
object PortalCardApi {

    private const val TAG = "PortalCard"

    const val PORTAL_BASE = "https://info.bistu.edu.cn"

    /** 门户 CAS 入口：它自己会 302 到 SSO */
    private const val CAS_LOGIN_PATH =
        "/manage/common/cas_login/1?to_bind=0&redirect=https%3A%2F%2Finfo.bistu.edu.cn"

    private const val ITEMS_PATH = "/personal/frontend/data/items?type=personal_data"
    private const val RECORDS_PATH = "/bistu/frontend/oa/card_records"

    private const val CARD_KEY = "card.spentMonth"

    /** 一页拿够一个月的量，先只取第一页 */
    private const val PAGE_SIZE = 30

    /** 网费充值流水的商户关键词。实测商户名：「网络中心网费（微信）」 */
    private val NETWORK_KEYWORDS = listOf("网费", "网络中心", "校园网", "上网")

    /** 单笔消费/充值记录（金额已换算成元） */
    data class CardRecord(
        val merchant: String,
        val time: String,
        /** 带方向：支出为负、充值（卡内余额变多）为正 */
        val amount: Double,
        val balance: Double,
        /**
         * 原始交易金额（绝对值，元）。
         *
         * `amount` 是靠余额差推断方向的，可「网费（微信）」这类走第三方支付的缴费
         * 卡内余额几乎不动，会被误判成支出。识别网费充值要用这个原始值。
         */
        val rawAmount: Double = 0.0,
    )

    /**
     * 一笔缴费充值（网费）。
     *
     * 门户没有网费余额接口，但充值会落在**一卡通流水**里
     * （实测：`2026-10-09 网络中心网费（微信）`），所以从流水反推「本月充了多少」，
     * 这是真数据，不是余额。
     */
    data class UtilityTopUp(
        /** 本月累计充值金额（元） */
        val amount: Double,
        /** 最近一笔的时间 */
        val lastTime: String,
        /** 最近一笔的商户名 */
        val merchant: String,
    )

    /** 一次查询的完整结果 */
    data class Overview(
        val balance: Double?,
        val monthSpent: Double,
        val records: List<CardRecord>,
        val totalPages: Int,
        val log: String,
        val error: String? = null,
        /** 校园卡状态，如「正常」 */
        val status: String = "",
        /** 有效期，如「2046-08-09」 */
        val expiry: String = "",
        /** 本月网费充值（从流水识别，**不是余额**，门户没有余额接口） */
        val networkTopUp: UtilityTopUp? = null,
        val rawItems: String = "",
        val rawDetail: String = "",
        val rawRecords: String = "",
    ) {
        /** 原始材料，调试/回传用 */
        fun rawText(): String = buildString {
            appendLine("=== 抓取日志 ===")
            appendLine(log)
            error?.let { appendLine("!!! $it") }
            appendLine()
            appendLine("=== items ===")
            appendLine(rawItems)
            appendLine()
            appendLine("=== detail ===")
            appendLine(rawDetail)
            appendLine()
            appendLine("=== card_records ===")
            appendLine(rawRecords)
        }
    }

    /**
     * 查一次余额 + 当月消费 + 流水。
     *
     * 必须在已登录状态下调用：复用 [BistuAuth.redirectClient] 的 CookieJar，
     * 这样访问门户时 SSO 才能凭已有的 TGC 换到 ticket。
     */
    suspend fun fetch(auth: BistuAuth): Overview = withContext(Dispatchers.IO) {
        val client = auth.redirectClient
        val log = StringBuilder()

        var rawItems = ""
        var rawDetail = ""
        var rawRecords = ""

        try {
            // ── 0. 建立门户会话 ──
            client.newCall(
                Request.Builder().url(PORTAL_BASE + CAS_LOGIN_PATH).get().build()
            ).execute().use { resp ->
                log.appendLine("CAS ${resp.code} → ${resp.request.url}")
                AppLog.d(TAG, "CAS ${resp.code} → ${resp.request.url}")
            }

            // ── 1. 卡片列表，找一卡通那张的 id ──
            val itemsResp = client.newCall(
                Request.Builder().url(PORTAL_BASE + ITEMS_PATH).get().build()
            ).execute()
            rawItems = itemsResp.body?.string().orEmpty()
            log.appendLine("items ${itemsResp.code} (${rawItems.length} 字符)")
            itemsResp.close()

            if (itemsResp.code != 200) {
                return@withContext Overview(
                    balance = null, monthSpent = 0.0, records = emptyList(), totalPages = 0,
                    log = log.toString(),
                    error = "门户会话没建立（items HTTP ${itemsResp.code}）",
                    rawItems = rawItems.take(500)
                )
            }

            val cardId = findCardId(rawItems, CARD_KEY)
            log.appendLine("卡片 $CARD_KEY id=${cardId?.take(16)}…")

            // ── 2. 当月消费总额 ──
            var monthSpent = 0.0
            if (cardId != null) {
                val detailResp = client.newCall(
                    Request.Builder()
                        .url("$PORTAL_BASE/personal/frontend/data/detail?id=$cardId")
                        .get().build()
                ).execute()
                rawDetail = detailResp.body?.string().orEmpty()
                log.appendLine("detail ${detailResp.code} (${rawDetail.length} 字符)")
                detailResp.close()
                monthSpent = parseMonthSpent(rawDetail)
            }

            // ── 3. 流水（同时带回余额）──
            val recordsResp = client.newCall(
                Request.Builder()
                    .url("$PORTAL_BASE$RECORDS_PATH?page=1&pageSize=$PAGE_SIZE")
                    .get().build()
            ).execute()
            rawRecords = recordsResp.body?.string().orEmpty()
            log.appendLine("card_records ${recordsResp.code} (${rawRecords.length} 字符)")
            recordsResp.close()

            val (records, totalPages) = parseRecords(rawRecords)
            val (status, expiry) = parseMeta(rawRecords)
            val network = findTopUp(records, NETWORK_KEYWORDS)
            log.appendLine("解析到 ${records.size} 条流水，共 $totalPages 页，状态=$status 有效期=$expiry")
            log.appendLine("网费充值=${network?.amount ?: "无"}")

            Overview(
                balance = records.firstOrNull()?.balance,
                monthSpent = monthSpent,
                records = records,
                totalPages = totalPages,
                log = log.toString(),
                status = status,
                expiry = expiry,
                networkTopUp = network,
                rawItems = rawItems,
                rawDetail = rawDetail,
                rawRecords = rawRecords
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "fetch 失败", e)
            Overview(
                balance = null, monthSpent = 0.0, records = emptyList(), totalPages = 0,
                log = log.toString(),
                error = "${e.javaClass.simpleName}: ${e.message}",
                rawItems = rawItems, rawDetail = rawDetail, rawRecords = rawRecords
            )
        }
    }

    // ══════════════════════════════════════════════════════════
    // 解析
    // ══════════════════════════════════════════════════════════

    /**
     * 从 detail 响应里取当月消费。
     *
     * 结构：`{e,m,d:{data:{name,value,unit}}}`，value 是**分**。
     */
    private fun parseMonthSpent(json: String): Double {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return 0.0
        val d = root.optJSONObject("d") ?: root
        val data = d.optJSONObject("data") ?: return 0.0
        return data.optDouble("value", 0.0) / 100.0
    }

    /**
     * 从流水里取卡面信息：账号状态、有效期。
     * 这两个字段每条记录都有，取第一条即可。
     */
    private fun parseMeta(json: String): Pair<String, String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return "" to ""
        val d = root.optJSONObject("d") ?: root
        val arr: JSONArray = d.optJSONArray("data") ?: return "" to ""
        val first = arr.optJSONObject(0) ?: return "" to ""
        return first.optString("ZHZTMC") to first.optString("YXQ")
    }

    /**
     * 解析流水。
     *
     * 结构：`{e,m,d:{data:[...],max_page:n}}`，也可能没有外层 d，两种都兼容。
     * 接口按**时间倒序**返回（最新在前），每条带该笔交易之后的卡内余额 KNYE。
     *
     * ⚠ JYJE 不管是消费还是缴费都是正数，没法靠符号判断收支，
     * 所以用「本条余额 - 上一条（更早）余额」来推断方向：余额变少 = 支出。
     */
    private fun parseRecords(json: String): Pair<List<CardRecord>, Int> {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return emptyList<CardRecord>() to 0
        val d = root.optJSONObject("d") ?: root
        val arr: JSONArray = d.optJSONArray("data") ?: return emptyList<CardRecord>() to 0
        val totalPages = d.optInt("max_page", 1)

        val raw = ArrayList<CardRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            raw += CardRecord(
                merchant = o.optString("SHMC").ifBlank { "未知商户" },
                time = o.optString("JYRQ"),
                // 门户前端也是 /100，单位是分
                amount = o.optDouble("JYJE", 0.0) / 100.0,
                balance = o.optDouble("KNYE", 0.0) / 100.0,
                rawAmount = abs(o.optDouble("JYJE", 0.0)) / 100.0,
            )
        }

        // 用余额差补上正负号：raw 是倒序，raw[i+1] 比 raw[i] 更早
        val signed = raw.mapIndexed { i, r ->
            val older = raw.getOrNull(i + 1)?.balance
            val delta = if (older == null) null else r.balance - older
            val amount = when {
                delta == null -> -abs(r.amount)      // 最老一条没有参照，按支出
                delta < -0.005 -> -abs(r.amount)     // 余额变少 → 支出
                delta > 0.005 -> abs(r.amount)       // 余额变多 → 充值
                else -> -abs(r.amount)
            }
            r.copy(amount = amount)
        }
        return signed to totalPages
    }

    /**
     * 在流水里找网费充值，汇总本月金额。
     *
     * 返回 null 表示这个月没有这类记录（UI 显示「--」），不要编一个 0。
     * 金额用 [CardRecord.rawAmount]，因为「网费（微信）」这类走第三方支付的缴费
     * 卡内余额几乎不动，用收支推断出来的 `amount` 会带错正负号。
     */
    private fun findTopUp(records: List<CardRecord>, keywords: List<String>): UtilityTopUp? {
        val hits = records.filter { r -> keywords.any { r.merchant.contains(it) } }
        if (hits.isEmpty()) return null
        val latest = hits.first()   // 接口按时间倒序，第一条最新
        return UtilityTopUp(
            amount = hits.sumOf { it.rawAmount },
            lastTime = latest.time,
            merchant = latest.merchant,
        )
    }

    /**
     * 在任意层级找 `key == targetKey` 的卡片，返回其 id。
     * 门户 items 的结构没写死在文档里，递归找最稳。
     */
    private fun findCardId(json: String, targetKey: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return searchForKey(root, targetKey)
    }

    private fun searchForKey(obj: JSONObject, targetKey: String): String? {
        if (obj.optString("key") == targetKey) {
            listOf("id", "data_id", "dataId", "cardId").forEach { name ->
                if (obj.has(name) && !obj.isNull(name)) return obj.opt(name).toString()
            }
        }
        for (k in obj.keys()) {
            when (val v = obj.opt(k)) {
                is JSONObject -> searchForKey(v, targetKey)?.let { return it }
                is JSONArray -> for (i in 0 until v.length()) {
                    (v.opt(i) as? JSONObject)?.let { child ->
                        searchForKey(child, targetKey)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    /** 金额格式化：¥12.50 / -¥12.50 */
    fun formatMoney(v: Double, withSign: Boolean = false): String {
        // 测试账号的余额是 Double.POSITIVE_INFINITY，
        // "%.2f".format(∞) 会输出 "Infinity"，直接显示无穷符号更好认
        if (v.isInfinite() || v.isNaN()) return if (v < 0) "-¥∞" else "¥∞"
        val sign = when {
            !withSign -> ""
            v > 0 -> "+"
            v < 0 -> "-"
            else -> ""
        }
        return "$sign¥${"%.2f".format(abs(v))}"
    }
}
