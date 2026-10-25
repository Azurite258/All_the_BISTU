package com.bistu.allthebistu.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bistu.allthebistu.data.prefs.BottomBarStyle
import com.bistu.allthebistu.ui.components.liquidGlass
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 悬浮胶囊导航栏（HyperOS 风格，参考 iOS 分段控件的药丸式切换）。
 *
 * 造型：
 *  - 整条是一个大胶囊，浮在内容上方，带柔和阴影 + 发丝描边
 *  - 选中项是「药丸」高亮块，**圆角与容器完全同一种弧度**：
 *    容器高 66dp / 圆角 33dp，药丸高 56dp / 圆角 28dp，各自都是「半径 = 自身高度一半」
 *    的全胶囊形，视觉上圆度一致。以后调高度时两个圆角要一起改，别写成固定值。
 *  - 四个 Tab 竖排（图标在上、文字在下），未选中也显示文字，宽度不跳动
 *
 * 手感（对齐 iOS 分段控件）：
 *  - 按住药丸左右拖动 → 药丸跟手，跨过中线 Tab 立即变色（不等松手）
 *  - 松手 → 按位置四舍五入吸附；若带明显速度（快速轻扫）再按方向多进一格
 *  - 纵向滑动放行给外层滚动容器，不抢事件
 *
 * ── 改这个文件前必读的三个坑（都实际踩过）──
 *  1. `AwaitPointerEventScope` 是 @RestrictsSuspension 的，手势循环里**不能**直接调
 *     `Animatable.snapTo/animateTo`（编译报 Restricted suspending functions）。
 *     拖动跟手只写普通 state，松手后的动画交给外部 `scope.launch`。
 *  2. `pointerInput` 的 block 是长期协程，**直接捕获 currentTab 会永远拿到首次组合的值**，
 *     表现是「切到别的 Tab 后再也回不去课表」「药丸走到一半不归位」。
 *     必须用 `rememberUpdatedState` 读最新值。
 *  3. 药丸位置**只能有一个驱动源**。`LaunchedEffect` 的 key 只能有 selectedIndex；
 *     把 dragPos 也加进去，松手时的置空会重启 effect 并取消吸附动画，
 *     表现是「第一次能滑，之后全部失灵」。
 */
@Composable
fun FloatingBottomBar(
    currentTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    style: Int = BottomBarStyle.DEFAULT,
    modifier: Modifier = Modifier
) {
    val tabs = MainTab.entries
    val count = tabs.size
    val selectedIndex = tabs.indexOf(currentTab).coerceAtLeast(0)

    // 坑 2：手势协程活得比一次提交久，必须拿最新的选中项，不能靠闭包捕获
    val latestSelectedIndex by rememberUpdatedState(selectedIndex)

    val surfaceColor = MaterialTheme.colorScheme.surface
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 深色下药丸要更亮一点才看得出来，浅色下则要淡，否则像块灰砖
    val isDarkSurface = surfaceColor.luminance() < 0.5f

    val isGlass = style == BottomBarStyle.GLASS
    val hairline = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    // 扁平模式的选中块。比最初那版（浅色 0.12 / 深色 0.22）深一档——
    // 底栏本身是纯白，选中块太淡等于没选
    val flatPillColor = primaryColor.copy(alpha = if (isDarkSurface) 0.28f else 0.20f)

    val scope = rememberCoroutineScope()

    // 静止状态的药丸位置（以「第几个 Tab」为单位的浮点数）
    val indicatorPos = remember { Animatable(selectedIndex.toFloat()) }
    // 拖动中的跟手位置；非 null 即表示手指正按在底栏上滑动
    var dragPos by remember { mutableStateOf<Float?>(null) }
    // 拖动会话计数：快速连滑时丢弃上一次还没落地的收尾协程，避免它把新的 dragPos 清掉
    var dragSession by remember { mutableIntStateOf(0) }

    // 药丸实际显示位置：拖动时听手指的，其余时候听动画
    val displayPos = dragPos ?: indicatorPos.value

    // 吸附动画：damping 0.7 让药丸落位时轻微过冲再回弹，stiffness 340 保持跟手
    val settleSpec: AnimationSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 340f)

    // 坑 3：唯一驱动源，key 只能是 selectedIndex
    LaunchedEffect(selectedIndex) {
        if (dragPos == null && indicatorPos.targetValue != selectedIndex.toFloat()) {
            indicatorPos.animateTo(selectedIndex.toFloat(), settleSpec)
        }
    }

    // 容器 66dp → 圆角 33dp；药丸 56dp → 圆角 28dp（二者均为全胶囊）
    val barHeight = 66.dp
    val barShape = RoundedCornerShape(barHeight / 2)
    val innerPadding = 5.dp
    val pillHeight = barHeight - innerPadding * 2
    val pillShape = RoundedCornerShape(pillHeight / 2)

    Box(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .then(
                if (isGlass) {
                    Modifier
                        // 玻璃要「浮」起来：elevation 拉高、颜色压淡。
                        // 阴影画在玻璃体下面，半透明会让它透出一点灰——这正是厚度感的一部分，
                        // 但颜色太浓就变脏了。
                        .shadow(
                            elevation = 22.dp,
                            shape = barShape,
                            clip = false,
                            ambientColor = Color(0x1A000000),
                            spotColor = Color(0x30000000)
                        )
                        .liquidGlass(
                            shape = barShape,
                            isDark = isDarkSurface,
                            // 浅色体色比 LiquidGlass 的默认值再深一档（#E6EBF3 → #DDE4EF）：
                            // 默认值算出来约 #E9ECF2，和纯白卡片只差 6 个色阶，
                            // 用户反馈「对比度不够」，所以这里显式压深。
                            // 深色模式没动——它本来就靠「比黑底亮」来分离，加深反而消失。
                            bodyColor = if (isDarkSurface) null else Color(0xFFDDE4EF),
                            bodyAlpha = if (isDarkSurface) 0.78f else 0.80f
                        )
                } else {
                    // 扁平：不透明实心胶囊 + 发丝描边。就是液态玻璃之前的做法
                    Modifier
                        .shadow(
                            elevation = 14.dp,
                            shape = barShape,
                            clip = false,
                            ambientColor = Color(0x22000000),
                            spotColor = Color(0x33000000)
                        )
                        .background(surfaceColor, barShape)
                        .border(0.5.dp, hairline, barShape)
                }
            )
            .height(barHeight)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val itemWidth = maxWidth / count
            val itemWidthPx = with(LocalDensity.current) { itemWidth.toPx() }

            // 视觉选中项：跟随药丸取整，拖动跨过中线就整体变色。
            // 用 derivedStateOf 是为了拖动时不逐帧重组四个 Tab，而是跨格才重组。
            val visualIndex by remember(count) {
                derivedStateOf {
                    (dragPos ?: indicatorPos.value).roundToInt().coerceIn(0, count - 1)
                }
            }

            // ── 选中药丸：与容器同弧度的全胶囊 ──
            // 光效模式：比底栏再亮一档的另一块玻璃（小玻璃浮在大玻璃上）
            // 扁平模式：一块淡色底，选中语义主要由「图标文字变蓝 + 字重加粗」承担
            Box(
                modifier = Modifier
                    .offset { IntOffset((displayPos * itemWidthPx).roundToInt(), 0) }
                    .width(itemWidth)
                    .fillMaxHeight()
                    .padding(horizontal = 3.dp)
                    .then(
                        if (isGlass) {
                            Modifier.liquidGlass(
                                shape = pillShape,
                                isDark = isDarkSurface,
                                bodyColor = if (isDarkSurface) Color(0xFF4C4C57) else Color.White,
                                bodyAlpha = if (isDarkSurface) 0.90f else 0.88f,
                                edgeWidth = 0.8.dp
                            )
                        } else {
                            Modifier.background(flatPillColor, pillShape)
                        }
                    )
            )

            // ── 四个 Tab ──
            Row(modifier = Modifier.fillMaxSize()) {
                tabs.forEachIndexed { index, tab ->
                    val selected = index == visualIndex
                    val contentColor by animateColorAsState(
                        targetValue = if (selected) primaryColor else onSurfaceVariant,
                        animationSpec = tween(durationMillis = 160),
                        label = "navContentColor"
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = contentColor,
                            modifier = Modifier.size(21.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = contentColor
                        )
                    }
                }
            }

            // ── 手势层：点按选择 + 左右拖动选择 ──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(itemWidthPx, count) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val touchSlop = viewConfiguration.touchSlop
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPointerInputChange(down)

                            var dragged = false
                            var aborted = false
                            // 拖动起始所在的 Tab，轻扫时以它为基准只进一格
                            var dragStartIndex = selectedIndex

                            // 按下位置对应的 Tab，用于「点一下立刻选中」的兜底
                            val downIndex = (down.position.x / itemWidthPx)
                                .toInt()
                                .coerceIn(0, count - 1)

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                velocityTracker.addPointerInputChange(change)
                                if (!change.pressed) break

                                if (!dragged) {
                                    val dx = abs(change.position.x - down.position.x)
                                    val dy = abs(change.position.y - down.position.y)
                                    when {
                                        // 横向位移占优 → 认定是滑动选择
                                        dx > touchSlop && dx > dy -> {
                                            dragged = true
                                            // 从当前药丸位置起步，避免第一帧跳变
                                            dragPos = indicatorPos.value
                                            dragStartIndex = indicatorPos.value
                                                .roundToInt()
                                                .coerceIn(0, count - 1)
                                        }
                                        // 纵向位移占优 → 放行给外层滚动，别抢
                                        dy > touchSlop && dy > dx -> {
                                            aborted = true
                                            break
                                        }
                                    }
                                }

                                if (dragged) {
                                    change.consume()
                                    // 直接按手指绝对横坐标映射到 Tab 序号，比累加误差小
                                    dragPos = (change.position.x / itemWidthPx)
                                        .coerceIn(0f, (count - 1).toFloat())
                                }
                            }

                            val from = dragPos
                            if (dragged && from != null) {
                                // 落点判定，对齐 iOS 分段控件：
                                //  - 已经拖过半格 → 按实际位置四舍五入
                                //  - 没拖过半格但有明显速度 → 从起点只进一格（轻扫）
                                //  - 其余 → 回原位
                                val vx = velocityTracker.calculateVelocity().x
                                val moved = from - dragStartIndex
                                val target = when {
                                    abs(moved) >= 0.5f -> from.roundToInt()
                                    vx > FLING_VELOCITY -> dragStartIndex + 1
                                    vx < -FLING_VELOCITY -> dragStartIndex - 1
                                    else -> dragStartIndex
                                }.coerceIn(0, count - 1)

                                val session = ++dragSession
                                scope.launch {
                                    // 把动画基准对齐到松手位置，避免从上一个 Tab 跳过去
                                    indicatorPos.snapTo(from)
                                    // 被更新的一次拖动接管了，本协程作废（否则会把新的 dragPos 清掉）
                                    if (session != dragSession) return@launch
                                    dragPos = null
                                    if (target != latestSelectedIndex) {
                                        // 切页 → selectedIndex 变 → 由 LaunchedEffect 完成吸附
                                        onTabSelected(tabs[target])
                                    } else {
                                        // 滑回原位：没有切页事件，自己吸附回去
                                        indicatorPos.animateTo(target.toFloat(), settleSpec)
                                    }
                                }
                            } else if (!aborted) {
                                // 点按：由 LaunchedEffect 负责滑动动画
                                if (downIndex != latestSelectedIndex) {
                                    onTabSelected(tabs[downIndex])
                                }
                            }
                        }
                    }
            )
        }
    }
}

/** 轻扫判定阈值（px/s）。超过它，松手时按方向多进一格。调高 = 更不灵敏。 */
private const val FLING_VELOCITY = 1250f
