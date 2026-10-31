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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 卡片底：按当前界面风格决定「阴影 / 圆角 / 描边 / 顶部内高光」。
 *
 * 方向稿里六套风格分层手段不同（见 [CardStyle]），而这三件事原来散落在每个页面的
 * `.clip().background()` 里各写一遍，结果就是「只有颜色跟着换了」。统一走这里。
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
            if (m.usesShadow && m.cardElevation > 0.dp) {
                // Compose 的阴影只有一个 elevation，方向稿的 box-shadow 是两层，
                // 取大范围那层的量级；两个色都压得克制，深色主题里不会糊成一圈灰晕
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
        .then(
            // 卡片底是不透明的（见 defaultCardColor 的注释），这里再叠一层极淡的冷/暖渐变，
            // 模拟「透出一点环境光」。它是一条平滑的线性渐变，且 alpha 只有 5%~7%，
            // 即使背景怎么变，也不会在卡片里形成能看见的台阶 —— 这就是和 alpha 混合的区别。
            if (m.cardStyle == CardStyle.GLASS) {
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Modifier.background(
                    Brush.linearGradient(
                        listOf(
                            (if (isDark) Color(0xFF16294A) else Color(0xFFD3E4FF))
                                .copy(alpha = if (isDark) 0.22f else 0.07f),
                            Color.Transparent,
                            (if (isDark) Color(0xFF3D1B2E) else Color(0xFFFFDAEE))
                                .copy(alpha = if (isDark) 0.18f else 0.05f)
                        )
                    ),
                    shape
                )
            } else {
                Modifier
            }
        )
        .then(if (m.usesBorder) Modifier.border(1.dp, borderColor, shape) else Modifier)
        .then(
            // iOS 液态玻璃的「厚度」全靠这一条内高光：`inset 0 1px 0 rgba(255,255,255,.94)`。
            // 0.19-i 一度压到 0.55，玻璃立刻变得像塑料板，退回到 0.86/0.26。
            // 深色下仍然压暗，否则卡片四周会发光。
            if (m.cardStyle == CardStyle.GLASS) {
                val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                Modifier.glassEdge(
                    m.cardRadius,
                    topAlpha = if (isDark) 0.18f else 0.86f,
                    bottomAlpha = if (isDark) 0.04f else 0.26f
                )
            } else {
                Modifier
            }
        )
}

/**
 * 顶部亮线 + 底部弱反光。
 *
 * CSS 的 `inset` 阴影在 Compose 里没有对应能力，用一条竖直渐变的圆角描边模拟：
 * 上沿接近纯白、下沿只有 28% 白 —— 视觉上就是「一块有厚度的玻璃」。
 * 描边画在 clip 之内，所以圆角处会被自然裁切，不需要额外处理。
 */
fun Modifier.glassEdge(
    radius: Dp,
    topAlpha: Float = 0.94f,
    bottomAlpha: Float = 0.28f,
    strokeWidth: Dp = 1.dp
): Modifier = drawWithContent {
    drawContent()
    val w = strokeWidth.toPx()
    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = topAlpha),
                Color.White.copy(alpha = 0f),
                Color.White.copy(alpha = bottomAlpha)
            )
        ),
        topLeft = Offset(w / 2f, w / 2f),
        size = Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2f),
        style = Stroke(width = w)
    )
}

/**
 * 把半透明色合成到页面底色上，得到一个不透明版本。
 *
 * iOS 的 `--surface` / `--surface-2` 是半透明白（`rgba(255,255,255,.55)`），
 * 铺在卡片上正好能透出底下的光晕，这是想要的效果；
 * 但**浮层**（登录提示条、弹窗、菜单）不能透 —— 一透就能看见下面滚动的课表，
 * 字压在格子上完全没法读。这两个场景必须用合成后的实色。
 */
@Composable
fun opaqueSurface(base: Color): Color {
    if (base.alpha >= 0.999f) return base
    val bg = MaterialTheme.colorScheme.background
    return Color(
        red = base.red * base.alpha + bg.red * (1f - base.alpha),
        green = base.green * base.alpha + bg.green * (1f - base.alpha),
        blue = base.blue * base.alpha + bg.blue * (1f - base.alpha),
        alpha = 1f
    )
}

/**
 * 卡片体色 —— **全 App 的卡片都从这里取**，不允许各页面自己写。
 *
 * 玻璃风格（iOS）返回半透明白：壁纸、彩色光晕能透上来，卡片才像玻璃，而不是一块
 * 和背景无关的实色板。非玻璃风格保持 `surface` 实色（那五套本来就没有玻璃语言）。
 *
 * ⚠️ 这里换过两次方案，两次都是被同一个现象逼回来的：
 *
 * 1. 0.19-i 之前直接用 `surface`（iOS 是白 55%）——页面光晕是一大片渐变，卡片一透就把它
 *    原样透进来，同一张卡上半截 217、下半截 214，差 3 个色阶，手机上看着像卡里嵌了块白斑。
 * 2. 于是改成 [opaqueSurface] 混成实色：干净了，但深色下等于一块不透明的深灰板。
 *    生活页当时用的是 Material3 `Card`（`containerColor = surface`），和课表卡并排一看
 *    就是两种材质，用户的原话是「生活页的卡片完全不透明，其他的有透明感觉」。
 * 3. 现在的做法：**透明度交给一层"薄白光"**，白 alpha 很低，光晕透过来的量很小（不会出斑），
 *    但它确实会随背景变色 —— 深色下按下限 0.085（约等于原来 #1C1C1E 的观感，但会透色），
 *    浅色 0.62。有壁纸时抬到 0.16 / 0.74，保证照片上的字还读得清。
 *
 * 玻璃的「厚度」仍然交给 `glassEdge` 上沿高光 + 投影 + 发丝描边，那三样才是液态玻璃的辨识点。
 */
@Composable
fun cardBodyColor(): Color {
    val m = LocalUiMetrics.current
    if (m.cardStyle != CardStyle.GLASS) {
        // 其余五套是实色卡（阴影卡 / 描边卡 / 色调卡），玻璃语义不适用
        return MaterialTheme.colorScheme.surface
    }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val wallpaper = AppWallpaper.enabled
    return if (isDark) {
        Color.White.copy(alpha = if (wallpaper) 0.16f else 0.085f)
    } else {
        Color.White.copy(alpha = if (wallpaper) 0.74f else 0.62f)
    }
}

/** 卡片默认底色。统一走 [cardBodyColor]，不再有第二套取色逻辑。 */
@Composable
private fun defaultCardColor(): Color = cardBodyColor()

/**
 * 玻璃件的「分界线」色。
 *
 * 浅色用**极淡的深色**、深色用**极淡的白色**。它是用来和背景划清界限的，
 * 不是高光 —— 高光交给 [glassEdge]。两者混用过一次（都给了白），
 * 结果每一张卡片和圆钮外面都套了两条白线，看着就是白色重影。
 */
@Composable
fun glassHairlineColor(): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) {
        Color.White.copy(alpha = 0.10f)
    } else {
        Color(0xFF1C1C1E).copy(alpha = 0.07f)
    }
}

/**
 * 卡片默认描边色。
 *
 * ⚠️ iOS 玻璃卡片**不能再用白色描边**。之前这里是 `rgba(255,255,255,.66)`，
 * 而下面 `glassEdge` 又会在同一圈内侧再画一条 94% 白的内高光 ——
 * 两条白线并排，在浅色页面上一眼就是「双线 / 白色重影」。
 *
 * 现在分工：描边只负责和背景划清界限，玻璃的厚度感全部交给 `glassEdge` 那一条。
 */
@Composable
private fun defaultCardBorderColor(): Color =
    if (LocalUiMetrics.current.glowBackdrop) {
        glassHairlineColor()
    } else {
        MaterialTheme.colorScheme.outline
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

        // 位置与半径照抄方向稿的三个 radial-gradient
        glow(w * 0.12f, h * 0.02f, w * 1.18f, glowA)
        glow(w * 0.96f, h * 0.26f, w * 1.10f, glowB)
        glow(w * 0.44f, h * 1.06f, w * 1.28f, glowC)
    }
}
