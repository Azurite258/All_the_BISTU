package com.bistu.allthebistu.data.event

import android.content.Context
import com.bistu.allthebistu.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * 自定义日程的本地存储。
 *
 * 沿用 [com.bistu.allthebistu.data.patch.CoursePatchStore] 那一套：
 * 一个 JSON 文件 + 读改写整段串行 + 临时文件 rename。
 * 日程量级只有几十条，没必要上 Room；而且出问题时直接把文件拷出来就能看。
 */
class EventStore(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serializer = ListSerializer(UserEvent.serializer())

    private val mutex = Mutex()

    private val _events = MutableStateFlow<List<UserEvent>>(emptyList())

    /** 全部日程，按开始时间升序。界面订阅这个 */
    val events: StateFlow<List<UserEvent>> = _events.asStateFlow()

    init {
        _events.value = readFromDisk()
    }

    /**
     * 新增或更新一条（id 已存在就替换）。
     *
     * @return 落盘之后的完整列表
     */
    suspend fun save(event: UserEvent): List<UserEvent> = mutex.withLock {
        val normalized = event.copy(
            id = event.id.ifBlank { UUID.randomUUID().toString() },
            title = event.title.trim().take(TITLE_MAX),
            note = event.note.trim().take(NOTE_MAX),
            repeat = Repeat.sanitize(event.repeat),
            tag = EventTag.sanitize(event.tag),
            // 结束早于开始就按「开始 + 30 分钟」，不让脏数据进存储
            endAt = if (event.endAt > event.startAt) event.endAt else event.startAt + 30 * 60_000L
        )
        val next = (_events.value.filterNot { it.id == normalized.id } + normalized)
            .sortedBy { it.startAt }
        persist(next)
        next
    }

    suspend fun delete(id: String): List<UserEvent> = mutex.withLock {
        val next = _events.value.filterNot { it.id == id }
        if (next.size != _events.value.size) persist(next)
        next
    }

    suspend fun clear() {
        mutex.withLock {
            runCatching { if (file.exists()) file.delete() }
                .onFailure { AppLog.w(TAG, "清空日程失败: ${it.message}") }
            _events.value = emptyList()
        }
    }

    private suspend fun persist(value: List<UserEvent>) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(json.encodeToString(serializer, value))
            if (file.exists() && !file.delete()) {
                AppLog.w(TAG, "旧日程文件删不掉，直接覆盖")
            }
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { AppLog.w(TAG, "日程写入失败: ${it.message}") }
        _events.value = value
    }

    private fun readFromDisk(): List<UserEvent> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val text = file.readText()
            if (text.isBlank()) emptyList() else json.decodeFromString(serializer, text)
        }.onFailure {
            AppLog.w(TAG, "日程读取失败，按空处理: ${it.message}")
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.bad")) }
        }.getOrDefault(emptyList()).sortedBy { it.startAt }
    }

    companion object {
        private const val TAG = "EventStore"
        private const val FILE_NAME = "user_events.json"
        private const val TITLE_MAX = 60
        private const val NOTE_MAX = 500

        /** 退出登录时清空（日程属于个人数据） */
        suspend fun wipe(context: Context) {
            withContext(Dispatchers.IO) {
                runCatching { File(context.applicationContext.filesDir, FILE_NAME).delete() }
            }
        }
    }
}
