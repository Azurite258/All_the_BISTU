package com.bistu.allthebistu.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 自建头像的本地存储。
 *
 * 为什么不用系统给的 Uri 直接显示：相册返回的 Uri 是临时授权，
 * 重启 App 后就失效了（尤其 `PickVisualMedia` 给的是 content:// 的临时读权限）。
 * 所以选完必须复制到自己的私有目录。
 *
 * 尺寸压到 512px：头像是在 42dp 的圆里显示的，512 已经远超任何屏幕密度下的需要，
 * 再大只是浪费内存和解码时间。
 */
object AvatarStore {

    private const val TAG = "AvatarStore"
    private const val FILE_NAME = "avatar.jpg"

    /** 输出边长上限 */
    private const val TARGET_SIZE = 512

    /** JPEG 质量。90 在 512px 下大约 30~60KB，肉眼无损 */
    private const val QUALITY = 90

    /** 头像文件（不存在表示还没设过） */
    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun has(context: Context): Boolean = file(context).exists()

    /**
     * 读出头像。
     *
     * 每次显示都重新解码而不是缓存 Bitmap：文件只有几十 KB，
     * 而缓存 Bitmap 会让「换完头像后旧图还在」这类问题变得隐蔽。
     * 调用方用版本号触发重组即可。
     */
    fun load(context: Context): Bitmap? = runCatching {
        val f = file(context)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /**
     * 把相册里的图存成本地头像。
     *
     * 两步解码：先只读尺寸算出采样率，再按采样率真正解码。
     * 直接用 `decodeStream` 解一张 4000×3000 的图会瞬间吃掉上百 MB。
     *
     * @return 成功与否。失败时保持原头像不变。
     */
    fun save(context: Context, uri: Uri): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return false

        // 居中裁成正方形，再缩到目标尺寸——头像本来就是圆的，非正方形会被拉变形
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(
            decoded,
            (decoded.width - side) / 2,
            (decoded.height - side) / 2,
            side,
            side
        )
        val scaled = if (side > TARGET_SIZE) {
            Bitmap.createScaledBitmap(square, TARGET_SIZE, TARGET_SIZE, true)
        } else {
            square
        }

        FileOutputStream(file(context)).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }

        // 中间产生的临时 Bitmap 主动回收，别等 GC
        if (scaled !== square) scaled.recycle()
        if (square !== decoded) square.recycle()
        decoded.recycle()

        true
    }.onFailure { Log.w(TAG, "保存头像失败: ${it.message}") }
        .getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 2 的幂次采样，保证解码后仍比目标尺寸大 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= TARGET_SIZE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
