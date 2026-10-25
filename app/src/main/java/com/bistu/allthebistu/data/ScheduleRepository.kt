package com.bistu.allthebistu.data

import android.content.Context
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.auth.BistuAuth
import com.bistu.allthebistu.data.auth.JwxtApi
import com.bistu.allthebistu.data.auth.SessionExpiredException
import com.bistu.allthebistu.data.db.AppDatabase
import com.bistu.allthebistu.data.db.toCourse
import com.bistu.allthebistu.data.db.toEntity
import com.bistu.allthebistu.data.db.toMetaEntity
import com.bistu.allthebistu.data.db.toTermWeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表仓库：统一管理「网络 → 数据库 → 界面」的数据流。
 *
 * 加载策略：
 *  1. 有缓存 → 立刻返回（秒开）
 *  2. 缓存为空 + 已登录 → 从教务系统拉取 → 存库 → 返回
 *  3. 缓存为空 + 未登录 → 读 assets 示例数据（**只展示，不落库**）
 */
class ScheduleRepository(private val context: Context) {

    private val dao by lazy { AppDatabase.get(context).scheduleDao() }

    /** 登录实例（由 AuthHolder 注入，会话本身已持久化在 SessionStore） */
    private var auth: BistuAuth? = null

    /**
     * 会话失效时的回调（由 UI 层注入，用于清理持久化的登录态）。
     *
     * data 层不直接依赖 ui 层，通过回调解耦。
     */
    var onSessionExpired: (() -> Unit)? = null

    /** 是否已登录 */
    val isLoggedIn: Boolean get() = auth?.isLoggedIn() == true

    /** 设置登录实例（登录成功或启动恢复后调用） */
    fun setAuth(newAuth: BistuAuth) {
        auth = newAuth
    }

    /** 清除登录状态 */
    fun clearAuth() {
        auth?.clearCookies()
        auth = null
    }

    /**
     * 加载课表。
     *
     * @param termCode 学期代码；null 表示由教务系统自动判定当前学期
     * @param forceRefresh true 时跳过缓存，强制从网络拉取
     */
    suspend fun loadSchedule(
        termCode: String? = null,
        forceRefresh: Boolean = false
    ): LoadResult = withContext(Dispatchers.IO) {

        // ── 1. 缓存优先（除非强制刷新）──
        // termCode 为 null 时，用最近一次同步的学期记录来命中缓存
        if (!forceRefresh) {
            readCache(termCode)?.let { return@withContext it }
        }

        // ── 2. 已登录 → 从网络拉 ──
        val currentAuth = auth
        if (currentAuth != null && currentAuth.isLoggedIn()) {
            try {
                val api = JwxtApi(currentAuth)
                val data = api.fetchSchedule(termCode = termCode)  // null = 自动取当前学期
                saveSchedule(data)
                return@withContext LoadResult(
                    data,
                    Source.NETWORK,
                    System.currentTimeMillis()
                )
            } catch (e: Exception) {
                // 鉴权失效（401 / 登录过期）→ 通知上层清掉持久化会话
                if (isAuthFailure(e)) {
                    AppLog.w(TAG, "会话已失效，通知上层清理登录态: ${e.message}")
                    auth = null
                    runCatching { onSessionExpired?.invoke() }
                }
                // 网络失败 → 依次回退：缓存 → 示例数据 → 错误页
                readCache(termCode)?.let { return@withContext it.copy(error = e) }
                loadSample(termCode, e)?.let { return@withContext it }
                return@withContext LoadResult(null, Source.ERROR, 0, e)
            }
        }

        // ── 3. 未登录 → 读 assets 示例（只展示，**不落库**）──
        // 关键：示例数据若写入 Room 会污染缓存，
        // 导致登录后首次加载仍先看到假课表。这里只返回、不保存。
        return@withContext loadSample(termCode, null)
            ?: LoadResult(null, Source.ERROR, 0, null)
    }

    /**
     * 只读本地缓存，不触网。
     *
     * 冷启动时先拿它把界面点亮（离线、教务系统宕机也能看到上次的课表），
     * 随后再在后台静默刷新。
     */
    suspend fun loadCached(termCode: String? = null): LoadResult? =
        withContext(Dispatchers.IO) { readCache(termCode) }

    /**
     * 读取 assets 里的示例课表（只用于展示，不写入数据库）。
     *
     * @param cause 若非 null，表示这是网络失败后的降级展示，错误会一并带出
     */
    private fun loadSample(termCode: String?, cause: Exception?): LoadResult? = try {
        val rawJson = context.assets.open("schedule_sample.json")
            .bufferedReader()
            .use { it.readText() }
        val data = ScheduleParser.parse(rawJson, termCode ?: DEFAULT_TERM)
        LoadResult(data, Source.ASSETS, System.currentTimeMillis(), cause)
    } catch (e: Exception) {
        AppLog.w(TAG, "读取示例课表失败: ${e.message}")
        null
    }

    /**
     * 读缓存。
     *
     * @param termCode null 表示取最近一次同步的学期
     */
    private suspend fun readCache(termCode: String?): LoadResult? {
        val meta = if (termCode != null) {
            dao.getMeta(termCode)
        } else {
            dao.getLatestMeta()
        } ?: return null

        val cachedCourses = dao.getCourses(meta.termCode)
        if (cachedCourses.isEmpty()) return null

        val data = ScheduleData(
            termCode = meta.termCode,
            termName = meta.termName,
            studentName = meta.studentName,
            studentId = meta.studentId,
            major = meta.major,
            className = meta.className,
            campus = meta.campus,
            courses = cachedCourses.map { it.toCourse() },
            termWeeks = dao.getTermWeeks(meta.termCode).map { it.toTermWeek() }
        )
        return LoadResult(data, Source.CACHE, meta.lastSyncAt)
    }

    /**
     * 判断异常是否属于「登录态真的失效」，只有这种才允许清会话。
     *
     * 以前是按 `message.contains("登录")` 猜，结果「本学期没排课」这类
     * 业务失败也被判成掉登录，把用户的 Cookie 一起清了。
     */
    private fun isAuthFailure(e: Exception): Boolean = e is SessionExpiredException

    /** 保存到数据库（覆盖同一学期的旧数据） */
    suspend fun saveSchedule(data: ScheduleData) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.deleteCourses(data.termCode)
        dao.insertCourses(data.courses.map { it.toEntity(data.termCode) })
        dao.insertMeta(data.toMetaEntity(now))
        // 教学周：只有拿到数据时才覆盖，避免一次接口失败把已有数据清空
        if (data.termWeeks.isNotEmpty()) {
            dao.deleteTermWeeks(data.termCode)
            dao.insertTermWeeks(data.termWeeks.map { it.toEntity(data.termCode) })
        }
    }

    /** 清空缓存 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        dao.clearAllCourses()
        dao.clearAllMeta()
        dao.clearAllTermWeeks()
    }

    /** 缓存里的课程数 */
    suspend fun cachedCount(termCode: String = DEFAULT_TERM): Int =
        dao.countCourses(termCode)

    /** 最后同步时间 */
    suspend fun lastSyncAt(termCode: String = DEFAULT_TERM): Long? =
        dao.getMeta(termCode)?.lastSyncAt

    companion object {
        private const val TAG = "ScheduleRepository"

        /** 仅在未指定学期且离线时用于解析 assets 示例数据 */
        const val DEFAULT_TERM = "2026-2027-1"
    }
}

/** 数据来源 */
enum class Source {
    CACHE,      // 本地数据库
    ASSETS,     // assets 示例数据
    NETWORK,    // 教务系统实时拉取
    ERROR
}

/** 加载结果 */
data class LoadResult(
    val data: ScheduleData?,
    val source: Source,
    val timestamp: Long,
    val error: Exception? = null
)
