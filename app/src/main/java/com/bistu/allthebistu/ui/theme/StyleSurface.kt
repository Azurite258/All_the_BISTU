package com.bistu.allthebistu.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * 卡片底：按当前界面风格决定「阴影 / 圆角 / 描边」三件事。
 *
 * 为什么要有它：六套风格的层次手段不一样——HyperOS 靠阴影浮起来，
 * Linear / 极简 / M3 / iOS 靠 1dp 描边，而这三件事散落在每个页面的
 * `.clip().background()` 里各写一遍，结果就是「只有颜色跟着换了」。
 * 统一走这里，加新风格时不用回头改每个页面。
 *
 * 用法：
 * ```
 * Column(Modifier.fillMaxWidth().styleCard().padding(16.dp)) { … }
 * ```
 */
@Composable
fun Modifier.styleCard(
    shape: Shape = RoundedCornerShape(LocalUiMetrics.current.cardRadius),
    color: Color = defaultCardColor(),
    borderColor: Color = defaultCardBorderColor(),
): Modifier {
    val m = LocalUiMetrics.current
    return this
        .then(
            if (m.useShadow && m.cardElevation > 0.dp) {
                // 不显式给 spotColor 的话，Compose 默认阴影在浅色底上几乎看不见，
                // 深色主题里又会糊成一圈灰晕。这里两个色都压得比较克制。
                Modifier.shadow(
                    elevation = m.cardElevation,
                    shape = shape,
                    clip = false,
                    ambientColor = Color(0x1F000000),
                    spotColor = Color(0x33000000)
                )
            } else {
                Modifier
            }
        )
        .clip(shape)
        .background(color)
        .then(if (m.useBorder) Modifier.border(1.dp, borderColor, shape) else Modifier)
}

/**
 * 卡片默认描边色。
 *
 * 液态玻璃的边必须是**近白**（方向稿 `rgba(255,255,255,.66)`）：玻璃的边缘高光
 * 来自折射，用灰色 outline 画出来就成了普通描边，一眼看出「不是玻璃」。
 */
@Composable
private fun defaultCardBorderColor(): Color =
    if (LocalUiMetrics.current.glowBackdrop) {
        Color.White.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.outline
    }

/**
 * 卡片默认底色。
 *
 * 液态玻璃风格（iOS）要**半透明**，否则卡片是块实心白板，底下的彩色光晕一点都透不上来，
 * 「玻璃」就名不副实。其余风格是实色。
 *
 * 例外：设了壁纸时一律实色。照片的明暗不可控，半透明卡片压在照片上文字会飘。
 */
@Composable
private fun defaultCardColor(): Color {
    val surface = MaterialTheme.colorScheme.surface
    return when {
        AppWallpaper.enabled -> surface
        LocalUiMetrics.current.glowBackdrop -> surface.copy(alpha = 0.64f)
        else -> surface
    }
}

/**
 * 页面背景的彩色光晕（目前只有 iOS 液态玻璃用）。
 *
 * 液态玻璃的前提是**底下得有颜色可透**。只写半透明 + 模糊，透出来的是一片白，
 * 看起来就是「白色卡片」，跟普通浅色主题没区别。所以这里铺三层径向色晕
 * （蓝 / 粉 / 绿），玻璃才有东西可以折射。
 *
 * 深色模式下换成深蓝 / 深紫 / 墨绿：同样的位置、同样的半径，只是亮度压到
 * 不会把浅色文字吃掉的程度。
 */
@Composable
fun StyleGlow(modifier: Modifier = Modifier) {
    val m = LocalUiMetrics.current
    if (!m.glowBackdrop) return

    val base = MaterialTheme.colorScheme.background
    val isDark = base.luminance() < 0.5f
    val glowA = if (isDark) Color(0xFF16294A) else Color(0xFFD3E4FF)
    val glowB = if (isDark) Color(0xFF3D1B2E) else Color(0xFFFFDAEE)
    val glowC = if (isDark) Color(0xFF123321) else Color(0xFFD9F2E2)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // 先铺底色：光晕是叠在主题背景上的，不能只画三个渐变（边缘会露出下层 surface 的白）
        drawRect(color = base)

        fun glow(cx: Float, cy: Float, radius: Float, color: Color) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = radius
                )
            )
        }

        // 位置对着方向稿里的三个椭圆：左上蓝、右上粉、底部绿
        glow(w * 0.12f, h * 0.02f, w * 1.18f, glowA)
        glow(w * 0.96f, h * 0.26f, w * 1.10f, glowB)
        glow(w * 0.44f, h * 1.06f, w * 1.28f, glowC)
    }
}
