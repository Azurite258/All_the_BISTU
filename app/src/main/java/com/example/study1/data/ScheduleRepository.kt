package com.example.study1.data

import android.content.Context
import com.example.study1.data.db.AppDatabase
import com.example.study1.data.db.toCourse
import com.example.study1.data.db.toEntity
import com.example.study1.data.db.toMetaEntity

/**
 * 课表仓库：统一管理「内存 → 数据库」的读取。
 *
 * 当前阶段数据来自 assets 里的示例 JSON，
 * 后续接入登录后，把 loadFromAssets 换成 loadFromNetwork 即可，
 * 上层调用方不用改。
 */
class ScheduleRepository(private val context: Context) {

    private val dao by lazy { AppDatabase.get(context).scheduleDao() }

    /**
     * 加载课表。
     *
     * 策略：
     *  1. 先读数据库缓存 → 有数据立刻返回
     *  2. 缓存为空 → 从 assets 解析 → 写入数据库 → 返回
     *
     * @return 课表数据 + 数据来源
     */
    suspend fun loadSchedule(termCode: String = "2026-2027-1"): LoadResult {
        // ── 1. 尝试读缓存 ──
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
                return LoadResult(data, Source.CACHE, meta.lastSyncAt)
            }
        }

        // ── 2. 缓存为空，从 assets 解析 ──
        return try {
            val rawJson = context.assets.open("schedule_sample.json")
                .bufferedReader()
                .use { it.readText() }
            val data = ScheduleParser.parse(rawJson, termCode)

            // 写入数据库
            saveSchedule(data)

            LoadResult(data, Source.ASSETS, System.currentTimeMillis())
        } catch (e: Exception) {
            LoadResult(null, Source.ERROR, 0, e)
        }
    }

    /** 保存到数据库（覆盖同一学期的旧数据） */
    suspend fun saveSchedule(data: ScheduleData) {
        val now = System.currentTimeMillis()
        dao.deleteCourses(data.termCode)
        dao.insertCourses(data.courses.map { it.toEntity(data.termCode) })
        dao.insertMeta(data.toMetaEntity(now))
    }

    /** 清空缓存 */
    suspend fun clearCache() {
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
    CACHE,      // 来自本地数据库
    ASSETS,     // 来自 assets 示例数据（将来会换成 NETWORK）
    NETWORK,    // 来自网络（登录后实现）
    ERROR
}

/** 加载结果 */
data class LoadResult(
    val data: ScheduleData?,
    val source: Source,
    val timestamp: Long,
    val error: Exception? = null
)
