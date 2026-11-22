package com.bistu.allthebistu.data.textbook

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 教材导入（**唯一依赖 Android API 的一环**，桌面对应实现换成文件对话框即可）。
 *
 * 流程：系统文件选择器给出 `content://` Uri → 流式拷贝进私有目录 → 用 PdfRenderer
 * 读页数 → 按文件名猜课程 → 返回一条待入库的 [Textbook]。
 *
 * 为什么要先拷贝而不是直接用 Uri：SAF 的临时授权重启后就失效，
 * 教材是长期资产，必须落到自己的目录里。
 */
class TextbookImporter(
    private val context: Context,
    private val store: TextbookStore
) {

    sealed interface Result {
        data class Success(val textbook: Textbook) : Result
        /** 单本失败不影响同批其它书，[name] 用来在汇总里点名 */
        data class Failure(val name: String, val reason: String) : Result
    }

    /**
     * 导入单个文件。
     *
     * @param courses 课表里的课程名，用来自动归类
     */
    suspend fun import(uri: Uri, courses: List<String>): Result = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            val name = queryName(uri) ?: "未命名.pdf"
            if (!name.endsWith(".pdf", ignoreCase = true)) {
                return@withContext Result.Failure(name, "目前只支持 PDF")
            }

            val targetName = "${UUID.randomUUID()}.pdf"
            tmp = File(store.fileOf(Textbook(fileName = targetName)).parentFile, "$targetName.part")
            tmp.parentFile?.mkdirs()

            // 流式拷贝：教材常见 100~300MB，绝不能 readBytes() 一把梭
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        copied += n
                    }
                }
            } ?: return@withContext Result.Failure(name, "文件打不开")

            // 用 PdfRenderer 读页数：打不开说明是加密/损坏的 PDF
            val pageCount = runCatching { countPages(tmp) }.getOrElse { err ->
                tmp.delete()
                val reason = when (err) {
                    is SecurityException -> "PDF 有密码保护，暂不支持"
                    else -> "不是有效的 PDF（${err.message ?: "无法解析"}）"
                }
                return@withContext Result.Failure(name, reason)
            }

            val finalFile = File(store.fileOf(Textbook(fileName = targetName)).parentFile, targetName)
            if (!tmp.renameTo(finalFile)) {
                tmp.delete()
                return@withContext Result.Failure(name, "写入失败")
            }
            tmp = null

            val textbook = Textbook(
                fileName = targetName,
                title = name.substringBeforeLast('.').trim().take(80).ifBlank { "未命名教材" },
                courseName = TextbookMatcher.bestCourse(name, courses),
                pageCount = pageCount,
                sizeBytes = copied,
                addedAt = System.currentTimeMillis()
            )
            Result.Success(textbook)
        } catch (e: Exception) {
            tmp?.let { runCatching { it.delete() } }
            Result.Failure(queryName(uri) ?: "未知文件", e.message ?: "导入失败")
        }
    }

    private fun countPages(file: File): Int {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer -> return renderer.pageCount }
        }
    }

    private fun queryName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }
}
