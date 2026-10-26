package com.bistu.allthebistu.ui.theme

import android.graphics.Typeface
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bistu.allthebistu.data.log.AppLog
import java.io.File

/**
 * 全局字体：设备**自带** MiSans 就用 MiSans，没有就一个字都不动，保持系统默认。
 *
 * 为什么是「设备自带」而不是把字体打进 APK：
 *  - MiSans 的授权允许随系统分发，但它不是开源自托管字体，塞进 APK 里再分发说不清楚；
 *  - 可变字体一档就十几 MB，为一个纯视觉偏好把包体积翻倍不划算；
 *  - 小米 / 红米 / POCO（HyperOS、MIUI 14 之后）系统里本来就有 MiSans，
 *    直接借系统那份即可，字形、字重、字距都和其他系统 App 完全一致。
 *
 * 所以这里只做「探测 + 借用」，探测不到就退回 [FontFamily.Default]——
 * 对非小米设备来说，本次改动在观感上等于零。
 *
 * ── 两条探测通道（按可靠性排序）──
 *  1. 读 `/system/fonts` 找 `MiSans*.ttf`。命中率最高，而且能拿到**可变字体**，
 *     按 `wght` 轴精确给出 400/500/600/700 四档，HyperOS 的粗细层次才完整。
 *  2. 读不到文件时，用 `Typeface.create("MiSans")` 试一次族名。
 *     "MiSans" 没注册成 family 时，系统会把请求落回默认 family，
 *     拿到的 native 实例与 `sans-serif` 完全相同 —— 这正是「不支持」的判据。
 *
 * 两条都失败 → 系统默认，绝不硬塞一个近似字体。
 */
object AppFont {

    private const val TAG = "AppFont"

    /** 系统字体目录。所有 Android 设备都有，且对 App 世界可读 */
    private const val SYSTEM_FONT_DIR = "/system/fonts"

    /** 按名探测用的族名。小米系统 fonts.xml 里注册的就是 "MiSans" */
    private const val FAMILY_NAME = "MiSans"

    /**
     * 需要覆盖的字重，和 [Typography] 里实际用到的四档严格对齐：
     * 正文 Normal / 按钮与标签 Medium / 卡片标题 SemiBold / 页面大标题 Bold。
     *
     * 少一档的后果：Compose 会拿最近的邻档去「假加粗」，
     * 标题会糊成一团，反而不如用系统默认。
     */
    private val NEEDED_WEIGHTS = listOf(
        FontWeight.Normal,
        FontWeight.Medium,
        FontWeight.SemiBold,
        FontWeight.Bold
    )

    /**
     * 全局字体族。**只求值一次**（lazy 默认就是 SYNCHRONIZED），
     * 因为每次访问都是一次磁盘探测 + 最长 4 次 Typeface 构建。
     */
    val family: FontFamily by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { resolve() }.getOrElse { error ->
            // Typeface 解析失败宁可退回默认字体，也不能让首帧崩掉：
            // 字体是装饰，不是功能
            AppLog.w(
                TAG,
                "MiSans 加载失败，退回系统字体：" +
                    "${error.javaClass.simpleName}: ${error.message}"
            )
            FontFamily.Default
        }
    }

    /** 实际生效的字体来源，写进诊断日志，方便确认「到底有没有用上」 */
    @Volatile
    var source: String = "系统默认"
        private set

    private fun resolve(): FontFamily {
        val dir = File(SYSTEM_FONT_DIR)
        val fileNameList = runCatching { dir.list()?.toList().orEmpty() }
            .getOrDefault(emptyList())
        val picked = pickMiSansFonts(fileNameList)

        if (picked.isEmpty()) {
            byFamilyName()?.let {
                source = "MiSans（系统字体族名）"
                AppLog.i(TAG, "已启用 MiSans：按字体族名解析成功")
                return it
            }
            AppLog.i(TAG, "未发现 MiSans（$SYSTEM_FONT_DIR 下无匹配字体），保持系统默认")
            return FontFamily.Default
        }

        val files = picked.map { File(dir, it) }.filter { it.canRead() }
        if (files.isEmpty()) {
            // 常见于列目录成功但文件权限收紧的定制系统
            AppLog.w(TAG, "找到 MiSans 文件名但读不到内容：$picked，保持系统默认")
            return FontFamily.Default
        }

        // 先可变字体（一个文件覆盖全部字重）——不行再退回静态字体
        files.firstOrNull { isVariableFontFileName(it.name) }?.let { variable ->
            familyFromVariableFont(variable)?.let {
                source = "MiSans（可变字体 ${variable.name}）"
                AppLog.i(TAG, "已启用 MiSans：$source")
                return it
            }
            AppLog.w(TAG, "可变字体的 wght 轴不可用，改试静态字体：${variable.name}")
        }

        val statics = files.filter { !isVariableFontFileName(it.name) }
        familyFromStaticFiles(statics)?.let {
            source = "MiSans（静态 ${statics.size} 档）"
            AppLog.i(TAG, "已启用 MiSans：$source")
            return it
        }

        byFamilyName()?.let {
            source = "MiSans（系统字体族名）"
            AppLog.i(TAG, "已启用 MiSans：按字体族名解析成功")
            return it
        }

        AppLog.w(TAG, "MiSans 字体文件解析失败：$picked，保持系统默认")
        return FontFamily.Default
    }

    /**
     * 真·预加载校验。
     *
     * 这一步不能省：Compose 的 `Font(file)` 在**构造时**并不读文件
     * （`AndroidFileFont.init` 传的是 null context，加载器直接返回 null），
     * 真正的 `Typeface.Builder().build()` 发生在首次绘制，
     * 而且那里失败会 `throwIllegalStateException("Cannot create Typeface")` —— 直接崩，不是降级。
     *
     * 所以宁可在这里同步试一次：过了才交给 Compose，不过就老老实实用系统字体。
     * 顺带把 Typeface 的 native 缓存热起来，首帧少卡一下。
     */
    private fun canLoad(file: File, variationSettings: String? = null): Boolean =
        runCatching {
            val builder = Typeface.Builder(file)
            if (variationSettings != null) {
                builder.setFontVariationSettings(variationSettings)
            }
            builder.build()
        }.isSuccess

    /**
     * 可变字体：一个文件给出全部字重。
     *
     * `Font(file, weight)` 的 `variationSettings` 默认就是
     * `FontVariation.Settings(weight, style)`，会翻译成 `wght` 轴设置，
     * 所以这里不需要手动碰 Typeface.Builder。
     */
    private fun familyFromVariableFont(file: File): FontFamily? {
        // 用 500 试探：wght 轴存在的话这一档必然成立；
        // 不存在时有的机型会抛 IllegalArgumentException，有的会静默忽略，两种情况都拦得住
        if (!canLoad(file, "'wght' 500")) return null

        val fonts = NEEDED_WEIGHTS.mapNotNull { weight ->
            runCatching { Font(file, weight) }.getOrNull()
        }
        return if (fonts.isEmpty()) null else FontFamily(fonts)
    }

    /**
     * 静态字体：一个文件一档字重，按文件名认字重。
     *
     * 同一字重出现多次时只留第一个（例如同时有 `MiSans-Bold.ttf` 和
     * `MiSansHeavy.ttf` 都解析成 700 的情况），避免 FontFamily 里出现重复档位。
     */
    private fun familyFromStaticFiles(files: List<File>): FontFamily? {
        val usedWeights = mutableSetOf<Int>()
        val fonts = files.mapNotNull { file ->
            val weight = weightOfFontFileName(file.name)
            if (!usedWeights.add(weight.weight)) return@mapNotNull null
            if (!canLoad(file)) {
                AppLog.w(TAG, "字体文件无法加载，跳过：${file.name}")
                return@mapNotNull null
            }
            runCatching { Font(file, weight) }.getOrNull()
        }
        return if (fonts.isEmpty()) null else FontFamily(fonts)
    }

    private fun byFamilyName(): FontFamily? {
        val probe = Typeface.create(FAMILY_NAME, Typeface.NORMAL)
        val fallback = Typeface.create("sans-serif", Typeface.NORMAL)
        // 族名不存在时系统直接把默认 family 还给你，两次拿到的 native 实例相等。
        // 注意这也覆盖了「系统默认字体本来就是 MiSans」的情况 —— 那种情况下
        // 返回 null、继续用 FontFamily.Default，渲染出来照样是 MiSans，不会变差。
        if (probe == fallback) return null
        return runCatching { FontFamily(probe) }.getOrNull()
    }
}

// ══════════════════════════════════════════════════════════════════════
// 纯函数：只吃文件名列表，不碰 Android API，便于 JVM 单测
// ══════════════════════════════════════════════════════════════════════

/** 能被 Typeface 直接吃下的扩展名 */
private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

/** 文件名是不是一个 MiSans 字体（大小写不敏感，必须是字体后缀） */
internal fun isMiSansFileName(fileName: String): Boolean =
    fileName.substringAfterLast('.', "").lowercase() in FONT_EXTENSIONS &&
        fileName.contains("misans", ignoreCase = true)

/**
 * 是不是可变字体（Variable Font）。
 *
 * 小米系统里叫 `MiSansVF.ttf`，少数版本是 `MiSans-VF.ttf` / `MiSansVariable.ttf`，
 * 三种写法都覆盖。
 */
internal fun isVariableFontFileName(fileName: String): Boolean =
    fileName.contains("vf", ignoreCase = true) ||
        fileName.contains("variable", ignoreCase = true)

/**
 * 从一组文件名里挑出要用的 MiSans，按「先试谁」排序。
 *
 * 规则：
 *  - 可变字体排第一（一个文件覆盖全部字重，字重过渡还连续）
 *  - 静态字体按字重从细到粗跟在后面 —— 它们是可变字体失败时的备胎，
 *    **不能因为「发现了 VF」就把它们丢掉**：VF 的 wght 轴在个别定制系统上设不进去，
 *    那种情况下唯一的退路就是静态文件
 *  - 什么都没有 → 空列表
 */
internal fun pickMiSansFonts(fileNames: List<String>): List<String> {
    val candidates = fileNames.filter { isMiSansFileName(it) }.distinct()
    if (candidates.isEmpty()) return emptyList()

    val (variable, statics) = candidates.partition { isVariableFontFileName(it) }
    return variable.sorted() + statics.sortedWith(
        compareBy({ weightOfFontFileName(it).weight }, { it.lowercase() })
    )
}

/**
 * 从文件名猜字重。
 *
 * 判断顺序不能随便改：`SemiBold` 里含 `Bold`、`ExtraLight` 里含 `Light`，
 * 先粗后细地写会让「半粗」被认成「粗」——Title 和 Body 的层次就全塌了。
 */
internal fun weightOfFontFileName(fileName: String): FontWeight {
    val name = fileName.lowercase()
    return when {
        name.contains("thin") -> FontWeight.Thin
        name.contains("extralight") || name.contains("ultralight") -> FontWeight.ExtraLight
        name.contains("semilight") || name.contains("demilight") -> FontWeight.Light
        name.contains("light") -> FontWeight.Light
        name.contains("semibold") || name.contains("demibold") -> FontWeight.SemiBold
        name.contains("extrabold") || name.contains("ultrabold") -> FontWeight.ExtraBold
        name.contains("heavy") || name.contains("black") -> FontWeight.Black
        name.contains("bold") -> FontWeight.Bold
        name.contains("medium") -> FontWeight.Medium
        else -> FontWeight.Normal
    }
}
