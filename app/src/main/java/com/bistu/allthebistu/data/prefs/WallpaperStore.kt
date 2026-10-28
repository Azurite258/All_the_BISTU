package com.bistu.allthebistu.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.bistu.allthebistu.data.log.AppLog
import java.io.File
import java.io.FileOutputStream

/**
 * 全局壁纸的本地存储。
 *
 * 和头像一个道理（见 [AvatarStore]）：相册返回的 Uri 是临时授权，重启就失效，
 * 必须复制到私有目录。区别在于壁纸要铺满整屏，所以：
 *
 *  - **不裁成正方形**，只把长边压到 1600px（2K 屏铺满也够清晰）
 *  - 质量 88：壁纸有大面积渐变，压太狠会有色带
 *
 * 文件约 200~500KB，退出登录时**不清**——壁纸是外观偏好，不是个人身份数据。
 */
object WallpaperStore {

    private const val TAG = "WallpaperStore"
    private const val FILE_NAME = "wallpaper.jpg"

    /** 长边上限：1600 在绝大多数手机上已经超过一屏的物理像素 */
    private const val MAX_EDGE = 1600

    private const val QUALITY = 88

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun has(context: Context): Boolean = file(context).exists()

    /**
     * 解码壁纸。
     *
     * 调用方（[com.bistu.allthebistu.ui.theme.AppWallpaper]）用版本号控制要不要重解，
     * 这里不缓存 Bitmap，避免「换了壁纸还显示旧的」。
     */
    fun load(context: Context): Bitmap? = runCatching {
        val f = file(context)
        if (!f.exists()) null else BitmapFactory.decodeFile(f.absolutePath)
    }.getOrNull()

    /** 把相册里的图存成壁纸；失败保持原样 */
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

        // 等比缩到长边 ≤ MAX_EDGE，不裁：壁纸要按原比例铺满
        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true
            )
        } else {
            decoded
        }

        FileOutputStream(file(context)).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        }

        if (scaled !== decoded) scaled.recycle()
        decoded.recycle()

        true
    }.onFailure { AppLog.w(TAG, "保存壁纸失败: ${it.message}") }
        .getOrDefault(false)

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    /** 2 的幂次采样，保证解码后仍比 MAX_EDGE 大 */
    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= MAX_EDGE) {
            longest /= 2
            sample *= 2
        }
        return sample
    }
}
