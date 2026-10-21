package com.example.study1.data.auth

import android.util.Log
import kotlinx.coroutines.Dispatchers
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
class BistuAuth(private val logger: (String) -> Unit = { Log.d(TAG, it) }) {

    companion object {
        private const val TAG = "BistuAuth"

        const val SSO_BASE = "https://sso.bistu.edu.cn"
        const val UC_SERVICE =
            "https://uc.bistu.edu.cn/api/login?target=https://uc.bistu.edu.cn/user/login"

        /** 教务系统 CAS 登录端点 */
        const val JWXT_CAS_LOGIN =
            "https://jwxt.bistu.edu.cn/jwapp/sys/yjsrzfwapp/bistuLogin/casLogin.do"

        /** 登录成功码（学校自定义） */
        const val SUCCESS_CODE = 666666

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

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookieStore.filter { it.matches(url) }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { newCookie ->
                cookieStore.removeAll { it.name == newCookie.name }
                cookieStore.add(newCookie)
            }
        }
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

    /** 取所有 Cookie（拼成请求头格式） */
    fun getAllCookiesHeader(url: String = "https://jwxt.bistu.edu.cn"): String {
        val httpUrl = url.toHttpUrlOrNull() ?: return ""
        return cookieStore
            .filter { it.matches(httpUrl) }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    /** 是否已登录（有 TGC 或 JSESSIONID） */
    fun isLoggedIn(): Boolean =
        getCookie("TGC") != null || getCookie("JSESSIONID") != null

    // ══════════════════════════════════════════════════════════
    // 步骤 1：获取 SM2 公钥
    // ══════════════════════════════════════════════════════════
    suspend fun getPublicKey(): String = withContext(Dispatchers.IO) {
        logger("getPublicKey: 开始")

        // 先访问登录入口建立 session
        val encodedService = java.net.URLEncoder.encode(UC_SERVICE, "UTF-8")
        val initUrl = "$SSO_BASE/login?service=$encodedService"
        runCatching {
            redirectClient.newCall(
                Request.Builder().url(initUrl).get().build()
            ).execute().close()
        }

        // 请求公钥
        val req = Request.Builder().url("$SSO_BASE/api/reset/rules").get().build()
        val body = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AuthException("获取公钥失败: HTTP ${resp.code}")
            resp.body?.string() ?: throw AuthException("公钥响应为空")
        }

        val json = JSONObject(body)
        if (json.optInt("code") != 200) {
            throw AuthException("获取公钥失败: $body")
        }

        val publicKey = json.getJSONObject("data")
            .getJSONObject("encrypt")
            .getString("publicKey")

        logger("getPublicKey: 成功，长度 ${publicKey.length}")
        publicKey
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 2：查询是否需要验证码 / MFA
    // ══════════════════════════════════════════════════════════
    suspend fun infoQuery(): JSONObject = withContext(Dispatchers.IO) {
        logger("infoQuery: 开始")

        val req = Request.Builder()
            .url("$SSO_BASE/info-query")
            .post("{}".toRequestBody(JSON_MEDIA))
            .build()

        val body = client.newCall(req).execute().use { resp ->
            resp.body?.string() ?: "{}"
        }

        logger("infoQuery: ${body.take(200)}")
        runCatching { JSONObject(body) }.getOrElse { JSONObject() }
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
    suspend fun login(
        username: String,
        password: String,
        publicKey: String
    ): LoginResult = withContext(Dispatchers.IO) {
        logger("login: 开始 username=$username")

        val encrypted = encryptPassword(password, publicKey)
        logger("login: 密码已加密，长度 ${encrypted.length}")

        val body = FormBody.Builder()
            .add("username", username)
            .add("password", encrypted)
            .build()

        val req = Request.Builder()
            .url("$SSO_BASE/username-password/login")
            .post(body)
            .build()

        val respBody = client.newCall(req).execute().use { resp ->
            logger("login: HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }

        val json = runCatching { JSONObject(respBody) }.getOrElse { JSONObject() }
        val code = json.optInt("code", -1)
        val message = json.optString("message", "")

        logger("login: code=$code message=$message")

        LoginResult(
            code = code,
            message = message,
            rawJson = json
        )
    }

    // ══════════════════════════════════════════════════════════
    // 步骤 5：CAS 登录教务系统（用 TGC 换 session）
    // ══════════════════════════════════════════════════════════
    suspend fun casLogin(casLoginUrl: String = JWXT_CAS_LOGIN): Boolean =
        withContext(Dispatchers.IO) {
            logger("casLogin: 开始 $casLoginUrl")

            val encodedService = java.net.URLEncoder.encode(casLoginUrl, "UTF-8")
            val url = "$SSO_BASE/login?service=$encodedService"

            val resp = redirectClient.newCall(
                Request.Builder().url(url).get().build()
            ).execute()

            logger("casLogin: 最终 URL=${resp.request.url}")
            logger("casLogin: HTTP ${resp.code}")
            resp.close()

            val success = isLoggedIn() || getAllCookiesHeader().contains("JSESSIONID")
            logger("casLogin: 结果=$success, Cookie 数=${cookieStore.size}")
            success
        }

    // ══════════════════════════════════════════════════════════
    // 完整登录流程
    // ══════════════════════════════════════════════════════════
    suspend fun fullLogin(username: String, password: String): LoginResult {
        logger("=== fullLogin 开始 ===")

        // ① 拿公钥
        val publicKey = getPublicKey()

        // ② 查验证码（失败不阻塞）
        runCatching { infoQuery() }

        // ③ 登录
        val result = login(username, password, publicKey)
        logger("=== fullLogin 结果: code=${result.code} ===")

        if (result.isSuccess) {
            // ④ CAS 登录教务系统
            val casOk = runCatching { casLogin() }.getOrElse {
                logger("casLogin 失败: ${it.message}")
                false
            }
            logger("=== fullLogin CAS 结果: $casOk ===")
        }

        return result
    }

    /** 清空所有 Cookie */
    fun clearCookies() {
        cookieStore.clear()
        logger("clearCookies: 已清空")
    }
}

/** 登录结果 */
data class LoginResult(
    val code: Int,
    val message: String,
    val rawJson: JSONObject
) {
    /** 学校用 666666 表示成功 */
    val isSuccess: Boolean get() = code == BistuAuth.SUCCESS_CODE
}

class AuthException(message: String) : Exception(message)
