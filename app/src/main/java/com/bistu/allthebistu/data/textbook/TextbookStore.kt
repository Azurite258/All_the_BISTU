package com.bistu.allthebistu.data.textbook

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
 * 电子课本的本地存储。
 *
 * 和 [com.bistu.allthebistu.data.event.EventStore] 同一套写法：一个 JSON 文件 +
 * 读改写整段串行 + 临时文件 rename。区别只有两点：
 *  - 书文件本身放在 [dir]（`files/textbooks/`），JSON 里只存文件名
 *  - 多了一个"只改进度"的高频方法 [updateProgress]，不走整条 save 逻辑
 *
 * 传 [dir] 而不是 Context，是为了将来桌面端能直接复用这个类
 * （桌面只要给一个 File 目录就能跑）。
 */
class TextbookStore(private val dir: File) {

    private val file = File(dir, FILE_NAME)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serializer = ListSerializer(Textbook.serializer())

    private val mutex = Mutex()

    private val _textbooks = MutableStateFlow<List<Textbook>>(emptyList())

    /** 全部课本，按导入时间倒序（新导入的排前面） */
    val textbooks: StateFlow<List<Textbook>> = _textbooks.asStateFlow()

    init {
        dir.mkdirs()
        _textbooks.value = readFromDisk()
    }

    /** 某个文件的完整路径 */
    fun fileOf(textbook: Textbook): File = File(dir, textbook.fileName)

    /**
     * 新增或更新一条（id 已存在就替换）。
     */
    suspend fun save(textbook: Textbook): List<Textbook> = mutex.withLock {
        val normalized = textbook.copy(
            id = textbook.id.ifBlank { UUID.randomUUID().toString() },
            title = textbook.title.ifBlank { "未命名教材" }.trim().take(TITLE_MAX),
            courseName = textbook.courseName.trim().take(COURSE_MAX),
            pageCount = textbook.pageCount.coerceAtLeast(0),
            lastPage = textbook.lastPage.coerceIn(0, (textbook.pageCount - 1).coerceAtLeast(0))
        )
        val next = (_textbooks.value.filterNot { it.id == normalized.id } + normalized)
            .sortedByDescending { it.addedAt }
        persist(next)
        next
    }

    /**
     * 只更新阅读进度。
     *
     * 翻页会频繁触发，但教材记录通常只有十几条，整段重写也在几毫秒内，
     * 所以没有单独做增量存储；等真到了几百本再优化。
     */
    suspend fun updateProgress(id: String, page: Int): List<Textbook> = mutex.withLock {
        val idx = _textbooks.value.indexOfFirst { it.id == id }
        if (idx < 0) return@withLock _textbooks.value
        val old = _textbooks.value[idx]
        val nextPage = page.coerceIn(0, (old.pageCount - 1).coerceAtLeast(0))
        if (nextPage == old.lastPage) return@withLock _textbooks.value
        val next = _textbooks.value.toMutableList().also { it[idx] = old.copy(lastPage = nextPage) }
        persist(next)
        next
    }

    /** 删除一本：先删记录，再删文件（文件删失败不影响记录，避免"删不掉的幽灵书"） */
    suspend fun delete(id: String): List<Textbook> = mutex.withLock {
        val target = _textbooks.value.firstOrNull { it.id == id }
        val next = _textbooks.value.filterNot { it.id == id }
        if (next.size != _textbooks.value.size) persist(next)
        target?.let { runCatching { fileOf(it).delete() } }
        next
    }

    suspend fun clear() {
        mutex.withLock {
            runCatching { if (file.exists()) file.delete() }
            _textbooks.value = emptyList()
        }
    }

    // ── 落盘 ──

    private suspend fun persist(list: List<Textbook>) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "$FILE_NAME.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(serializer, list))
            // rename 在部分文件系统上不能覆盖已存在的目标，先删
            if (file.exists() && !file.delete()) error("旧文件删不掉")
            if (!tmp.renameTo(file)) error("rename 失败")
        }.onFailure {
            runCatching { tmp.delete() }
        }
        _textbooks.value = list
    }

    private fun readFromDisk(): List<Textbook> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrElse {
            // 坏文件留个备份，别直接覆盖掉用户可能想救的数据
            runCatching { file.copyTo(File(dir, "$FILE_NAME.bad"), overwrite = true) }
            emptyList()
        }.sortedByDescending { it.addedAt }
    }

    companion object {
        private const val FILE_NAME = "textbooks.json"
        private const val TITLE_MAX = 80
        private const val COURSE_MAX = 40
    }
}
