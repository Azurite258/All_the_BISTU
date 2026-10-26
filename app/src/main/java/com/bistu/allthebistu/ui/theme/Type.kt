package com.bistu.allthebistu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * HyperOS 字体规范。
 *
 * 特点：
 * - 标题超大加粗（首页标题 32sp）
 * - 字重对比明显（标题 Bold，正文 Normal）
 * - 行高宽松（正文 1.5 倍行距）
 *
 * ── 字体族统一由 [AppFont.family] 决定 ──
 * 设备自带 MiSans（小米 / 红米 / POCO）时全局换成 MiSans，否则就是系统默认字体。
 * 这里**不要**再写死 `FontFamily.Default`：那会让 App 在小米机型上
 * 明明有 MiSans 却用 Roboto/Noto，标题的字形和字距跟系统其他 App 对不上。
 *
 * 字重四档（Normal / Medium / SemiBold / Bold）与 [AppFont] 里声明的
 * `NEEDED_WEIGHTS` 一一对应，改动任何一边都要同步另一边。
 */
val Typography = Typography(
    // ── 超大标题（首页「课表」这类）──
    displayLarge = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),

    // ── 页面标题 ──
    headlineLarge = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),

    // ── 卡片标题 ──
    titleLarge = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    titleSmall = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),

    // ── 正文 ──
    bodyLarge = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),

    // ── 标签 / 按钮 ──
    labelLarge = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = AppFont.family,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)
