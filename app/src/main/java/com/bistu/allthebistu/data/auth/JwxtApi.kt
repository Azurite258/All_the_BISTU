package com.bistu.allthebistu.data.auth

import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.ScheduleData
import com.bistu.allthebistu.data.ScheduleParser
import com.bistu.allthebistu.data.StudyReport
import com.bistu.allthebistu.data.StudyReportParser
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
    private val logger: (String) -> Unit = { AppLog.d(TAG, it) }
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

        /** 学业完成查询（个人学业监测报告：要求学分 / 已修 / 已选 / 分类明细） */
        const val API_STUDY_REPORT = "$BASE/jwapp/sys/byshapp/api/grbg/queryXyzhbx.do"

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
            logger("GET 响应: HTTP ${resp.code}, 落点=${resp.request.url.host}, 长度 ${body.length}")
            // 先判「是不是被认证拦了」：这种情况可能是 200 + 登录页，
            // 也可能被转成 302，必须排在 HTTP 状态判断前面
            offCampusError(resp)?.let { throw it }
            if (!resp.isSuccessful) throw httpError(resp.code, body)
            // 这条以前漏了：校外返回的登录页 HTML 会一路走到 JSONObject() 才炸，
            // 抛的是 JSONException，被当成「普通解析错误」，会话失效反而看不出来
            validateJsonBody(body, resp.code)
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
                logger("POST 响应: HTTP ${resp.code}, 落点=${resp.request.url.host}, 长度 ${text.length}")
                offCampusError(resp)?.let { throw it }
                if (!resp.isSuccessful) throw httpError(resp.code, text)
                validateJsonBody(text, resp.code)
                text
            }
        }

    /**
     * 这次响应是不是被统一身份认证截走了。
     *
     * 教务接口在校外（或会话失效）时不回 401，而是 302 到 `casLogin.do`，
     * 再被踢到 `sso.bistu.edu.cn` 的登录页。OkHttp 跟完重定向后，
     * `resp.request.url` 就是**最终落点**，所以「host 不是 jwxt」
     * 是一个确定信号——不用猜 IP、也不用猜返回文案。
     */
    private fun offCampusError(resp: okhttp3.Response): OffCampusException? {
        val host = resp.request.url.host
        if (host == BistuAuth.JWXT_HOST) return null
        // 只认校内域，避免 DNS 劫持 / 运营商插页时误报成「校外限制」
        if (!host.endsWith("bistu.edu.cn")) return null
        return OffCampusException(
            "教务系统把请求转到了 $host，说明这台设备现在进不去教务系统。" +
                    "校外访问需要连校园网或学校 VPN；若已在校园网，请退出登录后重新登一次。"
        )
    }

    /**
     * 把 HTTP 失败翻译成异常。
     *
     * 只有 401/403 才代表「会话没了」，必须用 [SessionExpiredException]，
     * 否则上层会把它和「没排课」「接口报错」混为一谈，把用户的登录态清掉。
     */
    private fun httpError(code: Int, body: String): Exception =
        if (code == 401 || code == 403) {
            SessionExpiredException("教务接口返回 $code，登录态已失效")
        } else {
            AuthException("HTTP $code: ${body.take(200)}")
        }

    /**
     * 学业完成情况（要求学分 / 已修 / 已选 / 分类明细）。
     *
     * 这个接口不带参数，但不同版本对 GET/POST 的支持不一样：
     * 先按 GET 试，拿到非 JSON 或失败再退回 POST（历史接口大多是 POST）。
     */
    suspend fun fetchStudyReport(): StudyReport {
        val raw = runCatching { getRaw(API_STUDY_REPORT) }
            .getOrElse { e ->
                logger("学业报告 GET 失败，改用 POST: ${e.message}")
                post(API_STUDY_REPORT, emptyMap())
            }
        return StudyReportParser.parse(raw)
    }

    /** 最朴素的 GET，供不带参数的查询接口使用 */
    private suspend fun getRaw(url: String): String = withContext(Dispatchers.IO) {
        logger("GET $url")
        client.newCall(requestBuilder(url).get().build()).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            logger("GET 响应: HTTP ${resp.code}, 落点=${resp.request.url.host}, 长度 ${text.length}")
            offCampusError(resp)?.let { throw it }
            if (!resp.isSuccessful) throw httpError(resp.code, text)
            validateJsonBody(text, resp.code)
            text
        }
    }

    /**
     * 校验响应体是不是接口数据。
     *
     * 会话失效时教务常常返回 200 + 统一身份认证登录页，
     * 这种响应交给 JSONObject 会抛 JSONException，被误当成普通错误，
     * 于是登录态不清、界面出现「已登录却显示示例数据」的矛盾状态。
     */
    private fun validateJsonBody(text: String, httpCode: Int) {
        if (isLikelyLoginPage(text)) {
            throw SessionExpiredException("接口返回登录页，会话已失效（HTTP $httpCode）")
        }
        val head = text.trimStart()
        if (!head.startsWith("{") && !head.startsWith("[")) {
            throw IllegalStateException("接口返回了非 JSON 内容：${head.take(120)}")
        }
    }

    /**
     * 建立课表模块的 session。
     * 有些接口需要先访问模块入口页，否则返回 401。
     *
     * ⚠️ 这里**不要**手动加 Cookie 头。
     * OkHttp 的规则是「请求自带 Cookie 头时就不再问 CookieJar」，
     * 而 [BistuAuth.getAllCookiesHeader] 在匹配不足 3 条时会「全部发送」，
     * 于是 sso 域下的 JSESSIONID 也会被塞给 jwxt，服务端取到哪一条全看实现，
     * 部分账号就会因此 401 —— 这正是「有的人登得上却拉不到课表」的另一条路径。
     * 统一交给同一个 CookieJar 按域匹配即可。
     */
    /**
     * 确保教务系统的会话可用；失效时**主动用 TGC 换票重建**。
     *
     * 这是「已登录却一直未能刷新」的根因所在：
     * 以前这里只是 `runCatching { 访问入口页 }.close()`，结果直接丢掉 ——
     * 教务把请求 302 到统一身份认证时，代码根本不知道，也不会去换票。
     * jwxt 的 JSESSIONID 在服务端一过期，就永远刷不出数据。
     *
     * 校园卡模块（PortalCardApi）从一开始就是「每次现场换票」，
     * 所以它的刷新从来没出过问题 —— 这里对齐它的做法。
     *
     * ⚠️ 不要手动加 Cookie 头。OkHttp 的规则是请求自带 Cookie 头就不再问
     * CookieJar，会把 sso 域的 JSESSIONID 也塞给 jwxt，部分账号因此 401。
     *
     * @return true 表示教务会话可用
     */
    suspend fun ensureSession(): CasResult = withContext(Dispatchers.IO) {
        // ① 先直接探业务入口：会话还在时省掉一次 CAS 往返
        val alive = runCatching {
            client.newCall(
                requestBuilder(ENTRY_SCHEDULE).get().build()
            ).execute().use { r ->
                val host = r.request.url.host
                logger("ensureSession: 入口落点=$host, HTTP ${r.code}")
                host == BistuAuth.JWXT_HOST
            }
        }.onFailure {
            // 以前这里是静默失败：探活一抛异常就当成「会话没了」，
            // 日志里连一行的痕迹都不留，排查时完全看不出是网络还是逻辑问题
            logger("ensureSession: 入口探活异常 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)

        if (alive) return@withContext CasResult.Ok

        // ② 被踢回认证了 → 拿 TGC 换一张票，把 jwxt 会话重建起来。
        //    直接复用登录时那套 [BistuAuth.casLoginDetailed]：它走的是
        //    sso/login?service=casLogin.do，登录流程已经验证过这条路能换到票，
        //    而且内部会探活确认「真的落在 jwxt 且不是登录页」。
        logger("ensureSession: 会话已失效，尝试用 TGC 换票")
        val renewed = runCatching { auth.casLoginDetailed() }.getOrElse {
            logger("ensureSession: 换票异常 ${it.message}")
            CasResult.Unreachable
        }

        logger(
            "ensureSession: 换票结果=$renewed, " +
                    "jwxt会话=${auth.hasJwxtSession()}, TGC=${auth.getSsoCookie("TGC") != null}"
        )
        renewed
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

    /**
     * 获取校区列表。
     *
     * ⚠️ 这里以前是「解析失败就退回 listOf("10")」—— 而 10 是**沙河校区**。
     * 小营等其他校区的学生于是永远只查沙河，拿回来空列表，
     * 上层再把它当「登录过期」，登录态被清掉，表现就是「登得上但没课表」。
     * 现在解析不出来就返回空，由 [fetchSchedule] 决定怎么降级。
     */
    suspend fun getCampusCodes(termCode: String): List<String> {
        val json = runCatching { get("$API_CAMPUS?termCode=$termCode") }
            .onFailure { logger("getCampusCodes 失败: ${it.message}") }
            .getOrNull() ?: return emptyList()

        val codes = parseCampusIds(json)
        logger("getCampusCodes: 解析到 ${codes.size} 个校区 $codes")
        return codes
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
        // 续期失败就**不要**再往下发了：
        // 硬着头皮请求只会拿到统一身份认证的登录页，被 validateJsonBody
        // 判成 SessionExpiredException，把用户直接踢下线并清掉 Cookie。
        //
        // 三种结论要分开处理，这是「掉线」误判的唯一出口：
        //  - NoTicket：服务端明确说没有有效 TGC → 才是真的过期，请用户重登
        //  - Unreachable：校外 / WAF / 维护 / 超时 → 账号本身是好的，绝不能清登录态
        when (ensureSession()) {
            CasResult.Ok -> Unit
            CasResult.NoTicket ->
                throw SessionExpiredException("统一身份认证会话已过期，请重新登录")
            CasResult.Unreachable -> throw OffCampusException(
                "教务系统没接住这次请求（校外访问、上网认证未完成或教务维护）。" +
                        "校内请下拉重试；校外需先连校园网或学校 VPN。"
            )
        }

        // ① 确定学期
        val (actualTermCode, termName) = if (termCode != null) {
            termCode to termCode
        } else {
            getCurrentTerm()
        }
        logger("学期: $actualTermCode ($termName)")

        // ② 拿校区列表；拿不到就退化成「不带 XQDM 查一次」
        val campusCodes = getCampusCodes(actualTermCode)
        logger("校区: ${campusCodes.ifEmpty { listOf("(未知)") }}")

        // ③ 每个校区拉课表，合并原始 JSON
        val mergedCourses = mutableListOf<org.json.JSONObject>()
        var subTitles: org.json.JSONArray? = null
        // mergeArrangedList = 教务侧的「合并后排课」。本字段在此之前只定义过、从没读过，
        // 重组 JSON 时还被主动置空。怀疑「同一门课多段安排只显示一段」就丢在这里
        // （有人反馈某课在教务有两个教室，App 只剩其中一个）。
        // 先只取证不改行为 —— 不清楚它的语义，贸然并进 arrangedList 可能整片重复。
        var mergeArrangedSample: org.json.JSONArray? = null
        var okQueries = 0
        var failCode = ""

        suspend fun queryCampus(campusCode: String) {
            val params = buildMap {
                put("XNXQDM", actualTermCode)
                // 校区未知时不带 XQDM：多数正方实例会返回全校数据
                if (campusCode.isNotBlank()) put("XQDM", campusCode)
            }
            val json = post(API_SCHEDULE, params)
            val obj = org.json.JSONObject(json)
            val code = obj.optString("code")
            val label = campusCode.ifBlank { "(不限校区)" }
            if (code != "0") {
                failCode = code
                logger("校区 $label 返回失败: code=$code")
                return
            }

            val detail = obj.optJSONObject("datas")?.optJSONObject("getMyScheduleDetail")
            if (detail == null) {
                failCode = "缺少 getMyScheduleDetail"
                logger("校区 $label 响应里没有 getMyScheduleDetail")
                return
            }

            okQueries++
            if (subTitles == null) subTitles = detail.optJSONArray("subTitles")
            if (mergeArrangedSample == null) {
                mergeArrangedSample = detail.optJSONArray("mergeArrangedList")
            }

            val list = detail.optJSONArray("arrangedList") ?: return
            (0 until list.length()).forEach { i ->
                mergedCourses.add(list.getJSONObject(i))
            }
        }

        if (campusCodes.isEmpty()) {
            queryCampus("")
        } else {
            campusCodes.forEach { queryCampus(it) }
        }

        // ④ 各校区都空、但接口本身是通的 → 再不带 XQDM 兜底查一次
        if (mergedCourses.isEmpty() && okQueries > 0 && campusCodes.isNotEmpty()) {
            logger("各校区均无课程，改为不带 XQDM 再查一次")
            runCatching { queryCampus("") }
                .onFailure { logger("不带 XQDM 查询失败: ${it.message}") }
        }

        logger("总共拉取 ${mergedCourses.size} 条原始课程")

        // 只记条数，先判断「到底有没有数据」，有再谈怎么用
        val mergeCount = mergeArrangedSample?.length() ?: 0
        logger("mergeArrangedList 条数=$mergeCount（当前未参与解析，仅取证）")

        // ⑤ 一次都没成功说明是接口/参数问题，不能伪装成「本学期没课」
        if (okQueries == 0) {
            throw AuthException("课表接口返回失败（code=$failCode），请稍后重试")
        }

        // 注意：接口成功但课程为空是**正常情况**（新生未排课、大四没课）。
        // 以前这里抛 AuthException("...登录已过期")，被上层按「登录失效」处理，
        // 直接把用户的登录态清掉 —— 这就是「登得上却拿不到课表、还被踢回登录页」的来源。
        if (mergedCourses.isEmpty()) {
            logger("接口成功但本学期没有排课")
        }

        // ⑥ 重新组装成完整 JSON，交给已有的 ScheduleParser 解析
        val rebuilt = org.json.JSONObject().apply {
            put("code", "0")
            put("datas", org.json.JSONObject().apply {
                put("getMyScheduleDetail", org.json.JSONObject().apply {
                    put("subTitles", subTitles ?: org.json.JSONArray())
                    put("arrangedList", org.json.JSONArray(mergedCourses))
                    // 原样带出去（以前是空的）：落盘后能直接对比它和 arrangedList
                    // 差在哪，不用再猜「多段安排被谁吃了」
                    put("mergeArrangedList", mergeArrangedSample ?: org.json.JSONArray())
                    put("termName", termName)
                })
            })
        }

        // 落盘原始数据：课表「少一门课」这种事，只有拿到真实 JSON 才能精确复现，
        // 否则只能靠猜服务端字段格式。点「诊断日志」导出时会一起带走。
        AppLog.dump("schedule_raw_$actualTermCode", rebuilt.toString(2))

        val termWeeks = getTermWeeks(actualTermCode)
        // 周历单独落盘。这门课「只在 9-10 周」和「教务网页今天有课」互相矛盾，
        // 症结很可能在「今天是第几周」上 —— 这份对应关系是唯一的裁判。
        if (termWeeks.isNotEmpty()) {
            AppLog.dump(
                "term_weeks_$actualTermCode",
                termWeeks.joinToString("\n") { "第${it.weekNumber}周  ${it.startDate} ~ ${it.endDate}" }
            )
        }

        return ScheduleParser.parse(rebuilt.toString(), actualTermCode)
            .copy(termWeeks = termWeeks)
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

/**
 * 从 getMyScheduledCampus.do 的响应里提取校区代码。
 *
 * 正方这套接口的返回包法并不统一，至少见过两种：
 *   {"code":"0","datas":[{"id":"10","name":"沙河校区"}]}
 *   {"code":"0","datas":{"rows":[{"XQDM":"10"}]}}
 * 两种都认；认不出来就返回空列表，**绝不猜一个默认校区** ——
 * 猜成 10（沙河）正是「别的校区学生永远拉不到课表」的根源。
 */
internal fun parseCampusIds(rawJson: String): List<String> {
    val obj = runCatching { org.json.JSONObject(rawJson) }.getOrNull() ?: return emptyList()

    val candidates = buildList {
        obj.optJSONArray("datas")?.let { add(it) }
        obj.optJSONObject("datas")?.let { datas ->
            datas.optJSONArray("rows")?.let { add(it) }
            datas.optJSONArray("datas")?.let { add(it) }
        }
    }

    val arr = candidates.firstOrNull { it.length() > 0 } ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        val row = arr.optJSONObject(i) ?: return@mapNotNull null
        listOf("XQDM", "xqdm", "id", "code", "dqdm")
            .firstNotNullOfOrNull { key -> row.optString(key).takeIf { it.isNotBlank() } }
    }.distinct()
}
