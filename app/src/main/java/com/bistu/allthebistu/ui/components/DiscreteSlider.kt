package com.bistu.allthebistu.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 分档滑块（Material 3 经典规格，非 1.4 的 expressive 竖条样式）。
 *
 * 为什么不直接用 [androidx.compose.material3.Slider]：
 * material3 1.4.0 的 Slider 默认换成了 expressive 风格——轨道变成细线、
 * 滑块变成一根竖条，放在这套 HyperOS 扁平卡片里非常突兀。
 * 这里按 M3 经典规范自己画一遍，参数对齐官方：
 *  - 轨道高 4dp，两端圆头
 *  - 滑块直径 20dp 实心圆（primary）
 *  - 按下时 40dp 的 12% 透明度光晕
 *  - 中间刻度点 4dp，落在已选区间内是白色
 *
 * 因为选项只有 2~3 个，拖动时直接吸附到最近的挡位，
 * 不做连续取值，避免出现「半档」这种没有意义的状态。
 *
 * @param value 当前挡位索引，0 起
 * @param onValueChange 挡位变化回调（只有真正改变时才回调）
 * @param steps 总挡位数
 */
@Composable
fun DiscreteSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 3,
    enabled: Boolean = true
) {
    val lastIndex = (steps - 1).coerceAtLeast(1)
    val safeValue = value.coerceIn(0, lastIndex)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current

    val thumbRadius = with(density) { 10.dp.toPx() }
    val trackHeight = with(density) { 4.dp.toPx() }
    val tickRadius = with(density) { 1.75.dp.toPx() }

    var pressed by remember { mutableStateOf(false) }

    val target = safeValue.toFloat() / lastIndex
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sliderFraction"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        val widthPx = constraints.maxWidth.toFloat().takeIf { it.isFinite() && it > 0f } ?: 1f
        // 滑块是圆的，两端要留出半径，否则拖到 0/100% 时圆点会被裁掉
        val startX = thumbRadius
        val endX = (widthPx - thumbRadius).coerceAtLeast(startX + 1f)
        val span = (endX - startX).coerceAtLeast(1f)

        fun valueAt(x: Float): Int {
            val f = ((x - startX) / span).coerceIn(0f, 1f)
            return (f * lastIndex).roundToInt()
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .pointerInput(enabled, startX, span, lastIndex, safeValue) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = { offset ->
                            val next = valueAt(offset.x)
                            if (next != safeValue) onValueChange(next)
                        }
                    )
                }
                .pointerInput(enabled, startX, span, lastIndex, safeValue) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            pressed = true
                            val next = valueAt(offset.x)
                            if (next != safeValue) onValueChange(next)
                        },
                        onDragEnd = { pressed = false },
                        onDragCancel = { pressed = false },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            val next = valueAt(change.position.x)
                            if (next != safeValue) onValueChange(next)
                        }
                    )
                }
        ) {
            val centerY = size.height / 2f
            val thumbX = startX + span * fraction

            // 未激活轨道
            drawLine(
                color = colors.onSurface.copy(alpha = 0.16f),
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            // 已激活轨道
            drawLine(
                color = colors.primary,
                start = Offset(startX, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            // 中间刻度点：落在已选区间内的用白色，衬托在蓝色轨道上
            for (i in 1 until lastIndex) {
                val tickX = startX + span * (i.toFloat() / lastIndex)
                drawCircle(
                    color = if (tickX <= thumbX) Color.White.copy(alpha = 0.9f)
                    else colors.onSurface.copy(alpha = 0.35f),
                    radius = tickRadius,
                    center = Offset(tickX, centerY)
                )
            }

            // 按下光晕（M3 的状态层）
            if (pressed) {
                drawCircle(
                    color = colors.primary.copy(alpha = 0.12f),
                    radius = thumbRadius * 2f,
                    center = Offset(thumbX, centerY)
                )
            }

            // 滑块本体
            drawCircle(
                color = if (enabled) colors.primary else colors.onSurface.copy(alpha = 0.38f),
                radius = thumbRadius,
                center = Offset(thumbX, centerY)
            )
        }
    }
}
