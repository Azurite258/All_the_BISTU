package com.example.study1.data

import android.content.Context
import com.example.study1.data.auth.BistuAuth
import com.example.study1.data.auth.JwxtApi
import com.example.study1.data.db.AppDatabase
import com.example.study1.data.db.toCourse
import com.example.study1.data.db.toEntity
import com.example.study1.data.db.toMetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表仓库：统一管理「网络 → 数据库 → 界面」的数据流。
 *
 * 加载策略：
 *  1. 有缓存 → 立刻返回（秒开）
 *  2. 缓存为空 + 已登录 → 从教务系统拉取 → 存库 → 返回
 *  3. 缓存为空 + 未登录 → 读 assets 示例数据（首次体验用）
 */
class ScheduleRepository(private val context: Context) {

    private val dao by lazy { AppDatabase.get(context).scheduleDao() }

    /** 登录状态（内存中，App 重启后丢失，后续可持久化） */
    private var auth: BistuAuth? = null

    /** 是否已登录 */
    val isLoggedIn: Boolean get() = auth?.isLoggedIn() == true

    /** 设置登录实例（登录成功后调用） */
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
     * @param forceRefresh true 时跳过缓存，强制从网络拉取
     */
    suspend fun loadSchedule(
        termCode: String = "2026-2027-1",
        forceRefresh: Boolean = false
    ): LoadResult = withContext(Dispatchers.IO) {

        // ── 1. 缓存优先（除非强制刷新）──
        if (!forceRefresh) {
            val cachedCourses = dao.getCourses(termCode)
            if (cachedCourses.isNotEmpty()) {
                val meta = dao.getMeta(termCode)
                if (meta != null) {
                    val data = ScheduleData(
                        termCode = meta.termCode,
                        termName = meta.termName,
                        studentName = meta.studentName,
                        studentId = meta.studentId,
                        major = meta.major,
                        className = meta.className,
                        campus = meta.campus,
                        courses = cachedCourses.map { it.toCourse() }
                    )
                    return@withContext LoadResult(data, Source.CACHE, meta.lastSyncAt)
                }
            }
        }

        // ── 2. 已登录 → 从网络拉 ──
        val currentAuth = auth
        if (currentAuth != null && currentAuth.isLoggedIn()) {
            try {
                val api = JwxtApi(currentAuth)
                val data = api.fetchSchedule(termCode = null)  // null = 自动取当前学期
                saveSchedule(data)
                return@withContext LoadResult(
                    data,
                    Source.NETWORK,
                    System.currentTimeMillis()
                )
            } catch (e: Exception) {
                // 网络失败 → 回退到缓存
                val cachedCourses = dao.getCourses(termCode)
                if (cachedCourses.isNotEmpty()) {
                    val meta = dao.getMeta(termCode)
                    if (meta != null) {
                        val data = ScheduleData(
                            meta.termCode, meta.termName, meta.studentName,
                            meta.studentId, meta.major, meta.className, meta.campus,
                            cachedCourses.map { it.toCourse() }
                        )
                        return@withContext LoadResult(
                            data, Source.CACHE, meta.lastSyncAt, e
                        )
                    }
                }
                return@withContext LoadResult(null, Source.ERROR, 0, e)
            }
        }

        // ── 3. 未登录 → 读 assets 示例 ──
        return@withContext try {
            val rawJson = context.assets.open("schedule_sample.json")
                .bufferedReader()
                .use { it.readText() }
            val data = ScheduleParser.parse(rawJson, termCode)
            saveSchedule(data)
            LoadResult(data, Source.ASSETS, System.currentTimeMillis())
        } catch (e: Exception) {
            LoadResult(null, Source.ERROR, 0, e)
        }
    }

    /** 保存到数据库（覆盖同一学期的旧数据） */
    suspend fun saveSchedule(data: ScheduleData) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.deleteCourses(data.termCode)
        dao.insertCourses(data.courses.map { it.toEntity(data.termCode) })
        dao.insertMeta(data.toMetaEntity(now))
    }

    /** 清空缓存 */
    suspend fun clearCache() = withContext(Dispatchers.IO) {
        dao.clearAllCourses()
        dao.clearAllMeta()
    }

    /** 缓存里的课程数 */
    suspend fun cachedCount(termCode: String = "2026-2027-1"): Int =
        dao.countCourses(termCode)

    /** 最后同步时间 */
    suspend fun lastSyncAt(termCode: String = "2026-2027-1"): Long? =
        dao.getMeta(termCode)?.lastSyncAt
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
