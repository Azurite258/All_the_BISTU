package com.example.study1.data.auth

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import org.json.JSONArray
import org.json.JSONObject

/**
 * 可序列化的 Cookie 快照。
 *
 * OkHttp 的 Cookie 本身不提供公开的序列化能力（构造函数是 internal 的），
 * 所以我们自己定义一份纯数据快照，落盘后用 [Cookie.Builder] 还原。
 */
data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    /** 过期时间戳（毫秒）。会话 Cookie 为 Long.MIN_VALUE */
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    /** true 表示只对当前域名生效（host-only），否则对子域名也生效 */
    val hostOnly: Boolean
) {
    /** 是否已过期。会话 Cookie（Long.MIN_VALUE）视为未过期 */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean =
        expiresAt != Long.MIN_VALUE && expiresAt <= now

    /** 还原成 OkHttp Cookie。域名非法等异常返回 null */
    fun toCookie(): Cookie? = runCatching {
        Cookie.Builder()
            .name(name)
            .value(value)
            .expiresAt(expiresAt)
            .apply {
                // domain() 与 hostOnlyDomain() 互斥，后者会把 hostOnly 置为 true
                if (hostOnly) hostOnlyDomain(domain) else domain(domain)
            }
            .path(path)
            .apply { if (secure) secure() }
            .apply { if (httpOnly) httpOnly() }
            .build()
    }.onFailure {
        Log.w(TAG, "还原 Cookie 失败 name=$name domain=$domain: ${it.message}")
    }.getOrNull()

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("value", value)
        put("domain", domain)
        put("path", path)
        put("expiresAt", expiresAt)
        put("secure", secure)
        put("httpOnly", httpOnly)
        put("hostOnly", hostOnly)
    }

    companion object {
        private const val TAG = "StoredCookie"

        fun from(cookie: Cookie): StoredCookie = StoredCookie(
            name = cookie.name,
            value = cookie.value,
            domain = cookie.domain,
            path = cookie.path,
            expiresAt = cookie.expiresAt,
            secure = cookie.secure,
            httpOnly = cookie.httpOnly,
            hostOnly = cookie.hostOnly
        )

        fun fromJson(obj: JSONObject): StoredCookie? = runCatching {
            StoredCookie(
                name = obj.getString("name"),
                value = obj.getString("value"),
                domain = obj.getString("domain"),
                path = obj.optString("path", "/"),
                expiresAt = obj.optLong("expiresAt", Long.MIN_VALUE),
                secure = obj.optBoolean("secure", false),
                httpOnly = obj.optBoolean("httpOnly", false),
                hostOnly = obj.optBoolean("hostOnly", false)
            )
        }.getOrNull()
    }
}

/**
 * 登录会话持久化（SharedPreferences）。
 *
 * 把 CAS 登录拿到的 Cookie 落盘，App 冷启动时还原，实现免重复登录。
 *
 * 安全说明：
 *  - 只保存 Cookie，**不保存学号和密码**。
 *  - 存放于应用私有目录（MODE_PRIVATE），其他应用无法读取。
 *  - 退出登录时立即清空。
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存 Cookie 快照（覆盖旧的） */
    fun save(cookies: List<StoredCookie>) {
        if (cookies.isEmpty()) {
            clear()
            return
        }
        val arr = JSONArray()
        cookies.forEach { arr.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_COOKIES, arr.toString())
            .putLong(KEY_SAVED_AT, System.currentTimeMillis())
            .apply()
        Log.d(TAG, "save: 已保存 ${cookies.size} 个 Cookie")
    }

    /**
     * 读取 Cookie 快照。
     *
     * 会自动过滤掉已过期的条目；若过滤后为空则顺带清空存储。
     */
    fun load(): List<StoredCookie> {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return emptyList()
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { StoredCookie.fromJson(it) }
            }
        }.getOrElse {
            Log.w(TAG, "load: 解析失败，清空存储: ${it.message}")
            emptyList()
        }

        val alive = list.filterNot { it.isExpired() }
        if (alive.isEmpty() && list.isNotEmpty()) {
            Log.d(TAG, "load: 全部 ${list.size} 个 Cookie 已过期")
            clear()
        }
        Log.d(TAG, "load: 读取 ${list.size} 个，有效 ${alive.size} 个")
        return alive
    }

    /** 是否存在可用会话 */
    fun hasSession(): Boolean = load().isNotEmpty()

    /** 最后保存时间（调试用） */
    fun savedAt(): Long = prefs.getLong(KEY_SAVED_AT, 0L)

    // ── 学号记忆（自动登录用）──
    //
    // 只记学号，**绝不保存密码**：
    // 密码仅用于本地 SM2 加密后发给学校，不做任何持久化。

    /** 记住上次登录成功的学号 */
    fun saveUsername(username: String) {
        if (username.isBlank()) return
        prefs.edit().putString(KEY_USERNAME, username).apply()
        Log.d(TAG, "saveUsername: 已记住学号")
    }

    /** 读取上次登录的学号（用于登录页预填） */
    fun loadUsername(): String = prefs.getString(KEY_USERNAME, "").orEmpty()

    /** 清空会话 */
    fun clear() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_SAVED_AT)
            .apply()
        Log.d(TAG, "clear: 会话已清除")
    }

    /** 清空会话 + 学号（退出登录时用） */
    fun clearAll() {
        prefs.edit()
            .remove(KEY_COOKIES)
            .remove(KEY_SAVED_AT)
            .remove(KEY_USERNAME)
            .apply()
        Log.d(TAG, "clearAll: 会话与学号已清除")
    }

    companion object {
        private const val TAG = "SessionStore"
        private const val PREFS_NAME = "auth_session"
        private const val KEY_COOKIES = "cookies"
        private const val KEY_SAVED_AT = "saved_at"
        private const val KEY_USERNAME = "username"
    }
}
