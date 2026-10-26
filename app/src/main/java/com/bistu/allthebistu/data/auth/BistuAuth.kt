package com.bistu.allthebistu.data.auth

import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.net.RetryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Security
import java.security.spec.ECFieldFp
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.EllipticCurve
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import com.tencent.kona.crypto.KonaCryptoProvider

/**
 * 北信科 SSO 登录（CAS 变体 + SM2 国密加密）。
 *
 * 流程（参考 iBistu 开源实现）：
 *  1. GET  /api/reset/rules      → 拿 SM2 公钥 + 建立 session
 *  2. POST /info-query           → 查验证码/MFA
 *  3. SM2 加密密码
 *  4. POST /username-password/login → 拿 TGC Cookie
 *  5. GET  /login?service=xxx    → 302 拿 ST ticket
 *  6. 带 ticket 访问教务系统 casLogin.do → 建立 session
 */
class BistuAuth(private val logger: (String) -> Unit = { AppLog.d(TAG, it) }) {

    companion object {
        private const val TAG = "BistuAuth"

        const val SSO_BASE = "https://sso.bistu.edu.cn"
        const val UC_SERVICE =
            "https://uc.bistu.edu.cn/api/login?target=https://uc.bistu.edu.cn/user/login"

        const val JWXT_BASE = "https://jwxt.bistu.edu.cn"

        /** 教务系统域名：判断「请求有没有被踢回统一身份认证」就靠它 */
        const val JWXT_HOST = "jwxt.bistu.edu.cn"

        /** 教务系统 CAS 登录端点 */
        const val JWXT_CAS_LOGIN =
            "$JWXT_BASE/jwapp/sys/yjsrzfwapp/bistuLogin/casLogin.do"

        /** 登录成功码（学校自定义） */
        const val SUCCESS_CODE = 666666

        /** 180040：Flowkey未找到 —— flowKey 为空，或不属于当前 SSO 会话 */
        const val CODE_FLOWKEY_MISSING = 180040

        /** 170002：用户名或密码错误 */
        const val CODE_WRONG_PASSWORD = 170002

        /** 600901：没找到 TGC（SSO 侧无登录态） */
        const val CODE_NO_TGC = 600901

        /** 建立会话（拿 flowKey）的最大尝试次数 */
        private const val FLOWKEY_MAX_ATTEMPTS = 3

        /** 每次重试间隔 */
        private const val FLOWKEY_RETRY_DELAY_MS = 400L

        /** 登录遇到 180040 后，最多重建几次会话重试 */
        private const val LOGIN_MAX_RETRIES = 2

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        init {
            // 注册 Kona 加密提供者
            runCatching {
                if (Security.getProvider("KonaCrypto") == null) {
                    Security.addProvider(KonaCryptoProvider())
                }
            }
        }
    }

    // ── Cookie 管理 ──
    private val cookieStore = mutableListOf<Cookie>()

    /**
     * 直接从 Set-Cookie 头解析出来的 flowKey。
     *
     * 不能只靠 cookieStore：sso / uc / jwxt 三个域会下发同名 Cookie，
     * CookieJar 去重时可能把 COOKIE_INFO 顶掉，导致读出来是空。
     */
    private var cachedFlowKey: String = ""

    /**
     * Cookie 发生变化时的回调（在写入 CookieJar 的那个线程上调）。
     *
     * 存在的理由：换票（[casLoginDetailed]）会从 Set-Cookie 拿到**新的**
     * 教务会话，但以前这份新会话只留在内存里，只有「同步成功」后才跟着
     * [AuthHolder.persist] 一起落盘。于是出现这条很隐蔽的路径：
     *
     *   冷启动恢复旧 Cookie → 探活失败（服务端会话早废了）→ 换票成功
     *   → 用户这时杀掉进程 / 同步恰好失败 → 新会话丢失
     *   → 下次冷启动又拿旧 Cookie 重新探活、重新换票
     *
     * 表现出来就是「过一会儿重进要转好久」「有时刷不出来」。
     * 挂上回调后，任何一个 Set-Cookie 都会立刻触发落盘。
     */
    var onCookiesChanged: (() -> Unit)? = null

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(cookieStore) { cookieStore.filter { it.matches(url) } }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            synchronized(cookieStore) {
                cookies.forEach { newCookie ->
                    // 同名但不同域/路径的 Cookie 必须共存：
                    // 早期只按 name 去重，sso 的 JSESSIONID 会被 jwxt 的顶掉，会话直接断
                    cookieStore.removeAll {
                        it.name == newCookie.name &&
                                it.domain == newCookie.domain &&
                                it.path == newCookie.path
                    }
                    cookieStore.add(newCookie)
                }
            }
            // 回调放锁外：落盘是 IO，不要抱着 cookieStore 的锁做
            notifyCookiesChanged()
        }
    }

    /** 安全地触发变更回调：回调里抛异常绝不能影响正常的网络请求 */
    private fun notifyCookiesChanged() {
        runCatching { onCookiesChanged?.invoke() }
            .onFailure { logger("onCookiesChanged 回调异常: ${it.message}") }
    }

    /** 跟随重定向的客户端（用于 CAS 跳转链） */
    val redirectClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 不跟随重定向的客户端（用于手动处理 302 里的 ticket） */
    val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 取指定名称的 Cookie */
    fun getCookie(name: String): String? =
        cookieStore.firstOrNull { it.name == name }?.value

    /**
     * 取 SSO 域下的 Cookie。
     *
     * COOKIE_INFO / TGC 只有 sso.bistu.edu.cn 下发的才算数：
     * CookieJar 里可能同时存在 uc / jwxt 域的同名条目，
     * [getCookie] 按插入顺序取第一个，很容易取到别的域上的旧值。
     */
    fun getSsoCookie(name: String): String? =
        cookieStore.lastOrNull { it.name == name && it.domain.endsWith("sso.bistu.edu.cn") }?.value
            ?: getCookie(name)

    /**
     * 从 COOKIE_INFO cookie 解析 flowKey。
     *
     * 学校 SSO 的会话标识，登录和信息查询都必须带上。
     * COOKIE_INFO 是 URL 编码的 JSON，形如 {"data":{"flowKey":"xxx"}}
     *
     * ⚠️ 必须以 CookieJar 里的**当前值**为准，不能先看缓存：
     * 服务端会在 `info-query`、登录失败等时刻换发/作废 COOKIE_INFO，
     * 一旦拿着缓存里的旧 flowKey 去登录，服务端只会回 180040 Flowkey未找到。
     * （这就是「别人的账号一登就 180040」的直接原因。）
     */
    fun getFlowKey(): String {
        // ① 实时值优先：只要 jar 里有 COOKIE_INFO，就以它为准（哪怕解析为空）
        val raw = getSsoCookie("COOKIE_INFO")
        val key = resolveFlowKey(raw, cachedFlowKey)

        if (raw != null && key != cachedFlowKey) {
            logger(
                "getFlowKey: 缓存${if (cachedFlowKey.isBlank()) "为空" else "已过期"}，" +
                        "改用当前 COOKIE_INFO（${if (key.isBlank()) "解析为空" else "${key.length} 字符"}）"
            )
        } else if (raw == null && cachedFlowKey.isNotBlank()) {
            logger("getFlowKey: jar 无 COOKIE_INFO，使用缓存 (${cachedFlowKey.length} 字符)")
        } else if (key.isBlank()) {
            logger("getFlowKey: COOKIE_INFO 不存在或解析为空")
        }

        cachedFlowKey = key
        return key
    }

    /**
     * 从响应头同步 flowKey。
     *
     * SSO 换发会话时会重新下发 COOKIE_INFO，必须当场跟上，
     * 否则下一次请求（登录）带的就是上一轮的 flowKey → 180040。
     */
    private fun syncFlowKeyFromHeaders(setCookies: List<String>) {
        val fresh = parseFlowKeyFromSetCookies(setCookies)
        if (fresh.isBlank()) return
        if (fresh != cachedFlowKey) {
            logger("flowKey 已随响应刷新 (${fresh.length} 字符)")
            cachedFlowKey = fresh
        }
    }

    /**
     * 确保拿到可用的 flowKey —— 登录前的守门人。
     *
     * 为什么必须有这一步：
     *  - `/api/reset/rules` 是**无状态**接口，没有会话也照样 200 返回公钥；
     *  - 所以「会话没建立」不会在这里暴露，只能等登录接口回 180040；
     *  - 180040 的唯一含义就是 flowKey 为空或不属于当前会话。
     *
     * 处网络抖动外，常见原因是校园网未完成上网认证、或挂了 VPN 被劫持。
     */
    suspend fun ensureFlowKey(force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!force) {
            val existing = getFlowKey()
            if (existing.isNotBlank()) {
                logger("ensureFlowKey: 复用已有 flowKey (${existing.length} 字符)")
                return@withContext existing
            }
        }

        repeat(FLOWKEY_MAX_ATTEMPTS) { attempt ->
            // 重建会话前先扔掉旧的 COOKIE_INFO：
            // 它属于上一个会话，带着它去 GET /login 只会让新会话被旧会话污染，
            // 拿回来的 flowKey 依旧对不上 → 还是 180040
            cookieStore.removeAll { it.name == "COOKIE_INFO" }
            cachedFlowKey = ""
            val encodedService = java.net.URLEncoder.encode(UC_SERVICE, "UTF-8")
            val initUrl = "$SSO_BASE/login?service=$encodedService"
            logger("ensureFlowKey: 第 ${attempt + 1} 次建立会话")

            val key = runCatching {
                // 用不跟随重定向的 client：302 响应本身就带齐 JSESSIONID + COOKIE_INFO
                client.newCall(Request.Builder().url(initUrl).get().build()).execute().use { resp ->
                    val setCookies = resp.headers.values("Set-Cookie")
                    logger("ensureFlowKey: HTTP ${resp.code}, Set-Cookie ${setCookies.size} 条")
                    val fromHeader = parseFlowKeyFromSetCookies(setCookies)
                    if (fromHeader.isNotBlank()) fromHeader
                    else parseFlowKeyValue(getSsoCookie("COOKIE_INFO").orEmpty())
                }
            }.getOrElse {
                logger("ensureFlowKey: 请求异常 ${it.javaClass.simpleName}: ${it.message}")
                ""
            }

            if (key.isNotBlank()) {
                cachedFlowKey = key
                logger("ensureFlowKey: 成功（第 ${attempt + 1} 次，${key.length} 字符）")
                return@withContext key
            }

            logger("ensureFlowKey: 第 ${attempt + 1} 次没拿到 flowKey")
            if (attempt < FLOWKEY_MAX_ATTEMPTS - 1) delay(FLOWKEY_RETRY_DELAY_MS)
        }

        throw AuthException(
            "无法建立统一身份认证会话（flowKey 为空）。\n" +
                    "请依次检查：\n" +
                    "1. 网络是否通畅；连校园网时先完成上网认证；\n" +
                    "2. 关闭 VPN / 代理后重试；\n" +
                    "3. 稍后再试，或换一个网络（如手机流量）。"
        )
    }

    /** 取所有 Cookie（拼成请求头格式） */
    fun getAllCookiesHeader(url: String = "https://jwxt.bistu.edu.cn"): String {
        // 先尝试用 matches 精确匹配
        val httpUrl = url.toHttpUrlOrNull()
        val matched = if (httpUrl != null) {
            cookieStore.filter { it.matches(httpUrl) }
        } else emptyList()

        // 如果匹配到的太少，退化为「全部发送」
        // 原因：CAS 跳转后 Cookie 可能挂在 sso 域名下，但 jwxt 接口也需要
        val useList = if (matched.size >= 3) matched else cookieStore

        val header = useList.joinToString("; ") { "${it.name}=${it.value}" }
        logger(
            "getAllCookiesHeader: 全部=${cookieStore.size}, 匹配=${matched.size}, " +
                    "使用=${useList.size}, 长度=${header.length}"
        )
        logger("Cookie 名称: ${cookieStore.joinToString(", ") { it.name }}")
        return header
    }

    /** 获取所有 Cookie 的详细信息（调试用） */
    fun dumpCookies(): String {
        return cookieStore.joinToString("\n") { c ->
            "  ${c.name} = ${c.value.take(40)} (domain=${c.domain}, path=${c.path})"
        }
    }

    /** 是否已登录（有 TGC 或 JSESSIONID） */
    fun isLoggedIn(): Boolean =
        getCookie("TGC") != null || getCookie("JSESSIONID") != null

    // ══════════════════════════════════════════════════════════
    // 步骤 1：获取 SM2 公钥
    // ══════════════════════════════════════════════════════════
    suspend fun getPublicKey(): String = withContext(Dispatchers.IO) {
        logger("getPublicKey: 开始")

        // ① 先建立 SSO 会话并拿到 flowKey。
        //    这一步以前是 runCatching 静默执行的，失败也不会中断，
        //    结果公钥照样拿到、登录时 flowKey 却是空的 → 180040。
        val flowKey = ensureFlowKey()
        logger("getPublicKey: 会话就绪，flowKey ${flowKey.length} 字符")

        // ② 请求公钥（该接口无状态，不能拿它的成功与否判断会话是否建立）
        val req = Request.Builder().url("$SSO_BASE/api/reset/rules").get().build()
        val body = client.newCall(req).execute().use { resp ->
            logger("getPublicKey: rules HTTP ${resp.code}")
            if (!resp.isSuccessful) throw AuthException("获取公钥失败: HTTP ${resp.code}")
            resp.body?.string() ?: throw AuthException("公钥响应为空")
        }

        logger("getPublicKey: rules 响应 = ${body.take(300)}")

        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw AuthException("获取公钥失败: $body")
        }

        val publicKey = json.getJSONObject("data")
            .getJSONObject("encrypt")
            .getString("publicKey")

        logger("getPublicKey: 成功，公钥长度 ${publicKey.length}")
        publicKey
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 2：查询是否需要验证码 / MFA
    // ══════════════════════════════════════════════════════════
    suspend fun infoQuery(): JSONObject = withContext(Dispatchers.IO) {
        val flowKey = getFlowKey()
        logger("infoQuery: flowKey=${if (flowKey.isBlank()) "(空)" else "${flowKey.length}字符"}")

        // 关键：必须是 JSON body，且带 flowKey
        val body = JSONObject().apply {
            put("username", "")
            put("flowKey", flowKey)
        }

        val req = Request.Builder()
            .url("$SSO_BASE/info-query")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val respBody = client.newCall(req).execute().use { resp ->
            logger("infoQuery: HTTP ${resp.code}")
            // 这一步服务端可能换发 COOKIE_INFO，必须当场同步，
            // 否则后面登录带的还是旧 flowKey
            syncFlowKeyFromHeaders(resp.headers.values("Set-Cookie"))
            resp.body?.string() ?: "{}"
        }

        logger("infoQuery: ${respBody.take(200)}")
        runCatching { JSONObject(respBody) }.getOrElse { JSONObject() }
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 3：SM2 加密密码
    // ══════════════════════════════════════════════════════════
    fun encryptPassword(password: String, publicKeyBase64: String): String {
        val publicKey = parsePublicKey(publicKeyBase64)
        val cipher = Cipher.getInstance("SM2", "KonaCrypto")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val asn1Output = cipher.doFinal(password.toByteArray(Charsets.UTF_8))

        // 尝试转成 C1C3C2 原始格式（学校要求）
        val rawOutput = runCatching { derToRawC1C3C2(asn1Output) }
            .getOrElse { asn1Output }

        return Base64.getEncoder().encodeToString(rawOutput)
    }

    /** 解析 Base64 编码的 SM2 公钥 */
    private fun parsePublicKey(base64Key: String): java.security.PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)

        // SM2 曲线参数（国密标准）
        val p = java.math.BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFF", 16)
        val a = java.math.BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000FFFFFFFFFFFFFFFC", 16)
        val b = java.math.BigInteger("28E9FA9E9D9F5E344D5A9E4BCF6509A7F39789F515AB8F92DDBCBD414D940E93", 16)
        val gx = java.math.BigInteger("32C4AE2C1F1981195F9904466A39C9948FE30BBFF2660BE1715A4589334C74C7", 16)
        val gy = java.math.BigInteger("BC3736A2F4F6779C59BDCEE36B692153D0A9877CC62A474002DF32E52139F0A0", 16)
        val n = java.math.BigInteger("FFFFFFFEFFFFFFFFFFFFFFFFFFFFFFFF7203DF6B21C6052B53BBF40939D54123", 16)

        val curve = EllipticCurve(ECFieldFp(p), a, b)
        val g = ECPoint(gx, gy)
        val params = ECParameterSpec(curve, g, n, 1)

        // 公钥是未压缩格式：04 || X(32字节) || Y(32字节)
        if (keyBytes.size != 65 || keyBytes[0].toInt() != 0x04) {
            throw AuthException("公钥格式错误: 长度=${keyBytes.size}")
        }

        val x = java.math.BigInteger(1, keyBytes.copyOfRange(1, 33))
        val y = java.math.BigInteger(1, keyBytes.copyOfRange(33, 65))
        val point = ECPoint(x, y)

        val keySpec = ECPublicKeySpec(point, params)
        val kf = KeyFactory.getInstance("EC", "KonaCrypto")
        return kf.generatePublic(keySpec)
    }

    /** ASN.1 DER → C1C3C2 原始格式 */
    private fun derToRawC1C3C2(der: ByteArray): ByteArray {
        // SEQUENCE { INTEGER x, INTEGER y, OCTET STRING c3, OCTET STRING c2 }
        var pos = 0

        fun readLength(): Int {
            var len = der[pos++].toInt() and 0xFF
            if (len and 0x80 != 0) {
                val numBytes = len and 0x7F
                len = 0
                repeat(numBytes) { len = (len shl 8) or (der[pos++].toInt() and 0xFF) }
            }
            return len
        }

        // 跳过 SEQUENCE 头
        if (der[pos++].toInt() and 0xFF != 0x30) throw AuthException("DER 格式错误")
        readLength()

        // 读 x
        if (der[pos++].toInt() and 0xFF != 0x02) throw AuthException("DER 缺少 x")
        val xLen = readLength()
        val xBytes = der.copyOfRange(pos, pos + xLen).let {
            // 去掉前导 0
            if (it.size > 32 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        pos += xLen

        // 读 y
        if (der[pos++].toInt() and 0xFF != 0x02) throw AuthException("DER 缺少 y")
        val yLen = readLength()
        val yBytes = der.copyOfRange(pos, pos + yLen).let {
            if (it.size > 32 && it[0].toInt() == 0) it.copyOfRange(1, it.size) else it
        }
        pos += yLen

        // 读 c3
        if (der[pos++].toInt() and 0xFF != 0x04) throw AuthException("DER 缺少 c3")
        val c3Len = readLength()
        val c3Bytes = der.copyOfRange(pos, pos + c3Len)
        pos += c3Len

        // 读 c2
        if (der[pos++].toInt() and 0xFF != 0x04) throw AuthException("DER 缺少 c2")
        val c2Len = readLength()
        val c2Bytes = der.copyOfRange(pos, pos + c2Len)

        // C1C3C2 格式：04 || X || Y || C3 || C2
        val out = ByteArray(1 + 32 + 32 + c3Len + c2Len)
        out[0] = 0x04
        System.arraycopy(xBytes, 0, out, 1 + (32 - xBytes.size), xBytes.size)
        System.arraycopy(yBytes, 0, out, 33 + (32 - yBytes.size), yBytes.size)
        System.arraycopy(c3Bytes, 0, out, 65, c3Len)
        System.arraycopy(c2Bytes, 0, out, 65 + c3Len, c2Len)
        return out
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 4：提交登录
    // ══════════════════════════════════════════════════════════

    /**
     * 提交登录。
     *
     * 180040（Flowkey未找到）会自动重建一次会话后重试 ——
     * 这是最容易踩的坑：用户手上的旧 JSESSIONID 早就过期，
     * 但 CookieJar 里还留着一条过期的 COOKIE_INFO，读出来不为空，
     * 于是一直拿着不属于当前会话的 flowKey 去登录。
     */
    suspend fun login(
        username: String,
        password: String,
        publicKey: String
    ): LoginResult {
        var result = submitLogin(username, password, publicKey)
        var key = publicKey

        // 180040 说明「本次请求带的 flowKey 不属于当前 SSO 会话」。
        // 唯一可靠的解法是把会话整个丢掉重建：换发公钥也算重建的一部分，
        // 因为公钥和 flowKey 必须来自同一个会话。
        var attempt = 0
        while (result.code == CODE_FLOWKEY_MISSING && attempt < LOGIN_MAX_RETRIES) {
            attempt++
            logger("login: 180040 Flowkey未找到 → 丢弃旧会话重建（第 $attempt 次）")
            clearCookies()
            key = runCatching { getPublicKey() }.getOrElse {
                logger("login: 重建会话失败: ${it.message}")
                return result
            }
            result = submitLogin(username, password, key)
            logger("login: 第 $attempt 次重试结果 code=${result.code}")
        }
        return result
    }

    private suspend fun submitLogin(
        username: String,
        password: String,
        publicKey: String
    ): LoginResult = withContext(Dispatchers.IO) {
        logger("login: 开始 username=$username")

        val encrypted = encryptPassword(password, publicKey)
        logger("login: 密码已加密，长度 ${encrypted.length}")

        val flowKey = getFlowKey()
        logger("login: flowKey=${if (flowKey.isBlank()) "(空)" else "${flowKey.length}字符"}")
        if (flowKey.isBlank()) {
            // 直接不发：空 flowKey 必然 180040，发出去只是浪费一次请求
            logger("login: flowKey 为空，中止提交")
            return@withContext LoginResult(
                code = CODE_FLOWKEY_MISSING,
                message = "Flowkey未找到（本地未建立 SSO 会话）",
                rawJson = JSONObject(),
                local = true
            )
        }

        // 关键：必须是 JSON body，且带 flowKey
        val body = JSONObject().apply {
            put("flowKey", flowKey)
            put("username", username)
            put("password", encrypted)
        }

        val req = Request.Builder()
            .url("$SSO_BASE/username-password/login")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        val respBody = client.newCall(req).execute().use { resp ->
            logger("login: HTTP ${resp.code}")
            syncFlowKeyFromHeaders(resp.headers.values("Set-Cookie"))
            resp.body?.string() ?: ""
        }

        val json = runCatching { JSONObject(respBody) }.getOrElse { JSONObject() }
        val code = json.optInt("code", -1)
        val message = json.optString("msg", "")

        logger("login: code=$code msg=$message")
        if (code != SUCCESS_CODE) {
            logger("login: 失败响应全文: ${respBody.take(300)}")
        }

        LoginResult(
            code = code,
            message = message,
            rawJson = json
        )
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 5：CAS 登录教务系统（用 TGC 换 session）
    // ══════════════════════════════════════════════════════════
    suspend fun casLoginDetailed(casLoginUrl: String = JWXT_CAS_LOGIN): CasResult =
        withContext(Dispatchers.IO) {
            logger("casLogin: 开始 $casLoginUrl")

            val encodedService = java.net.URLEncoder.encode(casLoginUrl, "UTF-8")
            val url = "$SSO_BASE/login?service=$encodedService"

            val resp = runCatching {
                redirectClient.newCall(Request.Builder().url(url).get().build()).execute()
            }.getOrElse {
                // 连 sso 都请求不出去：纯网络问题，登录态不能动
                logger("casLogin: 请求异常 ${it.message}")
                return@withContext CasResult.Unreachable
            }

            // 跟完 302 之后，request.url 才是真实落点
            val finalUrl = resp.request.url
            val finalHost = finalUrl.host
            val httpCode = resp.code
            resp.close()

            logger(
                "casLogin: 最终落点=$finalUrl ($httpCode), " +
                        "TGC=${if (getSsoCookie("TGC").isNullOrBlank()) "无" else "有"}"
            )

            // 落在教务才需要探活；其余落点探活结果没意义
            val probeOk = if (finalHost == JWXT_HOST) probeJwxt() else false
            val result = resolveCasLanding(finalHost, probeOk)
            logger(
                "casLogin: 落点=$finalHost, 探活=$probeOk → $result, " +
                        "Cookie 数=${cookieStore.size}"
            )
            result
        }

    /** 只要「换到票且教务认账」这一个结论，见 [casLoginDetailed] */
    suspend fun casLogin(casLoginUrl: String = JWXT_CAS_LOGIN): Boolean =
        casLoginDetailed(casLoginUrl) == CasResult.Ok

    /**
     * 探活：拿教务模块入口试一次。
     *
     * 只认「最终落在 jwxt 域 + HTTP 成功 + 不是统一身份认证登录页」。
     * 返回 false 基本等于「这台设备现在进不了教务系统」——
     * 校外没连校园网时就是这结果，属正常现象，不该清登录态。
     */
    private suspend fun probeJwxt(): Boolean = withContext(Dispatchers.IO) {
        val startAt = System.currentTimeMillis()
        var attempt = 1
        var answer = false
        while (true) {
            val result = runCatching {
                redirectClient.newCall(
                    Request.Builder()
                        .url(JwxtApi.ENTRY_SCHEDULE)
                        // 必须带 UA：教务前面有 WAF，空 UA 会被直接拦成 403，
                        // 那就把「校内正常」也误判成「教务连不上」了
                        .addHeader("User-Agent", JwxtApi.UA)
                        .get()
                        .build()
                ).execute().use { r ->
                    val host = r.request.url.host
                    val body = r.body?.string().orEmpty()
                    val loginPage = body.contains("统一身份认证") || body.contains("CASTGC")
                    host == JWXT_HOST && r.isSuccessful && !loginPage
                }
            }

            val value = result.getOrNull()
            if (value != null) {
                answer = value
                break
            }

            // 探活失败若是因为网络抖动（含教务只发叶子证书导致的随机握手失败），
            // 直接判「进不去教务」会让上层显示「校外访问」，把排查带偏。重试几次。
            val error = result.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - startAt
            if (error != null && RetryPolicy.shouldRetry(error, attempt, elapsed)) {
                val wait = RetryPolicy.delayFor(attempt)
                logger(
                    "casLogin: 探活第 $attempt 次异常（${error.javaClass.simpleName}: ${error.message}），" +
                            "${wait}ms 后重试"
                )
                delay(wait)
                attempt++
                continue
            }
            error?.let { logger("casLogin: 探活异常 ${it.message}") }
            answer = false
            break
        }
        answer
    }

    /**
     * 教务系统（jwxt 域）是否真的有会话。
     *
     * 必须按域判断：CookieJar 里同时存在 sso / uc / jwxt 三个域的 JSESSIONID，
     * 用 `getCookie("JSESSIONID")` 取第一个，命中的往往是 sso 的。
     */
    fun hasJwxtSession(): Boolean = synchronized(cookieStore) {
        // 正方新版（jwapp）用 GS_SESSIONID，老版才是 JSESSIONID。
        // 只认 JSESSIONID 会一直报 `jwxt会话=false`，日志看着像没登录上，实际是好的。
        //
        // ⚠️ 这里**不能用** Cookie.matches(url)：它连 path 一起校验，
        // 而 jwxt 的会话 Cookie 作用域是 path=/jwapp/，拿根路径去 matches 永远 false。
        cookieStore.any { isSessionCookieName(it.name) && cookieDomainCovers(it.domain, JWXT_HOST) }
    }

    // ══════════════════════════════════════════════════════════
    // 完整登录流程
    // ══════════════════════════════════════════════════════════
    suspend fun fullLogin(username: String, password: String): LoginResult {
        logger("=== fullLogin 开始 ===")

        // 0️⃣ 每次登录都从零建会话。
        //    残留的 COOKIE_INFO / TGC 属于上一个会话（上一个人、或上一次失败的尝试），
        //    拿它去登录，服务端只会回 180040 —— 这也是「换个人登录就失败」的主因。
        clearCookies()

        // ① 拿公钥（内部会先确保 SSO 会话已建立、flowKey 可用）
        val publicKey = try {
            getPublicKey()
        } catch (e: AuthException) {
            logger("=== fullLogin 中断: ${e.message} ===")
            return LoginResult(
                code = CODE_FLOWKEY_MISSING,
                message = e.message ?: "SSO 会话建立失败",
                rawJson = JSONObject()
            )
        }

        // ② 查验证码（失败不阻塞）
        runCatching { infoQuery() }

        // ③ 登录
        val result = login(username, password, publicKey)
        logger("=== fullLogin 结果: code=${result.code} ===")

        if (result.isSuccess) {
            // ④ CAS 登录教务系统（用 TGC 换票据，建 jwxt 域会话）
            val casOk = runCatching { casLogin() }.getOrElse {
                logger("casLogin 失败: ${it.message}")
                false
            }
            logger("=== fullLogin CAS 结果: $casOk ===")
            // 如实带出去：SSO 登录成功 ≠ 教务系统连得上。
            // 校外没连校园网时就是 casOk=false，但账号密码本身是对的。
            return result.copy(jwxtReady = casOk)
        }

        return result
    }

    /** 清空所有 Cookie */
    fun clearCookies() {
        synchronized(cookieStore) { cookieStore.clear() }
        cachedFlowKey = ""
        logger("clearCookies: 已清空")
        notifyCookiesChanged()
    }

    // ══════════════════════════════════════════════════════════
    // 会话持久化：导出 / 还原
    // ══════════════════════════════════════════════════════════

    /**
     * 诊断用：把教务会话「弄坏」，模拟服务端闲置过久已经作废的状态。
     *
     * 只改 jwxt 域的会话 Cookie，**不动 TGC** —— 这恰好就是
     * 「统一身份认证还好、教务会话已死」的状态，也正是
     * 「放一会儿再进就刷不出来」的真实前提。
     *
     * 之所以需要它：直接验证这个场景要真的把手机闲置到教务超时（半小时级），
     * 而连续快杀反而每次都给服务端会话续命，根本复现不了。
     * 弄坏之后立刻冷启动，就能确定「探活失败 → 换票 → 新会话落盘」这条链是否可靠。
     *
     * @return 被作废的 Cookie 个数（0 表示当前压根没有教务会话）
     */
    fun invalidateJwxtSession(): Int {
        var broken = 0
        synchronized(cookieStore) {
            // 先拷贝再改，避免边遍历边删除
            cookieStore.toList().forEach { c ->
                // 旧实现用 c.matches(jwxt)，而 jwxt 会话的 path 是 /jwapp/、
                // 用于匹配的 URL 是根路径，Cookie.matches 会连 path 一起判 → 永远 false，
                // 于是这个入口一次都没生效过（点下去只提示「当前没有教务会话」）。
                if (isSessionCookieName(c.name) && cookieDomainCovers(c.domain, JWXT_HOST)) {
                    cookieStore.remove(c)
                    // OkHttp 4 的 Cookie 没有 newBuilder()，只能照字段重建一个
                    val brokenCookie = Cookie.Builder()
                        .name(c.name)
                        .value("invalid-${System.currentTimeMillis()}")
                        .apply {
                            if (c.hostOnly) hostOnlyDomain(c.domain) else domain(c.domain)
                        }
                        .path(c.path)
                        .expiresAt(c.expiresAt)
                        .apply { if (c.secure) secure() }
                        .apply { if (c.httpOnly) httpOnly() }
                        .build()
                    cookieStore.add(brokenCookie)
                    broken++
                }
            }
        }
        logger("invalidateJwxtSession: 已作废 $broken 个教务会话 Cookie")
        notifyCookiesChanged()
        return broken
    }

    /**
     * 导出当前所有 Cookie 快照（用于落盘）。
     *
     * 加锁：写发生在 OkHttp 的网络线程，读可能来自 UI/WorkManager 线程，
     * 不同步的话 ArrayList 并发改会抛 ConcurrentModificationException。
     */
    fun exportCookies(): List<StoredCookie> = synchronized(cookieStore) {
        cookieStore.map { StoredCookie.from(it) }
    }

    /** 当前持有的 Cookie 数量（调试用） */
    fun cookieCount(): Int = cookieStore.size

    /**
     * 用快照还原 Cookie。
     *
     * 会先清空内存里的旧 Cookie，再逐个还原；
     * 已过期的条目自动跳过。返回实际还原成功的数量。
     */
    fun restoreCookies(stored: List<StoredCookie>): Int {
        synchronized(cookieStore) { cookieStore.clear() }
        cachedFlowKey = ""
        var restored = 0
        stored.forEach { s ->
            if (s.isExpired()) {
                logger("restoreCookies: 跳过已过期 ${s.name}")
                return@forEach
            }
            val cookie = s.toCookie()
            if (cookie == null) {
                logger("restoreCookies: 还原失败 ${s.name}")
            } else {
                synchronized(cookieStore) { cookieStore.add(cookie) }
                restored++
            }
        }
        logger("restoreCookies: 尝试 ${stored.size} 个，成功 $restored 个")
        return restored
    }
}

/** 登录结果 */
data class LoginResult(
    val code: Int,
    val message: String,
    val rawJson: JSONObject,
    /** true 表示请求压根没发出去（本机 flowKey 为空），而不是被服务端拒绝 */
    val local: Boolean = false,
    /**
     * 教务系统（jwxt）的会话是不是真的建起来了。
     *
     * SSO 登录成功（666666）只代表账号密码对了，不代表教务系统能访问：
     * 校外没连校园网时，教务会把人踢回统一身份认证，这里就是 false。
     * 这种情况**不该**报「登录失败」，而是「已登录，但教务连不上」。
     */
    val jwxtReady: Boolean = true
) {
    /** 学校用 666666 表示成功 */
    val isSuccess: Boolean get() = code == BistuAuth.SUCCESS_CODE

    /** 账号密码对了，但进不了教务系统（通常就是校外没连校园网） */
    val isLoggedInButNoJwxt: Boolean get() = isSuccess && !jwxtReady

    /** 会话/网络类问题（值得让用户先检查网络再重试，而不是以为密码错了） */
    val isSessionIssue: Boolean
        get() = code == BistuAuth.CODE_FLOWKEY_MISSING || code == BistuAuth.CODE_NO_TGC

    /** 给用户看的文案：服务端的 msg 有时太技术化，甚至为空 */
    val friendlyMessage: String
        get() = when (code) {
            BistuAuth.SUCCESS_CODE ->
                if (jwxtReady) "登录成功"
                else "已登录统一身份认证，但没连上教务系统。\n" +
                        "校外访问需要先连校园网（或学校 VPN）；连着校园网请先完成上网认证。"
            BistuAuth.CODE_WRONG_PASSWORD -> "学号或密码错误，请检查后重试"
            BistuAuth.CODE_FLOWKEY_MISSING ->
                if (local) {
                    "没能建立统一身份认证会话（flowKey 为空），登录请求没有发出。\n" +
                            "多半是网络被拦：连校园网请先完成上网认证，挂了 VPN/代理请先关闭。"
                } else {
                    "学校 SSO 拒绝了这个会话（180040 Flowkey未找到），已自动重建会话重试仍不成功。\n" +
                            "请退出到登录页重新登录一次；若只有某个账号一直失败、" +
                            "其他人的账号正常，说明该账号在统一身份认证侧本身异常，请先在浏览器里登录确认。"
                }
            BistuAuth.CODE_NO_TGC -> "登录状态已失效，请在浏览器里确认账号正常后重试"
            else -> message.ifBlank { "登录失败（code=$code）" }
        }
}

class AuthException(message: String) : Exception(message)

/**
 * 登录态**真的**失效了（HTTP 401/403，或接口直接返回了 SSO 登录页）。
 *
 * 单独分一个类型，是因为以前靠 `message.contains("登录")` 判断，
 * 把「没有获取到课程数据（可能登录已过期）」这种业务失败也算成掉登录，
 * 结果用户一进课表就被清掉 Cookie、踢回登录页。
 * 只有这个类型的异常才允许触发登出。
 */
class SessionExpiredException(message: String) : Exception(message)

/**
 * 请求被统一身份认证拦下了，教务系统的数据拿不到。
 *
 * 和 [SessionExpiredException] 的区别很关键：
 *  - SessionExpiredException：真的是会话过期了 → 应该清登录态，请用户重登
 *  - OffCampusException：网络路径走不通（绝大多数是校外没连校园网 / VPN），
 *    账号和会话都是好的 → **绝不能清登录态**，清了用户重登也一样进不去
 *
 * 判定依据不是猜文案，而是 OkHttp 跟完 302 之后的最终落点：
 * 被踢到 sso / uc 域，就说明这次请求根本没被教务系统接住。
 */
class OffCampusException(message: String) : Exception(message)

/**
 * CAS 换票的三种结论。
 *
 * 以前只回 true/false，上层没法区分「TGC 已经失效」和「这会儿网络/教务不通」，
 * 只能二选一：要么一律当过期（校外或教务抖动时把人误踢下线、清掉数据），
 * 要么一律当网络问题（TGC 真死了还一直显示「查询失败」，永远好不了）。
 * 这就是「用过一会儿就自己退出登录」「退出去重进又一直失败」两个现象的共同根源。
 */
sealed interface CasResult {
    /** 换到票，教务也认这个会话 */
    data object Ok : CasResult

    /** 被踢回统一身份认证首页：TGC 已失效，只能重新登录 */
    data object NoTicket : CasResult

    /** 压根没走到教务（校外 / WAF / 维护 / 超时）：登录态是好的，别清 */
    data object Unreachable : CasResult
}

/**
 * 换票落点 → 结论。抽成纯函数，单测可以直接覆盖「掉线」判定。
 *
 * 实测依据（2026-10，`curl -L` 无 TGC 请求 sso/login?service=...）：
 * 没有有效 TGC 时服务端 302 到 `https://sso.bistu.edu.cn/?timestamp=...&service=...`，
 * 即**统一身份认证首页**；有 TGC 时才会带着 ticket 落到 jwxt。
 * 所以「落在 sso 首页」是唯一能确定凭据真没了的信号，其余落点一律按网络问题处理。
 */
internal fun resolveCasLanding(finalHost: String, probeOk: Boolean): CasResult = when {
    finalHost == BistuAuth.JWXT_HOST -> if (probeOk) CasResult.Ok else CasResult.Unreachable
    finalHost.endsWith("sso.bistu.edu.cn") -> CasResult.NoTicket
    else -> CasResult.Unreachable
}

/**
 * 这个 Cookie 名是不是「某个系统的会话 ID」。
 *
 * 正方新版（jwapp）用 GS_SESSIONID，老版用 JSESSIONID；sso 域也有一个 JSESSIONID，
 * 所以必须连域名一起判，不能只按名字找。
 */
internal fun isSessionCookieName(name: String): Boolean =
    name == "JSESSIONID" || name == "GS_SESSIONID"

/**
 * Cookie 的 domain 是否覆盖 [host]。
 *
 * ⚠️ 判断「某个 Cookie 属于哪个站」**不要用** `Cookie.matches(url)`：
 * 它会把 **path** 一起校验，而教务的会话 Cookie 作用域是
 * `domain=jwxt.bistu.edu.cn, path=/jwapp/`，用 `https://jwxt.bistu.edu.cn/`
 * （path=/）去 matches 永远返回 false。
 *
 * 「诊断：模拟教务会话过期」点了提示「当前没有教务会话」就是这个原因：
 * 它拿 matches 找 Cookie，一个都没找到。同理 `hasJwxtSession()` 一直报 false，
 * 排查时看着像没登录上，实际会话是好的。
 *
 * 所以这里只比 domain（含 `.bistu.edu.cn` 这种父域写法），不看 path。
 */
internal fun cookieDomainCovers(domain: String, host: String): Boolean {
    val d = domain.removePrefix(".").lowercase()
    val h = host.lowercase()
    if (d.isEmpty()) return false
    return d == h || h.endsWith(".$d")
}

// ══════════════════════════════════════════════════════════════
// flowKey 解析（抽成顶层函数，便于单元测试）
//
// 180040「Flowkey未找到」的全部来源都在这两个函数里：
// 要么响应头里根本没有 COOKIE_INFO，要么它的值解析不出 flowKey。
// ══════════════════════════════════════════════════════════════

/**
 * 解析 COOKIE_INFO 的原始值（URL 编码的 JSON）→ flowKey。
 *
 * 形如 `%7B%22data%22%3A%7B%22flowKey%22%3A%22flow.abc%22%7D%7D`，
 * 注意是「一次」URL 编码，解码后才能当 JSON 读。
 */
internal fun parseFlowKeyValue(rawCookieValue: String): String {
    if (rawCookieValue.isBlank()) return ""
    return try {
        val decoded = java.net.URLDecoder.decode(rawCookieValue, "UTF-8")
        JSONObject(decoded).optJSONObject("data")?.optString("flowKey").orEmpty()
    } catch (e: Exception) {
        ""
    }
}

/**
 * 决定这次请求该带哪个 flowKey。
 *
 * 规则只有一条：**jar 里有 COOKIE_INFO 就以 jar 为准**，哪怕它解析出来是空的。
 * 因为服务端作废会话时会下发 `COOKIE_INFO=; Max-Age=0`，
 * 此时若还退回缓存里的旧 flowKey，登录必然 180040。
 * 缓存只在「jar 里压根没有这条 cookie」时兜底。
 *
 * @param rawCookieValue jar 中 COOKIE_INFO 的原始值；null 表示不存在
 * @param cachedFlowKey 上一次解析到的 flowKey
 */
internal fun resolveFlowKey(rawCookieValue: String?, cachedFlowKey: String): String =
    if (rawCookieValue != null) parseFlowKeyValue(rawCookieValue) else cachedFlowKey

/**
 * 从响应头里的 Set-Cookie 列表取出 COOKIE_INFO 的 flowKey。
 *
 * 值里可能含 `=`（URL 编码后一般不会，但别假设），所以只按第一个 `=` 切。
 * 找不到 COOKIE_INFO 或解析失败都返回空串，由调用方决定重试还是报错。
 */
internal fun parseFlowKeyFromSetCookies(setCookies: List<String>): String {
    setCookies.forEach { line ->
        val pair = line.substringBefore(';')
        if (pair.substringBefore('=').trim() == "COOKIE_INFO") {
            return parseFlowKeyValue(pair.substringAfter('=', "").trim())
        }
    }
    return ""
}
