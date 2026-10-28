package com.bistu.allthebistu.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.bistu.allthebistu.data.prefs.WallpaperStore
import com.bistu.allthebistu.data.prefs.WallpaperTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 全局壁纸状态。
 *
 * 壁纸画在**最底层**（[WallpaperBackdrop]），各页面的背景色在壁纸开启时改成透明，
 * 卡片仍然是实色 —— 所以文字对比度靠的是「蒙层 + 卡片不透明」，不是靠调字号。
 *
 * 存的是文件路径而不是 Bitmap：Bitmap 常驻会把「换完壁纸还显示旧的」这类问题
 * 变得隐蔽，也让内存里多压一张全屏图。[version] 只在真正换了图时 +1，
 * 界面用它当 key 重新解码。
 */
object AppWallpaper {

    /** 图片路径；null 表示没设壁纸 */
    var path by mutableStateOf<String?>(null)
        private set

    /** 换图/清除时 +1，界面用作解码 key */
    var version by mutableIntStateOf(0)
        private set

    val enabled: Boolean get() = path != null

    /** 启动时调用；之后都由 [set] / [clear] 维护 */
    fun refresh(context: Context) {
        path = WallpaperStore.file(context).takeIf { it.exists() }?.absolutePath
        version++
    }

    /** 选好图后调用；失败返回 false，界面可以提示 */
    fun set(context: Context, uri: Uri): Boolean {
        val ok = WallpaperStore.save(context, uri)
        if (ok) refresh(context)
        return ok
    }

    fun clear(context: Context) {
        WallpaperStore.clear(context)
        refresh(context)
    }
}

/**
 * 页面根背景色。
 *
 * 没壁纸时就是主题背景色；有壁纸时透明，让下层的壁纸露出来。
 * **只该用在页面根布局上**，卡片请继续用 `colorScheme.surface`（否则文字会糊在图上）。
 */
@Composable
fun pageBackgroundColor(): Color =
    if (AppWallpaper.enabled) Color.Transparent else MaterialTheme.colorScheme.background

/**
 * 壁纸图层：图片铺满 + 一层主题色蒙层。
 *
 * 蒙层不是装饰，是**可读性兜底**：不管用户选了什么花色的图，
 * 正文永远压在至少 70% 的主题底色上，对比度不会崩。
 * 深色模式蒙得略轻（0.70），浅色模式重一点（0.80），因为浅色下花色更容易把黑字吃掉。
 */
@Composable
fun WallpaperBackdrop(
    modifier: Modifier = Modifier,
    scrim: Int = WallpaperTone.DEFAULT,
) {
    if (!AppWallpaper.enabled) return

    val context = LocalContext.current
    val version = AppWallpaper.version
    val bitmap by produceState<ImageBitmap?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                WallpaperStore.load(context)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val base = MaterialTheme.colorScheme.background
    // 0..100 直接映射成 0..1 的蒙层不透明度。用户调的就是「图能看清多少」，
    // 拖到 0 时照片完全露出，字能不能看清由用户自己权衡。
    val scrimAlpha = WallpaperTone.sanitize(scrim) / 100f

    Box(modifier = modifier.fillMaxSize().background(base)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(Modifier.fillMaxSize().background(base.copy(alpha = scrimAlpha)))
    }
}

/**
 * 卡片底色。
 *
 * 默认就是 `surface`（不透明）。**课表那种占满整屏的容器**才需要传 alpha，
 * 让它透一点壁纸出来；普通卡片保持不透明，长文字压在图上看不清。
 */
@Composable
fun cardSurfaceColor(alpha: Float = 0.82f): Color =
    if (AppWallpaper.enabled) {
        MaterialTheme.colorScheme.surface.copy(alpha = alpha)
    } else {
        MaterialTheme.colorScheme.surface
    }

/** 壁纸预览缩略图用的解码（界面里 56dp 的小方块，直接给原图即可，Compose 自己会缩） */
@Composable
fun rememberWallpaperPreview(): ImageBitmap? {
    if (!AppWallpaper.enabled) return null
    val context = LocalContext.current
    val version = AppWallpaper.version
    val bitmap by produceState<ImageBitmap?>(initialValue = null, version) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(AppWallpaper.path)?.asImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}
