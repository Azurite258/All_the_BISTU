package com.bistu.allthebistu.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ═══════════════════════════════════════════════════════════════
// HyperOS 配色
// 参考小米澎湃OS设计规范：高对比、低饱和、留白充足
// ═══════════════════════════════════════════════════════════════

// ── 浅色模式 ──
val HyperPrimary = Color(0xFF3482FF)          // HyperOS 蓝
val HyperPrimaryDark = Color(0xFF2B6DD8)
val HyperBackground = Color(0xFFF5F5F7)       // 页面底色（浅灰，让白卡片浮起来）
val HyperSurface = Color(0xFFFFFFFF)          // 卡片底色（纯白）
val HyperSurfaceVariant = Color(0xFFF0F0F2)   // 次级卡片
val HyperOnBackground = Color(0xFF000000)     // 主文字（纯黑）
val HyperOnSurfaceVariant = Color(0xFF8C8C8C) // 次要文字（灰）
val HyperOutline = Color(0xFFE5E5E5)          // 分割线

// ── 深色模式 ──
val HyperPrimaryDarkMode = Color(0xFF6BA5FF)
val HyperBackgroundDark = Color(0xFF000000)   // 纯黑背景（OLED 省电）
val HyperSurfaceDark = Color(0xFF1C1C1E)      // 深灰卡片
val HyperSurfaceVariantDark = Color(0xFF2C2C2E)
val HyperOnBackgroundDark = Color(0xFFFFFFFF)
val HyperOnSurfaceVariantDark = Color(0xFF8E8E93)
val HyperOutlineDark = Color(0xFF38383A)

// ── 功能色 ──
val HyperRed = Color(0xFFFF3B30)
val HyperGreen = Color(0xFF34C759)
val HyperOrange = Color(0xFFFF9500)
val HyperYellow = Color(0xFFFFCC00)

// ═══════════════════════════════════════════════════════════════
// HyperOS 圆角规范
// 小米官方建议：图片圆角 = 尺寸的 1/4
// 卡片统一用 20dp，小组件用 16dp
// ═══════════════════════════════════════════════════════════════

val HyperShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),        // 标准卡片
    extraLarge = RoundedCornerShape(28.dp)    // 大卡片 / 弹窗
)

// ═══════════════════════════════════════════════════════════════
// HyperOS 间距规范
// ═══════════════════════════════════════════════════════════════

object HyperSpacing {
    /** 页面左右边距 */
    val pageHorizontal = 16.dp
    /** 卡片内部 padding */
    val cardPadding = 16.dp
    /** 卡片之间的间距 */
    val cardGap = 12.dp
    /** 小元素间距 */
    val small = 8.dp
    /** 中等间距 */
    val medium = 12.dp
    /** 大间距 */
    val large = 20.dp
}
