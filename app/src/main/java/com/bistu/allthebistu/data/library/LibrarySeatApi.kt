package com.bistu.allthebistu.data.library

import com.bistu.allthebistu.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 图书馆座位余量（数据源：沙河校区图书馆「IC 空间预约」系统）。
 *
 * ## 系统是什么
 * `https://reserve-lib.bistu.edu.cn/mobile.html#/ic/homePage`
 * —— Vue 单页应用，接口基址 `window.g.ApiUrl = "/ic-web"`，
 * 前端所有请求都是 `GET /ic-web/<path>`（见打包产物里的 `url:"..."`）。
 *
 * ## 为什么只做「全馆空座」
 * 逐个实测过（2026-10-27，公网、不带任何 Cookie）：
 * ```
 * GET /ic-web/seatDevice/resvStatus   → 0   {"available":1939,"used":340}   ← 唯一免登录
 * GET /ic-web/seatMenu                → 300 用户未登录
 * GET /ic-web/reserve/seatArea        → 300 用户未登录
 * GET /ic-web/reserve/areaInfo        → 300 用户未登录
 * GET /ic-web/seatRoom/open           → 300 用户未登录
 * GET /ic-web/home/page/room/idle     → 300 用户未登录
 * GET /ic-web/seatDevice/coordinate   → 300 用户未登录
 * ```
 * 也就是说：**分楼层 / 分区域的空座数需要登录会话**，而登录方式是 CAS 单点登录
 * （`config.js` 里 `loginMode: 2`），走 `sso.bistu.edu.cn` 换票到
 * `/ic-web/login/cas`。这条路能不能用 App 现有的 TGC 白嫖到，**还没验证过**
 * （校外探测时 `/ic-web/login/cas` 带假 ticket 也只回「用户未登录」，
 * 全局鉴权挡在 controller 之前，分不出接口是否存在）。
 *
 * 所以这里只接**确定可用**的那一个接口，界面上的数字不编：
 * - 有数就显示「空座 N / 总 M」
 * - 请求失败就显示「--」并给一行小字说明
 * - 想看具体哪个座位/楼层，点卡片跳官方预约页（那边登录态是现成的）
 *
 * ⚠️ 后续如果拿到了登录态，`seatMenu` 的响应结构还需要真实抓一次再写解析，
 * 不要凭猜测写字段名。
 */
object LibrarySeatApi {

    private const val TAG = "LibrarySeat"

    /** 预约系统根地址 */
    const val BASE = "https://reserve-lib.bistu.edu.cn"

    /** 手机端首页（#/ic/homePage），点卡片跳这里 */
    const val MOBILE_HOME = "$BASE/mobile.html#/ic/homePage"

    /** 全馆座位实时余量：唯一免登录接口 */
    private const val STATUS_PATH = "/ic-web/seatDevice/resvStatus"

    /** 教务那套 UA 是绕 WAF 用的；这里只需要一个正常的浏览器标识 */
    private val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    /** 座位余量快照 */
    data class Snapshot(
        /** 当前可用座位数 */
        val available: Int,
        /** 当前已被占用（或已预约）座位数 */
        val used: Int,
        /** 取数时刻（用于显示「更新于 hh:mm」） */
        val atMillis: Long,
        /** 非空表示这次没取到 */
        val error: String? = null,
    ) {
        val total: Int get() = available + used

        /** 占用率 0f..1f，总数为 0 时按 0 处理（避免除零） */
        val occupancy: Float
            get() = if (total <= 0) 0f else used.toFloat() / total
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 查一次全馆座位余量。
     *
     * 不需要登录、不需要 Cookie：这套接口在公网就能访问（实测校外可用）。
     * 失败不抛异常，一律装在 [Snapshot.error] 里交给界面处理。
     */
    suspend fun fetch(): Snapshot = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(BASE + STATUS_PATH)
                .header("User-Agent", UA)
                .header("Referer", "$BASE/mobile.html")
                .header("Accept", "application/json")
                .get()
                .build()

            val body = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Snapshot(
                        available = 0, used = 0, atMillis = startedAt,
                        error = "图书馆系统 HTTP ${resp.code}"
                    )
                }
                resp.body?.string().orEmpty()
            }

            parse(body, startedAt).also {
                AppLog.d(
                    TAG,
                    "resvStatus: available=${it.available} used=${it.used} " +
                        "error=${it.error ?: "无"}"
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "fetch 失败", e)
            Snapshot(
                available = 0, used = 0, atMillis = startedAt,
                error = when (e) {
                    is java.net.SocketTimeoutException -> "连接图书馆系统超时"
                    is java.net.UnknownHostException -> "网络没通（DNS 解析失败）"
                    else -> "${e.javaClass.simpleName}: ${e.message}"
                }
            )
        }
    }

    /**
     * 解析 `{"code":0,"message":"查询成功","data":{"available":1939,"used":340},...}`
     *
     * - `code == 0` 才算成功
     * - `code == 300` 是这套系统的「用户未登录」；这个接口理论上不该出现，
     *   真出现了说明学校改了鉴权，按失败处理而不是显示 0
     */
    internal fun parse(json: String, atMillis: Long): Snapshot {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Snapshot(0, 0, atMillis, error = "图书馆系统返回非 JSON")

        val code = root.optInt("code", -1)
        if (code == 300) {
            return Snapshot(0, 0, atMillis, error = "图书馆系统要求登录（接口鉴权变了）")
        }
        if (code != 0) {
            val msg = root.optString("message").ifBlank { "code=$code" }
            return Snapshot(0, 0, atMillis, error = msg)
        }

        val data = root.optJSONObject("data")
            ?: return Snapshot(0, 0, atMillis, error = "返回里没有 data")

        val available = data.optInt("available", -1)
        val used = data.optInt("used", -1)
        if (available < 0 || used < 0) {
            return Snapshot(0, 0, atMillis, error = "返回里缺少 available/used")
        }
        return Snapshot(available, used, atMillis)
    }
}
