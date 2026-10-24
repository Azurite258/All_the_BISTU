package com.bistu.allthebistu.data.card

import android.util.Log
import com.bistu.allthebistu.data.auth.BistuAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * 校园一卡通数据源（走信息门户网关）。
 *
 * ## 为什么走门户而不是一卡通系统
 * 一卡通本体的「信息科大 E 卡通」是微信企业号里的 H5，走微信 OAuth，App 拿不到会话；
 * 但信息门户 `info.bistu.edu.cn` 首页就聚合了一卡通数据，而且它走的是**学校 CAS**，
 * 可以复用现有 [BistuAuth] 的 SSO 会话。
 *
 * ## 接口链路（从门户前端 bundle 里扒出来的）
 * ```
 * 1. GET /manage/common/cas_login/1?to_bind=0&redirect=...
 *      门户 → 302 → sso.bistu.edu.cn/login?service=...  （带 TGC 直接换 ticket）
 *      → 302 回门户 → 种下 SESS_ID 会话
 * 2. GET /personal/frontend/data/items?type=personal_data
 *      → 个人数据卡片列表，找 key = "card.spentMonth" 的卡片 id
 * 3. GET /personal/frontend/data/detail?id={id}
 *      → 该卡片数据：KNYE=卡内余额(分)、JYJE=交易金额(分)、JYRQ=交易时间、商户名称
 * ```
 *
 * 当前阶段只做「抓原始数据」——字段结构要等真实响应回来再定，
 * 所以这里不解析业务模型，只负责把三个响应原样带出来。
 */
object PortalCardApi {

    private const val TAG = "PortalCard"

    const val PORTAL_BASE = "https://info.bistu.edu.cn"

    /** 门户 CAS 入口：它自己会 302 到 SSO，所以直接请求它即可 */
    private const val CAS_LOGIN_PATH =
        "/manage/common/cas_login/1?to_bind=0&redirect=https%3A%2F%2Finfo.bistu.edu.cn"

    /** 个人数据卡片列表 */
    private const val ITEMS_PATH = "/personal/frontend/data/items?type=personal_data"

    /** 一卡通当月消费卡片的 key */
    private const val CARD_KEY = "card.spentMonth"

    /** 一次抓取的全部原始材料 */
    data class RawDump(
        val log: String,
        val itemsJson: String,
        val detailId: String?,
        val detailJson: String,
        val error: String? = null
    ) {
        /** 存成一段方便复制/回传的纯文本 */
        fun toPlainText(): String = buildString {
            appendLine("=== 抓取日志 ===")
            appendLine(log)
            error?.let { appendLine("!!! 错误: $it") }
            appendLine()
            appendLine("=== items 响应 ===")
            appendLine(itemsJson)
            appendLine()
            appendLine("=== detail id = ${detailId ?: "(未找到)"} ===")
            appendLine(detailJson)
        }
    }

    /**
     * 抓一次原始数据。
     *
     * 必须在已登录状态下调用：复用 [BistuAuth.redirectClient] 的 CookieJar，
     * 这样访问门户时 SSO 才能凭已有的 TGC 换到 ticket。
     */
    suspend fun dump(auth: BistuAuth): RawDump = withContext(Dispatchers.IO) {
        val client = auth.redirectClient
        val log = StringBuilder()

        try {
            // ── 1. 建立门户会话 ──
            val casUrl = PORTAL_BASE + CAS_LOGIN_PATH
            client.newCall(Request.Builder().url(casUrl).get().build()).execute().use { resp ->
                log.appendLine("CAS  ${resp.code}  →  ${resp.request.url}")
                log.appendLine("门户 Cookie: ${resp.headers("Set-Cookie").joinToString(" | ").take(200)}")
                Log.d(TAG, "CAS ${resp.code} final=${resp.request.url}")
            }

            // ── 2. 卡片列表 ──
            val itemsResp = client.newCall(
                Request.Builder().url(PORTAL_BASE + ITEMS_PATH).get().build()
            ).execute()
            val itemsJson = itemsResp.body?.string().orEmpty()
            log.appendLine("items  ${itemsResp.code}  ${itemsJson.length} 字符")
            itemsResp.close()

            if (itemsResp.code != 200) {
                return@withContext RawDump(
                    log = log.toString(),
                    itemsJson = itemsJson.take(500),
                    detailId = null,
                    detailJson = "",
                    error = "items 返回 HTTP ${itemsResp.code}，可能门户会话没建立"
                )
            }

            // ── 3. 找一卡通卡片的 id ──
            val detailId = findCardId(itemsJson, CARD_KEY)
            log.appendLine("目标卡片 key=$CARD_KEY  id=$detailId")

            // ── 4. 取明细 ──
            var detailJson = ""
            if (detailId != null) {
                val detailResp = client.newCall(
                    Request.Builder()
                        .url("$PORTAL_BASE/personal/frontend/data/detail?id=$detailId")
                        .get()
                        .build()
                ).execute()
                detailJson = detailResp.body?.string().orEmpty()
                log.appendLine("detail  ${detailResp.code}  ${detailJson.length} 字符")
                detailResp.close()
            }

            RawDump(log.toString(), itemsJson, detailId, detailJson)
        } catch (e: Exception) {
            Log.e(TAG, "dump 失败", e)
            RawDump(
                log = log.toString(),
                itemsJson = "",
                detailId = null,
                detailJson = "",
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * 在任意层级的 JSON 里找 `key == targetKey` 的对象，返回它的 id。
     *
     * 门户的 items 结构没验证过，可能包在 data/list/results 里，
     * 所以不硬编码路径，直接递归找。
     */
    private fun findCardId(json: String, targetKey: String): String? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        return searchForKey(root, targetKey)
    }

    private fun searchForKey(obj: JSONObject, targetKey: String): String? {
        if (obj.optString("key") == targetKey) {
            // id 字段名不确定，按常见命名依次尝试
            val candidates = listOf("id", "data_id", "dataId", "cardId", "detail_id")
            candidates.forEach { name ->
                if (obj.has(name) && !obj.isNull(name)) return obj.opt(name).toString()
            }
        }
        for (k in obj.keys()) {
            when (val v = obj.opt(k)) {
                is JSONObject -> searchForKey(v, targetKey)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        (v.opt(i) as? JSONObject)?.let { child ->
                            searchForKey(child, targetKey)?.let { return it }
                        }
                    }
                }
            }
        }
        return null
    }
}
