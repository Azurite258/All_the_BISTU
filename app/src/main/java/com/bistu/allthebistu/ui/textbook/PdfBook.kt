package com.bistu.allthebistu.ui.textbook

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import java.io.File
import java.io.Closeable

/**
 * 一本已打开的 PDF。
 *
 * **为什么要单独包一层**：`PdfRenderer` 有三个坑，直接用在 Compose 里会出事：
 *  1. **不是线程安全的** —— 渲染必须串行，否则会随机崩。这里用 [lock] 全包住。
 *  2. **只能按页渲染成位图** —— 没有文字层，所以每页的位图要自己缓存，
 *     否则来回滚动会反复渲染（一页 1080 宽的 A4 约 4~6MB）。
 *  3. **必须手动 close** —— 不关会泄漏文件描述符，开几本书就 "Too many open files"。
 *
 * 缓存策略：按「页号 @ 目标宽度」做 key，最多 [CACHE_PAGES] 页。
 * **位图必须是 ARGB_8888** —— 试过 RGB_565 省内存，结果 PdfRenderer 直接渲染成白板
 * （它对目标 Bitmap 的配置有硬性要求）。所以缓存页数不能多，4 页 ≈ 26MB 是安全线。
 *
 * 将来做桌面版时，把这个类换成 PDFium/Skia 实现、接口保持不变即可，
 * 上面的 [TextbookReaderScreen] 不用动。
 */
class PdfBook(private val file: File) : Closeable {

    private val lock = Any()

    private val pfd: ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

    private val renderer: PdfRenderer = PdfRenderer(pfd)

    val pageCount: Int = renderer.pageCount

    /** 页面宽高比（高 / 宽），用来在渲染完成前先把占位撑出来，避免滚动时跳来跳去 */
    private val ratios = HashMap<Int, Float>()

    private val cache = object : LruCache<String, Bitmap>(CACHE_PAGES) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /**
     * 渲染第 [index] 页，宽度缩放到 [targetWidthPx]。
     *
     * 返回的 Bitmap 归调用方使用，但**不要回收**——它还在缓存里。
     */
    fun render(index: Int, targetWidthPx: Int): Bitmap? = synchronized(lock) {
        if (index !in 0 until pageCount) return null
        val key = "$index@$targetWidthPx"
        cache.get(key)?.let { return it }

        return runCatching {
            renderer.openPage(index).use { page ->
                val scale = targetWidthPx.toFloat() / page.width
                val w = targetWidthPx.coerceAtLeast(1)
                val h = (page.height * scale).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                // 白底：教材 PDF 的页面本身是透明的，不铺白会渲染成黑底
                bitmap.eraseColor(Color.WHITE)
                val matrix = Matrix().apply { setScale(scale, scale) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                cache.put(key, bitmap)
                bitmap
            }
        }.getOrNull()
    }

    /** 高 / 宽。拿不到时按 A4（1.414）兜底 */
    fun ratioOf(index: Int): Float = synchronized(lock) {
        ratios[index]?.let { return it }
        val r = runCatching {
            renderer.openPage(index).use { page -> page.height.toFloat() / page.width }
        }.getOrDefault(A4_RATIO)
        ratios[index] = r
        r
    }

    override fun close() {
        synchronized(lock) {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
            cache.evictAll()
        }
    }

    companion object {
        /** 缓存页数。ARGB_8888 一页 ≈ 6.6MB，4 页 ≈ 26MB，再多有 OOM 风险 */
        private const val CACHE_PAGES = 4
        private const val A4_RATIO = 1.414f
    }
}
