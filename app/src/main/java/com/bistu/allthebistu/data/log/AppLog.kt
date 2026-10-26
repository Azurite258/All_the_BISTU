package com.bistu.allthebistu.data.log

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 会落盘的日志。
 *
 * 为什么需要它：业务日志原来只有 `Log.d`，只进 logcat 的环形缓冲区
 * （默认 256KB，手机一重启就清空，别的 App 也在同一个池子里刷）。
 * 而「登录放着半小时后自己掉线」这类问题必须回看半小时前的链路，
 * 等用户发现再去 `adb logcat` 早就被冲掉了 —— 前几次排查全靠猜。
 *
 * 现在每条日志同时写 logcat 和 `filesDir/logs/app.log`（512KB 滚动，保留 2 份），
 * 未捕获异常也写进去。掉线后一条 `adb pull`，或在「我的 → 诊断日志」里
 * 直接分享出来，就是完整链路。
 *
 * 只写本机文件，不含账号密码（密码从来不入日志，Cookie 只打名字和长度）。
 */
object AppLog {

    /** 单个文件上限：够存几小时到几天的调试量，又不至于 pull 半天 */
    private const val MAX_BYTES = 512L * 1024

    /** 保留文件数：app.log 正在写，app.log.1 是上一份 */
    private const val MAX_FILES = 2

    /** 单条截断：WebView 诊断会把整段 DOM 打进来，不截会瞬间刷满 */
    private const val MAX_MESSAGE = 1000

    private const val FILE_NAME = "app.log"

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    /** 单线程写盘：不阻塞调用方（含主线程），顺序也不会乱 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "app-log").apply { isDaemon = true }
    }

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 里调用一次 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = File(context.applicationContext.filesDir, "logs")
        runCatching { dir.mkdirs() }
        logDir = dir

        // 崩溃栈必须同步写：进程马上就没，排队写盘来不及
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            appendNow("E", "CRASH", "线程「${thread.name}」未捕获异常: ${Log.getStackTraceString(error)}")
            previous?.uncaughtException(thread, error)
        }

        d("AppLog", "日志已启用：${File(dir, FILE_NAME).absolutePath}")
    }

    fun d(tag: String, message: String) {
        if (!installed) return
        Log.d(tag, message)
        enqueue("D", tag, message)
    }

    fun i(tag: String, message: String) {
        if (!installed) return
        Log.i(tag, message)
        enqueue("I", tag, message)
    }

    fun w(tag: String, message: String) {
        if (!installed) return
        Log.w(tag, message)
        enqueue("W", tag, message)
    }

    fun e(tag: String, message: String) {
        if (!installed) return
        Log.e(tag, message)
        enqueue("E", tag, message)
    }

    fun e(tag: String, message: String, error: Throwable?) {
        if (!installed) return
        Log.e(tag, message, error)
        enqueue("E", tag, if (error == null) message else "$message\n${Log.getStackTraceString(error)}")
    }

    /** 把落盘队列刷干净（导出前调用） */
    fun flush() {
        runCatching { writer.submit {}.get(2, TimeUnit.SECONDS) }
    }

    /** 当前日志目录，调试用 */
    fun logDir(): File? = logDir

    /**
     * 把一段原始文本单独落盘（用于保存接口原始响应）。
     *
     * 为什么不直接塞进 app.log：教务的课表响应动辄几十 KB，
     * 一次就能把 512KB 的滚动日志刷掉，真正的排查线索反而没了。
     * 独立成文件后，导出诊断日志时会一起带走。
     */
    fun dump(name: String, content: String) {
        val dir = logDir ?: return
        val file = File(dir, "$name.txt")
        runCatching {
            file.writeText(content)
            d("AppLog", "dump: ${file.name} 已保存 (${content.length} 字符)")
        }.onFailure {
            w("AppLog", "dump: ${file.name} 写入失败 ${it.message}")
        }
    }

    /**
     * 把日志打包成一个 txt 分享出去。
     *
     * 没有电脑、或者 adb 连不上的时候，用户能自己把日志发出来，
     * 不用再靠「描述现象」来反推。
     */
    fun share(context: Context) {
        flush()
        val dir = logDir ?: return
        val target = runCatching {
            val outDir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            outDir.listFiles()?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
            val file = File(outDir, "all-the-bistu-log-$stamp.txt")
            file.bufferedWriter().use { out ->
                out.appendLine("App: All the BISTU ${versionName(context)}")
                out.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                out.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                out.appendLine("=".repeat(60))
                // 先老的、后新的，顺时间线读
                (MAX_FILES - 1 downTo 1).forEach { i ->
                    val old = File(dir, "$FILE_NAME.$i")
                    if (old.exists()) {
                        out.appendLine()
                        out.appendLine("──── ${old.name} ────")
                        out.append(old.readText())
                    }
                }
                val current = File(dir, FILE_NAME)
                if (current.exists()) {
                    out.appendLine()
                    out.appendLine("──── ${current.name} ────")
                    out.append(current.readText())
                }
                // 附加的原始数据（接口响应等）。单独成文件是为了不刷掉滚动日志，
                // 但排查时必须一起看，比如「某门课从课表里消失」要对着原始 JSON 才能定位
                dir.listFiles()
                    ?.filter { it.isFile && it.extension == "txt" && !it.name.startsWith(FILE_NAME) }
                    ?.sortedBy { it.name }
                    ?.forEach { extra ->
                        out.appendLine()
                        out.appendLine("──── ${extra.name} (${extra.length()} bytes) ────")
                        out.append(extra.readText())
                        out.appendLine()
                    }
            }
            file
        }.getOrNull() ?: return

        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "All the BISTU 诊断日志")
                clipData = ClipData.newRawUri("log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                Intent.createChooser(send, "导出诊断日志")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w("AppLog", "分享日志失败: ${it.message}") }
    }

    // ───────────────────────────────────────────────────────────
    // 内部
    // ───────────────────────────────────────────────────────────

    private fun enqueue(level: String, tag: String, message: String) {
        val dir = logDir ?: return
        writer.execute { appendNow(level, tag, message, dir) }
    }

    private fun appendNow(level: String, tag: String, message: String, dir: File? = logDir) {
        val target = dir ?: return
        runCatching {
            val file = File(target, FILE_NAME)
            if (file.length() > MAX_BYTES) rotate(target)
            synchronized(timeFormat) { timeFormat.format(Date()) }.let { stamp ->
                file.appendText("$stamp $level/$tag: ${message.take(MAX_MESSAGE)}\n")
            }
        }
    }

    /** app.log 写满 → 依次往后挪，最老的一份丢掉 */
    private fun rotate(dir: File) {
        val current = File(dir, FILE_NAME)
        for (i in MAX_FILES - 1 downTo 1) {
            val from = if (i == 1) current else File(dir, "$FILE_NAME.${i - 1}")
            if (!from.exists()) continue
            val to = File(dir, "$FILE_NAME.$i")
            to.delete()
            from.renameTo(to)
        }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
