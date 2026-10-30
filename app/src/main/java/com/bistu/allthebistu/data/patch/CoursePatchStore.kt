package com.bistu.allthebistu.data.patch

import android.content.Context
import com.bistu.allthebistu.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 课程本地改动的存储。
 *
 * 为什么不用 Room：改动总量极小（几十条 JSON），而且结构还会随需求变；
 * 一个 JSON 文件读一次就够，改完整体覆写，简单、可读、出问题能直接看文件。
 *
 * 一致性靠 [mutex]：读改写整段串行，避免两个快速点击把对方覆盖掉。
 * 写盘用「先写临时文件再 rename」，中途断电也不会留下半截 JSON——
 * 半截文件会被 [load] 当成空集，等于用户一晚上的改动全没了。
 */
class CoursePatchStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val mutex = Mutex()

    private val _patches = MutableStateFlow(CoursePatches.EMPTY)

    /** 当前生效的改动；界面订阅这个，任何写操作都会推新值 */
    val patches: StateFlow<CoursePatches> = _patches.asStateFlow()

    val current: CoursePatches get() = _patches.value

    init {
        _patches.value = readFromDisk()
    }

    /**
     * 读改写。
     *
     * @param block 纯变换，返回值会立刻落盘并推给订阅者
     */
    suspend fun update(block: (CoursePatches) -> CoursePatches): CoursePatches {
        val next = mutex.withLock {
            val updated = block(_patches.value).pruned()
            if (updated != _patches.value) {
                writeToDisk(updated)
                _patches.value = updated
            }
            updated
        }
        return next
    }

    /** 退出登录时清空（课程改动属于个人数据，换个人登录不该带过去） */
    suspend fun clear() {
        mutex.withLock {
            runCatching { if (file.exists()) file.delete() }
                .onFailure { AppLog.w(TAG, "清空课程改动失败: ${it.message}") }
            _patches.value = CoursePatches.EMPTY
        }
    }

    /** 构造时同步读一次：文件只有几 KB，塞进协程反而让首帧拿到空集合 */
    private fun readFromDisk(): CoursePatches {
        if (!file.exists()) return CoursePatches.EMPTY
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) CoursePatches.EMPTY else json.decodeFromString<CoursePatches>(text)
        }.onFailure {
            // 文件损坏就当没有改动，同时把它挪开留证，不要让它一直挡着写盘
            AppLog.w(TAG, "课程改动读取失败，按空处理: ${it.message}")
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.bad")) }
        }.getOrDefault(CoursePatches.EMPTY)
    }

    private suspend fun writeToDisk(value: CoursePatches) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(CoursePatches.serializer(), value))
            if (file.exists() && !file.delete()) {
                AppLog.w(TAG, "旧改动文件删不掉，直接覆盖")
            }
            if (!tmp.renameTo(file)) {
                // 少数机型上 rename 会失败（跨挂载点），退回直接覆写
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure {
            AppLog.w(TAG, "课程改动写入失败: ${it.message}")
        }
    }

    companion object {
        private const val TAG = "CoursePatchStore"
        private const val FILE_NAME = "course_patches.json"

        /** 给退出登录的清除器用，不依赖实例 */
        suspend fun wipe(context: Context) {
            withContext(Dispatchers.IO) {
                runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
            }
        }
    }
}
