package com.bistu.allthebistu.data.auth

import android.util.Log
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.ScheduleParser
import com.bistu.allthebistu.data.TermWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 教务系统接口调用（用登录后的 Cookie）。
 *
 * 所有接口都在 https://jwxt.bistu.edu.cn 下。
 */
class JwxtApi(
    private val auth: BistuAuth,
    private val logger: (String) -> Unit = { Log.d(TAG, it) }
) {

    companion object {
        private const val TAG = "JwxtApi"
        private const val BASE = "https://jwxt.bistu.edu.cn"

        /** 当前学期 */
        const val API_CURRENT_TERM = "$BASE/jwapp/sys/jwpubapp/modules/gg/cxmrxnxq.do"

        /** 学期列表 */
        const val API_TERM_LIST = "$BASE/jwapp/sys/homeapp/api/home/kb/xnxq.do"

        /** 教学周日期 */
        const val API_TERM_WEEKS = "$BASE/jwapp/sys/kbbpapp/api/schoolCalendar/getTermWeeks.do"

        /** 校区列表 */
        const val API_CAMPUS = "$BASE/jwapp/sys/homeapp/api/home/student/getMyScheduledCampus.do"

        /** 课表详情 */
        const val API_SCHEDULE = "$BASE/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do"

        /** 考试安排 */
        const val API_EXAM = "$BASE/jwapp/sys/wdkwapp/api/wdks/queryMyExamArrangeMent.do"

        /** 课表模块入口（建立 session 用） */
        const val ENTRY_SCHEDULE =
            "$BASE/jwapp/sys/kbapp/*default/index.do?THEME=indigo&EMAP_LANG=zh&forceApp=kbapp"

        /** 移动端 UA */
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/145.0.0.0 Mobile Safari/537.36"
    }

    /**
     * 直接复用 auth 的 redirectClient —— 它带同一个 CookieJar，
     * CAS 登录拿到的 Cookie 会自动带上，不用手动拼 Cookie 头。
     */
    private val client: OkHttpClient
        get() = auth.redirectClient

    /** 构造请求（Cookie 由 CookieJar 自动管理） */
    private fun requestBuilder(url: String): Request.Builder {
        return Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .addHeader("Referer", "$BASE/jwapp/sys/kbapp/*default/index.do")
            .addHeader("X-Requested-With", "XMLHttpRequest")
    }

    /** GET 请求 */
    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        logger("GET $url")
        logger("Cookie 详情:\n${auth.dumpCookies()}")
        client.newCall(requestBuilder(url).get().build()).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            logger("GET 响应: HTTP ${resp.code}, 长度 ${body.length}")
            if (!resp.isSuccessful) throw AuthException("HTTP ${resp.code}: ${body.take(200)}")
            body
        }
    }

    /** POST 请求（form 格式） */
    private suspend fun post(url: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            logger("POST $url params=$params")
            val body = FormBody.Builder().apply {
                params.forEach { (k, v) -> add(k, v) }
            }.build()

            client.newCall(requestBuilder(url).post(body).build()).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                logger("POST 响应: HTTP ${resp.code}, 长度 ${text.length}")
                if (!resp.isSuccessful) throw AuthException("HTTP ${resp.code}: ${text.take(200)}")

                // 会话失效时，教务系统常常不返回 401，而是 200 + 一张统一身份认证登录页。
                // 这种响应交给 JSONObject 解析会抛 JSONException，被当成「普通错误」，
                // 于是登录态不会被清理，界面就出现「已登录却显示示例数据」的矛盾状态。
                // 这里提前识别 HTML / 登录页，统一按会话失效处理。
                if (isLikelyLoginPage(text)) {
                    throw AuthException("接口返回登录页，会话已失效（HTTP ${resp.code}）")
                }
                val head = text.trimStart()
                if (!head.startsWith("{") && !head.startsWith("[")) {
                    throw IllegalStateException("接口返回了非 JSON 内容：${head.take(120)}")
                }
                text
            }
        }

    /**
     * 建立课表模块的 session。
     * 有些接口需要先访问模块入口页，否则返回 401。
     */
    suspend fun ensureSession() = withContext(Dispatchers.IO) {
        logger("ensureSession: 访问课表模块入口")
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(ENTRY_SCHEDULE)
                    .addHeader("Cookie", auth.getAllCookiesHeader())
                    .addHeader("User-Agent", UA)
                    .get()
                    .build()
            ).execute().close()
        }
    }

    /** 获取当前学期代码 */
    suspend fun getCurrentTerm(): Pair<String, String> {
        val json = post(API_CURRENT_TERM, mapOf(
            "CSDM" to "SYS",
            "ZCSDM" to "DQXNXQDM",
            "SFSY" to "1"
        ))

        val obj = org.json.JSONObject(json)
        val rows = obj.getJSONObject("datas")
            .getJSONObject("cxmrxnxq")
            .getJSONArray("rows")

        if (rows.length() == 0) throw AuthException("没有当前学期数据")

        val term = rows.getJSONObject(0)
        return term.getString("XNXQDM") to term.getString("XNXQMC")
    }

    /** 获取校区列表 */
    suspend fun getCampusCodes(termCode: String): List<String> {
        val json = get("$API_CAMPUS?termCode=$termCode")
        val obj = org.json.JSONObject(json)
        val datas = obj.optJSONArray("datas") ?: return listOf("10")

        return (0 until datas.length()).mapNotNull { i ->
            datas.getJSONObject(i).optString("id").takeIf { it.isNotBlank() }
        }.ifEmpty { listOf("10") }
    }

    /**
     * 获取教学周列表（每周的起止日期）。
     *
     * 接口：POST getTermWeeks.do，参数 XNXQDM=学期代码
     * 返回：datas.getTermWeeks[]，每项含 serialNumber / startDate / endDate
     *
     * 失败时返回空列表而不是抛异常 —— 教学周只是「锦上添花」，
     * 拿不到时课表仍应能按课程覆盖范围正常显示。
     */
    suspend fun getTermWeeks(termCode: String): List<TermWeek> {
        return runCatching {
            val json = post(API_TERM_WEEKS, mapOf("XNXQDM" to termCode))
            val obj = org.json.JSONObject(json)
            val arr = obj.optJSONObject("datas")?.optJSONArray("getTermWeeks")
                ?: return@runCatching emptyList()

            (0 until arr.length()).mapNotNull { i ->
                val w = arr.optJSONObject(i) ?: return@mapNotNull null
                val num = w.optInt("serialNumber", 0)
                val start = w.optString("startDate", "")
                val end = w.optString("endDate", "")
                if (num <= 0 || start.isBlank()) null
                else TermWeek(weekNumber = num, startDate = start, endDate = end)
            }.sortedBy { it.weekNumber }
        }.onFailure {
            logger("getTermWeeks 失败（将退化为按课程周次显示）: ${it.message}")
        }.getOrDefault(emptyList())
    }

    /**
     * 获取完整课表。
     *
     * @param termCode 学期代码，null 表示自动获取当前学期
     */
    suspend fun fetchSchedule(termCode: String? = null): ScheduleData {
        ensureSession()

        // ① 确定学期
        val (actualTermCode, termName) = if (termCode != null) {
            termCode to termCode
        } else {
            getCurrentTerm()
        }
        logger("学期: $actualTermCode ($termName)")

        // ② 拿校区列表
        val campusCodes = getCampusCodes(actualTermCode)
        logger("校区: $campusCodes")

        // ③ 每个校区拉课表，合并原始 JSON
        val mergedCourses = mutableListOf<org.json.JSONObject>()
        var subTitles: org.json.JSONArray? = null

        campusCodes.forEach { campusCode ->
            val json = post(API_SCHEDULE, mapOf(
                "XNXQDM" to actualTermCode,
                "XQDM" to campusCode
            ))

            val obj = org.json.JSONObject(json)
            val code = obj.optString("code")
            if (code != "0") {
                logger("校区 $campusCode 返回失败: code=$code")
                return@forEach
            }

            val detail = obj.getJSONObject("datas").getJSONObject("getMyScheduleDetail")
            if (subTitles == null) {
                subTitles = detail.optJSONArray("subTitles")
            }

            val list = detail.optJSONArray("arrangedList") ?: return@forEach
            (0 until list.length()).forEach { i ->
                mergedCourses.add(list.getJSONObject(i))
            }
        }

        logger("总共拉取 ${mergedCourses.size} 条原始课程")

        if (mergedCourses.isEmpty()) {
            throw AuthException("没有获取到课程数据（可能登录已过期）")
        }

        // ④ 重新组装成完整 JSON，交给已有的 ScheduleParser 解析
        val rebuilt = org.json.JSONObject().apply {
            put("code", "0")
            put("datas", org.json.JSONObject().apply {
                put("getMyScheduleDetail", org.json.JSONObject().apply {
                    put("subTitles", subTitles ?: org.json.JSONArray())
                    put("arrangedList", org.json.JSONArray(mergedCourses))
                    put("mergeArrangedList", org.json.JSONArray())
                    put("termName", termName)
                })
            })
        }

        return ScheduleParser.parse(rebuilt.toString(), actualTermCode)
            .copy(termWeeks = getTermWeeks(actualTermCode))
    }
}

/**
 * 判断一段响应体是不是「登录页」而不是接口数据。
 *
 * 教务系统的会话过期后不会回 401，而是 200 + 一张 CAS 登录页；
 * 这类响应必须按「会话失效」处理，否则会被误当成普通解析错误，
 * 登录态不清、界面就会显示「已登录但只有示例数据」。
 */
internal fun isLikelyLoginPage(body: String): Boolean {
    val head = body.trimStart()
    if (head.isEmpty()) return false
    // 正常接口一定是 JSON
    if (head.startsWith("{") || head.startsWith("[")) return false
    return head.startsWith("<") ||
            head.contains("统一身份认证") ||
            head.contains("CASTGC") ||
            head.contains("login", ignoreCase = true) ||
            head.contains("service=", ignoreCase = true)
}
