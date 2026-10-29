package com.bistu.allthebistu.ui.schedule

import androidx.compose.ui.graphics.Color
import com.bistu.allthebistu.data.prefs.CourseColorScheme
import com.bistu.allthebistu.ui.theme.CourseTone
import com.bistu.allthebistu.ui.theme.UiStyle
import kotlin.math.pow

/**
 * 课程配色方案。
 *
 * 三种方案：
 *   SCHOOL   — 用教务系统返回的原始颜色（浅粉、浅黄、浅蓝等）
 *   MORANDI  — 莫兰迪色系：奶油黄、珊瑚橙、牛油果绿这一挂，柔和低饱和
 *   DOPAMINE — 多巴胺色系：小米 15 自选色里那组亮色，冷杉绿、宝蓝、孔雀绿
 *
 * 两套色板的十六进制值不是拍脑袋调的，是从小米商城配色页逐块取色得到的：
 * 取色脚本对参考截图做了多点平均，三处采样完全一致，
 * 说明取到的是色块内部而不是渐变边缘。
 */
object CourseColors {

    /**
     * 课块色相池，**直接取自方向稿 `ui-lab/index.html` 里用到的 9 个 `--h` 值**。
     *
     * 方向稿的课块不是固定色板，而是 `hsl(h, --course-s, --course-l)`：
     * 同一套色相换不同的 s/l 就是一套新观感，所以六套风格共用这 9 个色相。
     * 顺序也是方向稿里的出现顺序，别按大小重排——重排会让同一个课程换风格后色相跳变。
     */
    private val COURSE_HUES = floatArrayOf(14f, 28f, 160f, 190f, 206f, 238f, 270f, 280f, 346f)

    /** 取第 [index] 门课的色相（循环使用色相池） */
    fun hueOf(index: Int): Float = COURSE_HUES[((index % COURSE_HUES.size) + COURSE_HUES.size) % COURSE_HUES.size]

    /** 跟随风格时的课块底色：`hsl(h, s, l)` */
    fun styleBackground(index: Int, tone: CourseTone): Color =
        Color.hsl(hueOf(index), tone.saturation, tone.lightness)

    /** 跟随风格时的课块主文字：`hsl(h, 48%, t)` */
    fun styleTextColor(index: Int, tone: CourseTone): Color =
        Color.hsl(hueOf(index), 0.48f, tone.textLightness)

    /** 跟随风格时的副文字（地点/教师），比主文字再淡一档但保持同色相 */
    fun styleSubTextColor(index: Int, tone: CourseTone): Color =
        Color.hsl(hueOf(index), 0.40f, if (tone.textLightness > 0.5f) 0.66f else 0.42f)

    // ── 莫兰迪色系 ──
    // 8 个色相 × 2 个明度层 = 16 色。
    // 为什么不沿用「8 色」：示例课表就有 14 门课，8 色必然有两门撞色
    // （实测「基础英语」和「高等数学(1)」拿到了同一个颜色）。
    //
    // 明度/饱和度是照着学校配色的手感定的：学校那套是「高明度 + 中高饱和」，
    // 所以看着清爽。莫兰迪取 s=0.50/l=0.86（第二层 s=0.35/l=0.79 更灰一点），
    // 比学校配色淡一档，但不再像之前那样糊成一片灰。
    private val MORANDI_LIGHT = listOf(
        "#EDDBC9", "#DCC9B7", // 橙调
        "#E4EDC9", "#D3DCB7", // 黄绿
        "#C9EDC9", "#B7DCB7", // 草绿
        "#C9EDE4", "#B7DCD3", // 薄荷
        "#C9DBED", "#B7C9DC", // 天蓝
        "#D2C9ED", "#C0B7DC", // 淡紫
        "#EDC9ED", "#DCB7DC", // 紫红
        "#EDC9D2", "#DCB7C0"  // 玫瑰
    )

    private val MORANDI_DARK = listOf(
        "#725437", "#72471D",
        "#637237", "#5D721D",
        "#377237", "#1D721D",
        "#377263", "#1D725D",
        "#375472", "#1D4772",
        "#453772", "#321D72",
        "#723772", "#721D72",
        "#723745", "#721D32"
    )

    // ── 多巴胺色系 ──
    // 同样 16 色。饱和度提到 0.92（第二层 0.90），明度 0.88/0.80，
    // 观感比莫兰迪艳一档，但明度和学校配色接近，不会出现之前那种深绿压屏。
    private val DOPAMINE_LIGHT = listOf(
        "#FDE0C4", "#FACC9E", // 蜜橘
        "#EEFDC4", "#E3FA9E", // 嫩芽黄
        "#C4FDC4", "#9EFA9E", // 青草绿
        "#C4FDEE", "#9EFAE3", // 孔雀绿
        "#C4E0FD", "#9ECCFA", // 宝蓝
        "#D2C4FD", "#B59EFA", // 葡萄紫
        "#FDC4FD", "#FA9EFA", // 荧光粉
        "#FDC4D2", "#FA9EB5"  // 树莓
    )

    private val DOPAMINE_DARK = listOf(
        "#774C22", "#54381C",
        "#627722", "#46541C",
        "#227722", "#1C541C",
        "#227762", "#1C5446",
        "#224C77", "#1C3854",
        "#372277", "#2A1C54",
        "#772277", "#541C54",
        "#772237", "#541C2A"
    )

    /** 浅色底上的深字（不用纯黑，避免过硬） */
    private val DarkText = Color(0xFF1F1D1B)

    /** 深色底上的浅字 */
    private val LightText = Color(0xFFFFFFFF)

    /**
     * 根据方案和主题，计算课程块背景色。
     *
     * @param originalHex 教务系统返回的原始颜色
     * @param courseName  课程名（用于稳定分配颜色，同名课程同色）
     * @param scheme      配色方案
     * @param isDark      是否深色模式
     */
    fun backgroundOf(
        originalHex: String,
        courseName: String,
        scheme: Int,
        isDark: Boolean,
        paletteIndex: Int? = null,
        style: Int = UiStyle.DEFAULT
    ): Color {
        // 优先用调用方给的顺序索引（课表页会按课程名排好序依次分配，
        // 这样 8 门课一定拿到 8 种颜色）；没有索引时才退回按名字哈希。
        val index = paletteIndex ?: stableIndex(courseName)

        return when (scheme) {
            // 跟随界面风格：按该风格的 HSL 参数实时算，与方向稿一比一
            CourseColorScheme.FOLLOW_STYLE ->
                styleBackground(index, UiStyle.courseTone(style, isDark))

            CourseColorScheme.MORANDI -> {
                val palette = if (isDark) MORANDI_DARK else MORANDI_LIGHT
                parse(palette[slotOf(index, palette.size)])
            }

            CourseColorScheme.DOPAMINE -> {
                val palette = if (isDark) DOPAMINE_DARK else DOPAMINE_LIGHT
                parse(palette[slotOf(index, palette.size)])
            }

            else -> { // SCHOOL
                val base = parse(originalHex)
                if (isDark) darken(base) else base
            }
        }
    }

    /**
     * 设置页的色卡预览。
     *
     * 学校配色没有固定色板，用四个真实感的浅色示例；
     * 其余两套直接取各自色板的前 4 个，所见即所得。
     */
    fun sampleSwatches(
        scheme: Int,
        isDark: Boolean,
        count: Int = 4,
        style: Int = UiStyle.DEFAULT
    ): List<Color> = when (scheme) {
        // 跟随风格：色卡直接按当前风格的 HSL 参数算，所见即所得
        CourseColorScheme.FOLLOW_STYLE ->
            (0 until count).map { styleBackground(it, UiStyle.courseTone(style, isDark)) }

        // 色板是「色相 × 明度」交错的，直接取前 N 个会全是同一两个色相，
        // 这里按步长跳着取，预览里能看出这是一套多色相色板
        CourseColorScheme.MORANDI -> spread(if (isDark) MORANDI_DARK else MORANDI_LIGHT, count)

        CourseColorScheme.DOPAMINE -> spread(if (isDark) DOPAMINE_DARK else DOPAMINE_LIGHT, count)

        else -> listOf("#FFF0CC", "#D3EAFD", "#E8DDF7", "#D9F0DC")
            .take(count)
            .map { if (isDark) darken(parse(it)) else parse(it) }
    }

    /** 从色板里等间隔取 count 个，供色卡预览用 */
    private fun spread(palette: List<String>, count: Int): List<Color> {
        if (palette.isEmpty() || count <= 0) return emptyList()
        val step = (palette.size / count).coerceAtLeast(1)
        return (0 until count).map { parse(palette[(it * step) % palette.size]) }
    }

    /** 把任意索引收敛到色板范围内 */
    private fun slotOf(index: Int, size: Int): Int = ((index % size) + size) % size

    /**
     * 给一批课程名分配色板索引：去重后按名字排序，依次编号。
     *
     * 顺序分配而不是各自哈希，是因为色板只有 8 色，
     * 哈希在 8 门课时大概率撞色（实测只能分出 5 种），
     * 而顺序分配能保证前 8 门课颜色各不相同。
     * 排序是为了让「同一门课在不同周/不同次加载」拿到同一个索引。
     */
    fun paletteIndexFor(courseNames: Collection<String>): Map<String, Int> =
        courseNames.distinct().sorted().withIndex().associate { (i, name) -> name to i }

    /** 把课程名映射到 [0, 1000) 的稳定索引 */
    private fun stableIndex(courseName: String): Int {
        if (courseName.isEmpty()) return 0
        // 简单稳定的哈希：逐字符累加，避免 hashCode 的负数问题
        var h = 0
        courseName.forEach { ch -> h = (h * 31 + ch.code) % 1000 }
        return (h + 1000) % 1000
    }

    /**
     * 课程块上的文字颜色。
     *
     * 不再用「亮度 > 0.6 就黑字」这种拍脑袋的阈值——多巴胺色系里
     * 宝蓝、孔雀绿这类中等明度的底色，简单阈值会选错方向导致对比度不够。
     * 这里分别算与白字、深字的 WCAG 对比度，取大的那个。
     */
    fun textColorOf(background: Color, isDark: Boolean): Color {
        val bgL = relativeLuminance(background)
        val contrastWithWhite = 1.05f / (bgL + 0.05f)
        val contrastWithDark = (bgL + 0.05f) / (relativeLuminance(DarkText) + 0.05f)
        return if (contrastWithDark >= contrastWithWhite) DarkText else LightText
    }

    /**
     * 课块描边色，照方向稿 `hsl(h, 40%, t / .13)`。
     *
     * 两处特例都是方向稿里明确的：
     *  - Linear 的描边更实（`/ .2`），它整套就是靠细边框分层
     *  - iOS 的课块是**白边 + 内高光**（`rgba(255,255,255,.55)`），
     *    这是液态玻璃「有厚度」的线索，换成同色相暗边会立刻变回普通卡片
     */
    fun styleBorderColor(index: Int, tone: CourseTone, style: Int): Color = when (style) {
        // 玻璃白边只对浅色课块成立：深色课块配 55% 白边会变成一圈发光的框
        UiStyle.IOS -> Color.White.copy(alpha = if (tone.lightness < 0.5f) 0.16f else 0.55f)
        UiStyle.LINEAR -> Color.hsl(hueOf(index), 0.40f, tone.textLightness).copy(alpha = 0.20f)
        else -> Color.hsl(hueOf(index), 0.40f, tone.textLightness).copy(alpha = 0.13f)
    }

    /**
     * 取一门课在色相池里的序号。
     *
     * 课表页会传排好序的 [paletteIndex]，弹窗和预览没有，退回按名字哈希，
     * 保证「同一门课在课表和详情弹窗里是同一个颜色」。
     */
    fun indexOf(courseName: String, paletteIndex: Int? = null): Int =
        paletteIndex ?: stableIndex(courseName)

    /**
     * 跟随风格时的文字配色（主文字 + 副文字）。
     *
     * 方向稿用 `hsl(h, 48%, t)` 给文字上同色相的暗色，这里照做；
     * 但某套风格的参数万一算出难读的组合，就用 WCAG 兜一道底：
     * 低于 4.5:1 退回按亮度挑黑/白的旧逻辑。
     */
    fun textPairStyle(
        index: Int,
        tone: CourseTone,
        background: Color,
        isDark: Boolean
    ): Pair<Color, Color> {
        val title = styleTextColor(index, tone)
        val sub = styleSubTextColor(index, tone)
        val titleOk = contrastRatio(title, background) >= 4.5f
        val subOk = contrastRatio(sub, background) >= 3f
        return when {
            titleOk && subOk -> title to sub
            titleOk -> title to subTextColorOf(background, isDark)
            else -> textColorOf(background, isDark) to subTextColorOf(background, isDark)
        }
    }

    /** 副文字颜色（教室、时间），按同一套判断跟着主文字走 */
    fun subTextColorOf(background: Color, isDark: Boolean): Color {
        val useDark = textColorOf(background, isDark) == DarkText
        return if (useDark) Color(0xFF4F4A45) else Color(0xFFE2E2E2)
    }

    /** 深色模式下把浅色调暗（只用于学校配色） */
    private fun darken(color: Color, factor: Float = 0.35f): Color {
        return Color(
            red = (color.red * factor).coerceIn(0f, 1f),
            green = (color.green * factor).coerceIn(0f, 1f),
            blue = (color.blue * factor).coerceIn(0f, 1f),
            alpha = 1f
        )
    }

    /**
     * 解析 "#RRGGBB" / "#AARRGGBB"。
     *
     * 不用 android.graphics.Color.parseColor 是为了能在 JVM 单元测试里跑
     * （Android 的实现是 stub，单测调用即抛异常）。
     */
    private fun parse(hex: String): Color {
        val cleaned = hex.removePrefix("#")
        return runCatching {
            val v = cleaned.toLong(16)
            if (cleaned.length == 8) {
                Color(
                    red = ((v shr 16) and 0xFF).toInt(),
                    green = ((v shr 8) and 0xFF).toInt(),
                    blue = (v and 0xFF).toInt(),
                    alpha = ((v shr 24) and 0xFF).toInt()
                )
            } else {
                Color(
                    red = ((v shr 16) and 0xFF).toInt(),
                    green = ((v shr 8) and 0xFF).toInt(),
                    blue = (v and 0xFF).toInt()
                )
            }
        }.getOrElse { Color(0xFFE0E0E0) }
    }

    /** WCAG 相对亮度，供文字配色与单元测试使用 */
    fun relativeLuminance(color: Color): Float {
        fun channel(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * channel(color.red) +
                0.7152f * channel(color.green) +
                0.0722f * channel(color.blue)
    }

    /** 两色间的 WCAG 对比度，供单元测试断言可读性 */
    fun contrastRatio(a: Color, b: Color): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05f) / (darker + 0.05f)
    }
}
