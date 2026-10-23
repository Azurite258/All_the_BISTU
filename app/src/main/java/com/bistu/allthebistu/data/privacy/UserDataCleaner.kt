package com.bistu.allthebistu.data.privacy

import android.content.Context
import android.util.Log
import com.bistu.allthebistu.data.ScheduleRepository
import com.bistu.allthebistu.data.auth.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 可扩展的「用户数据清除器」注册表。
 *
 * 退出登录要清的东西会越来越多（课表、校园卡、学籍信息、缴费记录…），
 * 如果每次都去改 UserDataCleaner，迟早会漏。所以这里留一个注册点：
 * 新模块只要在初始化时 `UserDataWipers.register("校园卡") { ... }`，
 * 退出登录时就会被自动清掉。
 *
 * 约定：wipe 里不要抛异常，抛了也不会影响其他模块（这里会兜住并记录）。
 */
object UserDataWipers {

    data class Entry(val name: String, val wipe: suspend () -> Unit)

    private val entries = CopyOnWriteArrayList<Entry>()

    /** 注册一个清除器；同一个 name 重复注册会覆盖旧的 */
    fun register(name: String, wipe: suspend () -> Unit) {
        entries.removeAll { it.name == name }
        entries.add(Entry(name, wipe))
    }

    /** 当前注册了哪些 */
    fun names(): List<String> = entries.map { it.name }

    /** 单个模块的清除结果 */
    data class Result(
        val cleared: List<String> = emptyList(),
        /** 失败项：名字 → 原因 */
        val failed: Map<String, String> = emptyMap()
    )

    /**
     * 依次执行所有注册的清除器。
     *
     * 单个模块失败不会中断其余模块——清除个人信息这件事，
     * 「清掉一部分」远好过「因为某个模块报错就全都留着」。
     */
    suspend fun runAll(): Result {
        val cleared = mutableListOf<String>()
        val failed = mutableMapOf<String, String>()
        entries.forEach { entry ->
            runCatching { entry.wipe() }
                .onSuccess { cleared += entry.name }
                .onFailure { failed[entry.name] = it.message ?: it.javaClass.simpleName }
        }
        return Result(cleared, failed)
    }

    /** 仅测试用：清空注册表 */
    fun resetForTest() = entries.clear()
}

/**
 * 退出登录时的「一键清除」。
 *
 * 覆盖三类东西：
 *  1. 登录凭据：Cookie 快照、记住的学号（SessionStore）
 *  2. 业务数据：课表缓存（课程 / 学期元信息里的姓名学号专业 / 教学周）
 *  3. 临时文件：应用缓存目录（图片、临时下载等）
 *
 * 另外会执行 [UserDataWipers] 里注册的扩展清除器（校园卡等）。
 *
 * 注意：**不碰** app_settings（主题、字体、配色）——这些是设备偏好，
 * 不是个人信息，退出登录后还应该保留，否则用户每次登录都要重设一遍。
 */
class UserDataCleaner(private val context: Context) {

    private val repository = ScheduleRepository(context)

    /**
     * 执行清除。
     *
     * @return 实际清除成功的项目名，用于界面提示
     */
    suspend fun wipeAll(): List<String> = withContext(Dispatchers.IO) {
        val done = mutableListOf<String>()

        // ① 登录凭据：Cookie + 记住的学号
        runCatching { SessionStore(context).clearAll() }
            .onSuccess { done += "登录状态" }
            .onFailure { Log.w(TAG, "清除登录状态失败: ${it.message}") }

        // ② 课表缓存（顺带清掉学期元信息里的姓名 / 学号 / 专业 / 班级）
        runCatching { repository.clearCache() }
            .onSuccess { done += "课表缓存" }
            .onFailure { Log.w(TAG, "清除课表缓存失败: ${it.message}") }

        // ②' 学业报告（含要求/已修学分，属于个人学籍数据）
        runCatching { com.bistu.allthebistu.data.StudyReportStore(context).clear() }
            .onSuccess { done += "学业报告" }
            .onFailure { Log.w(TAG, "清除学业报告失败: ${it.message}") }

        // ③ 应用缓存目录：头像、临时图片、网络缓存等
        runCatching { wipeCacheDirs() }
            .onSuccess { done += "临时文件" }
            .onFailure { Log.w(TAG, "清除临时文件失败: ${it.message}") }

        // ④ 扩展模块（校园卡 / 学籍 / 缴费…）
        val extra = UserDataWipers.runAll()
        done += extra.cleared
        if (extra.failed.isNotEmpty()) {
            Log.w(TAG, "部分模块清除失败: ${extra.failed}")
        }

        Log.d(TAG, "退出登录已清除: ${done.joinToString("、")}")
        done.distinct()
    }

    private fun wipeCacheDirs() {
        listOfNotNull(context.cacheDir, context.codeCacheDir).forEach { dir ->
            dir.listFiles()?.forEach { file ->
                runCatching { file.deleteRecursively() }
            }
        }
    }

    companion object {
        private const val TAG = "UserDataCleaner"
    }
}
