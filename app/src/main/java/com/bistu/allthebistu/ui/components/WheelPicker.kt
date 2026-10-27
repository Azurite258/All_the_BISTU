package com.bistu.allthebistu.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 滚轮选择器（就是系统闹钟「添加闹钟」那个样子）：中间一行高亮，上下渐隐。
 *
 * 为什么不用滑杆：49 档的滑杆每拖过一档都要回调一次，上层得去抖写盘、
 * 重排闹钟，手指一快就跟不上，表现出来就是「拖不动 + 卡」。
 * 滚轮靠惯性和吸附自己滑，**只在停下时回调一次**，中间过程一个回调都不发。
 *
 * 几个必须注意的实现细节：
 *  - 上下各留 `half * itemHeight` 的 contentPadding，让**首尾项也能停在正中**；
 *    没有这个 padding，0 和最大值永远滚不到中间那一行，用户根本选不到
 *  - 判断「哪一项在中间」用的是 `layoutInfo` 里各可见项占的区间去夹视口中点，
 *    而不是 `firstVisibleItemIndex + offset / itemHeight`：后者在有 contentPadding 时
 *    语义会跑偏（首项已经贴顶、padding 还没滚完的那一段算不清楚），
 *    典型症状就是「滚到头两项，选中的却永远是 0」
 *  - 外部值变化（比如「4 小时」把分钟轮锁成 0）时反向同步位置：
 *    先滚到大致位置，再按实际布局量把目标项挪到正中，不依赖上面的公式
 *  - 只在用户手停下时报值；同步滚动期间不报，避免回环
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

    val state = rememberLazyListState(
        initialFirstVisibleItemIndex = (safeIndex - half).coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = 0
    )
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    val scope = rememberCoroutineScope()

    // 回调可能在组合更新后才执行，用 rememberUpdatedState 保证拿到最新 lambda，
    // 否则会调用首次组合时那个把旧值又写回去的闭包
    val currentOnChange by rememberUpdatedState(onIndexChange)
    val currentSafe by rememberUpdatedState(safeIndex)

    // 哪一项正压在视口中间：看哪一项的区间包含中点，比任何偏移量推导都可靠
    val centered by remember {
        derivedStateOf {
            val info = state.layoutInfo
            if (info.visibleItemsInfo.isEmpty()) {
                currentSafe
            } else {
                val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo
                    .firstOrNull { mid >= it.offset && mid < it.offset + it.size }
                    ?.index ?: currentSafe
            }
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
        if (centered != safeIndex) state.centerOn(safeIndex, half)
    }

    LazyColumn(
        state = state,
        flingBehavior = fling,
        userScrollEnabled = enabled,
        contentPadding = PaddingValues(vertical = itemHeight * half),
        modifier = modifier
            .height(itemHeight * visibleItems)
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
        items(count) { i ->
            val selected = i == centered
            val distance = abs(i - centered)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .clickable(enabled = enabled) {
                        // 点哪一行就滚到哪一行，省得非要在小小一行字上精确拖
                        scope.launch { state.centerOn(i, half) }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = formatter(i),
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
 * 把第 [target] 项挪到视口正中。
 *
 * 分两步：先滚到「目标项以上 half 项」的位置（到边界会被夹住），
 * 再用 `scrollBy` 按**实测布局**补掉剩下的差值。
 * 之所以不一步到位：目标项在头两项或末两项时，单靠 `scrollToItem` 的
 * offset 参数表达不出来（会要求负数偏移），实测补差才是稳的。
 */
private suspend fun LazyListState.centerOn(target: Int, half: Int) {
    scrollToItem((target - half).coerceAtLeast(0), 0)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
    val delta = item.offset + item.size / 2 - mid
    if (delta != 0) scrollBy(delta.toFloat())
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
