package com.bistu.allthebistu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 滚轮选择器（就是系统闹钟「添加闹钟」那个样子）：中间一行高亮，上下渐隐。
 *
 * 为什么不用滑杆：49 档的滑杆每拖过一档都要回调一次，上层得去抖写盘、
 * 重排闹钟，手指一快就跟不上，表现出来就是「拖不动 + 卡」。
 * 滚轮靠惯性和吸附自己滑，**只在停下时回调一次**，中间过程一个回调都不发。
 *
 * ## 居中的实现（这里踩过坑，写清楚免得以后改回去）
 *
 * 一开始用 `contentPadding` 上下各留 `half * itemHeight` 让首尾项能停在正中，
 * 再用 `firstVisibleItemIndex + round(offset / itemHeight)` 算中间项，**这是错的**：
 * 带 padding 时，列表在起点（padding 还没滚完）和滚过 padding 之后，
 * 这两个值的语义不一样。实测日志：
 *
 *     起点（选中第 0 项）：first=0 off=0，中间项其实就是 0
 *     选中第 3 项：first=1 off=0，中间项却是 3
 *
 * 同一个 `first=1/off=0` 对应不同结果，无论怎么调公式都救不回来。
 * 表现出来就是「打开时显示 15 分钟，重开一次变回 5 分钟」——
 * 轮子把用户存的设置改掉了。
 *
 * 现在改成在列表**前后各插入 `half` 个等高空白项**，不用 padding。
 * 这样任何时刻都有 `S = first * H + offset`，
 * 中间项（内容下标）= `first + round(offset / H) - half`，
 * 且 `scrollToItem(k, 0)` 恰好就是「让第 k 项居中」，不用补差。
 *
 * @param index 当前选中的项下标
 * @param count 总项数，至少 1
 * @param onIndexChange 停下后回调（拖动过程中不回调）
 * @param formatter 数值 → 显示文本
 * @param enabled 关掉时禁用滚动并降低透明度
 */
@Composable
fun WheelPicker(
    index: Int,
    count: Int,
    onIndexChange: (Int) -> Unit,
    formatter: (Int) -> String,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 38.dp,
    visibleItems: Int = 5,
    enabled: Boolean = true
) {
    val lastIndex = (count - 1).coerceAtLeast(0)
    val safeIndex = index.coerceIn(0, lastIndex)
    val half = (visibleItems / 2).coerceAtLeast(0)
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    // 前后各 half 个空白项，用来替代 contentPadding（原因见类注释）
    val leading = half
    val totalItems = count + half * 2

    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = safeIndex.coerceIn(0, (totalItems - 1).coerceAtLeast(0)),
        initialFirstVisibleItemScrollOffset = 0
    )
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()

    // 回调可能在组合更新后才执行，用 rememberUpdatedState 保证拿到最新 lambda，
    // 否则会调用首次组合时那个把旧值又写回去的闭包
    val currentOnChange by rememberUpdatedState(onIndexChange)
    val currentSafe by rememberUpdatedState(safeIndex)

    // 中间项的内容下标。
    // 注意**不要再减 leading**：滚动量 S = k * H 时第 k 项正好居中，
    // 而前导空白项让 first == k 在这个位置成立，所以 first + round(off/H) 就是答案。
    // 减掉 leading 会让「打开时 15 分钟被读成 5 分钟」。
    val centered by remember {
        derivedStateOf {
            val first = state.firstVisibleItemIndex
            val offset = state.firstVisibleItemScrollOffset
            (first + (offset / itemHeightPx).roundToInt()).coerceIn(0, lastIndex)
        }
    }

    // 只在用户手势停下来时上报一次
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                val picked = centered
                if (picked != currentSafe) currentOnChange(picked)
            }
        }
    }

    // 外部值变了（联动、脏数据被清洗）→ 把轮子滚过去
    LaunchedEffect(safeIndex, lastIndex) {
        if (state.isScrollInProgress) return@LaunchedEffect
        if (centered != safeIndex) {
            state.scrollToItem(safeIndex.coerceIn(0, totalItems - 1), 0)
        }
    }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        userScrollEnabled = enabled,
        modifier = modifier
            .height(itemHeight * visibleItems)
            // DstIn 必须画在独立离屏图层里。
            // 少了这一句，混合会作用到下面已经绘制的内容上——
            // 弹窗自己的背景被渐变擦成透明，底下的课表直接透上来，
            // 看起来就像弹窗是半透明的。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                // 上下两行用 DstIn 擦掉透明度：保留本来的底色，
                // 不用去猜「弹窗背景到底是什么颜色」
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.30f to Color.Black,
                        0.70f to Color.Black,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
    ) {
        items(totalItems) { i ->
            val value = i - leading
            if (value < 0 || value >= count) {
                // 占位项：让首尾的值也能滚到中间，视觉上是一片空白
                Spacer(Modifier.fillMaxWidth().height(itemHeight))
                return@items
            }
            val selected = value == centered
            val distance = abs(value - centered)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable(enabled = enabled) {
                        // 点哪一行就滚到哪一行，省得非要在小小一行字上精确拖
                        scope.launch { state.animateScrollToItem(i, 0) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatter(value),
                    fontSize = if (selected) 24.sp else 18.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = colors.onSurface.copy(
                        alpha = when {
                            !enabled -> 0.25f
                            distance == 0 -> 1f
                            distance == 1 -> 0.5f
                            else -> 0.26f
                        }
                    )
                )
            }
        }
    }
}

/**
 * 带标题的一列滚轮（「小时」「分钟」）。
 *
 * 单独拆出来只是为了两列共用同样的宽度和标题字号，
 * 逻辑全在 [WheelPicker] 里。
 */
@Composable
fun WheelColumn(
    label: String,
    index: Int,
    count: Int,
    onIndexChange: (Int) -> Unit,
    formatter: (Int) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WheelPicker(
            index = index,
            count = count,
            onIndexChange = onIndexChange,
            formatter = formatter,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
