package com.bistu.allthebistu.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）表面。
 *
 * ## 为什么是「画」出来的，不是「模糊」出来的
 *
 * Compose 没有原生的「背景模糊」（backdrop blur）。真要把底下的内容模糊掉只有三条路，
 * 代价都不小：
 *  - `Haze` 之类的第三方库：多一个依赖，还要往外传「哪些节点算背景」
 *  - Android 12 的 `RenderEffect`：得先截一张背后的位图再模糊，滚动时逐帧截图
 *  - `Window.setBackgroundBlurRadius`：只对 Dialog / Window 生效，浮层里用不了
 *
 * 而玻璃的「像」其实不靠模糊，靠下面四样东西——它们全都能纯绘制，零依赖零开销：
 *
 *  1. **体色必须比背景暗一档**。这条最容易做错：浅色模式下直觉会填纯白，
 *     但纯白叠在 `#F5F5F7` 上算出来还是 `#FFFFFF` 附近，和卡片糊成一片，等于没做。
 *     必须用冷灰（`#E6EBF3`）做体，才压得住。
 *  2. **厚度感**：一条贯穿全高的渐变——上沿镜面高光、中段近乎全透、下沿冷色吸收。
 *     亮带必须集中在顶部 12% 左右；摊到 40% 就变渐变色块，不像玻璃。
 *  3. **镜面边缘**：外侧一圈极淡的深色发丝线（跟背景划清界限），
 *     内侧一圈白色高光弧（玻璃倒角处的全反射）。**浅色背景上这层才是灵魂**——
 *     白描边混在白底里看不见，必须靠外侧那道深线勾出轮廓。
 *  4. **悬浮**：交给调用方的 [Modifier.shadow]，不在本函数里画。
 *
 * ## 用法
 *
 * ```kotlin
 * Box(
 *     Modifier
 *         .shadow(18.dp, shape, clip = false, ambientColor = ..., spotColor = ...)
 *         .liquidGlass(shape, isDark = false)
 * ) { ... }
 * ```
 *
 * modifier 顺序：`shadow` 必须在 `liquidGlass` **前面**（更外层），否则阴影会盖在玻璃上。
 *
 * ## 为什么用 drawWithCache
 *
 * 底部导航的药丸在拖动时**每帧**都在重绘。`drawWithCache` 能把 Path、Brush、Stroke
 * 这些只跟尺寸有关的东西缓存下来，只在 size / 布局方向变化时才重建；
 * 换成 `drawWithContent` 就得每帧重新构造一遍，白扔 GC。
 *
 * @param shape 玻璃外轮廓。胶囊传 `RoundedCornerShape(height / 2)`。
 * @param isDark 深色模式。深色下体色、高光、描边方向全部不同，不能只换 alpha。
 * @param bodyColor 体色。传 null 用默认（浅色冷灰 / 深色偏亮的灰）。
 *   底栏上的选中药丸需要比底栏本身更亮，就靠这个参数区分。
 * @param bodyAlpha 玻璃体不透明度。越低越透（能看见背后滚动的文字），
 *   但太低会显脏。0.66~0.82 是可用区间。
 * @param edgeWidth 边缘线宽度。胶囊圆角大，超过 1.5dp 会显得笨重。
 */
fun Modifier.liquidGlass(
    shape: Shape,
    isDark: Boolean,
    bodyColor: Color? = null,
    bodyAlpha: Float = if (isDark) 0.78f else 0.72f,
    edgeWidth: Dp = 1.2.dp,
): Modifier = this.drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = outline.toPath()
    val w = size.width
    val h = size.height
    val edgePx = edgeWidth.toPx()

    // ── ① 玻璃体 ──
    // 浅色用冷灰而**不是**白：#E6EBF3 叠在 #F5F5F7 上算出来约 #E9ECF2，
    // 比纯白卡片暗一档，比页面底色也暗一档，一眼就能分出「这是浮在上面的一层」。
    val body = (bodyColor ?: if (isDark) Color(0xFF2A2A32) else Color(0xFFE6EBF3))
        .copy(alpha = bodyAlpha)

    // ── ② 厚度渐变，五个停靠点各管一件事 ──
    //   0%   上沿镜面（玻璃最厚处反光）
    //   12%  已经衰减掉大半 —— 必须窄，宽了就像渐变色块
    //   45%  几乎全透，让背后的内容透出来
    //   72%  完全透明
    //   100% 下沿——浅色用冷色「吸收」（玻璃边缘变暗），深色用微白反光
    val sheen = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = if (isDark) 0.16f else 0.46f),
        0.12f to Color.White.copy(alpha = if (isDark) 0.04f else 0.16f),
        0.45f to Color.White.copy(alpha = if (isDark) 0.01f else 0.02f),
        0.72f to Color.Transparent,
        1.00f to if (isDark) Color.White.copy(alpha = 0.05f)
        else Color(0xFFBFC9DA).copy(alpha = 0.30f),
        startY = 0f,
        endY = h
    )

    // 左右两端的内壁高光：圆角最大的地方最像玻璃倒角
    val sideSheen = Brush.horizontalGradient(
        0f to Color.White.copy(alpha = if (isDark) 0.09f else 0.40f),
        0.10f to Color.Transparent,
        0.90f to Color.Transparent,
        1f to Color.White.copy(alpha = if (isDark) 0.09f else 0.40f)
    )

    // ── ③ 外层轮廓线：浅色下必须用深色，否则白线在白背景上看不见 ──
    //    刻意很淡（0.09）：它只负责跟背景划清界限，重了就变描边色块
    val hairline = if (isDark) Color.White.copy(alpha = 0.12f)
    else Color(0xFF1C1C1E).copy(alpha = 0.09f)

    // ── ④ 内沿镜面高光 ──
    //    前 25% 保持高亮、之后才快速衰减，边缘才有一条锐利的亮线；
    //    从顶部一路线性衰减的话整圈都是半亮的，看起来就是灰塑料
    val rim = Brush.verticalGradient(
        0.00f to Color.White.copy(alpha = if (isDark) 0.30f else 1.00f),
        0.25f to Color.White.copy(alpha = if (isDark) 0.16f else 0.72f),
        0.60f to Color.White.copy(alpha = if (isDark) 0.06f else 0.26f),
        1.00f to Color.White.copy(alpha = if (isDark) 0.03f else 0.08f),
        startY = 0f,
        endY = h
    )

    val outerStroke = Stroke(width = edgePx)
    // Stroke 是「居中」描的，一半落在玻璃外面会把轮廓画糊 → 描两倍宽再裁，只留内侧
    val rimStroke = Stroke(width = edgePx * 2f)

    onDrawWithContent {
        drawPath(path, body)

        // 高光全部裁在玻璃体内，不会溢出圆角
        clipPath(path) {
            drawRect(brush = sheen, size = Size(w, h))
            drawRect(brush = sideSheen, size = Size(w, h))
        }

        drawPath(path = path, color = hairline, style = outerStroke)

        clipPath(path) {
            drawPath(path = path, brush = rim, style = rimStroke)
        }

        drawContent()
    }
}

/** `Outline` 转 `Path`，为了能用 `clipPath` / `drawPath`。三种形态都要覆盖。 */
private fun Outline.toPath(): Path = Path().apply {
    when (this@toPath) {
        is Outline.Rectangle -> addRect(rect)
        is Outline.Rounded -> addRoundRect(roundRect)
        is Outline.Generic -> addPath(path)
    }
}
