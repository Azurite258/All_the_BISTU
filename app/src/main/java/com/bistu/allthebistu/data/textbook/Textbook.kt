package com.bistu.allthebistu.data.textbook

import kotlinx.serialization.Serializable

/**
 * 一本电子课本。
 *
 * **跨端纪律**：这个文件和 [TextbookStore] 只用 java.io / kotlinx.serialization，
 * 不碰任何 Android API。将来若把项目迁到 Compose Multiplatform，
 * 这一层可以原样搬过去（桌面端换成文件对话框选书即可）。
 *
 * 书本身不进数据库，只存一条记录 + 一个文件：
 * 教材动辄一两百 MB，塞进 JSON/数据库不现实，文件放 App 私有目录 [fileName]。
 */
@Serializable
data class Textbook(
    /** UUID，空串表示还没存过 */
    val id: String = "",
    /** 显示用书名，默认取文件名去掉扩展名，用户可以改 */
    val title: String = "",
    /** 私有目录 `files/textbooks/` 下的文件名（不含路径，方便换设备/迁移） */
    val fileName: String = "",
    /** 自动匹配到的课程名；空串 = 未分类 */
    val courseName: String = "",
    /** 总页数，导入时用 PdfRenderer 读出来 */
    val pageCount: Int = 0,
    val sizeBytes: Long = 0L,
    /** 导入时刻 epoch 毫秒 */
    val addedAt: Long = 0L,
    /** 上次读到的页（0-based），打开时直接跳过去 */
    val lastPage: Int = 0,
) {

    val isMatched: Boolean get() = courseName.isNotBlank()

    /** 阅读进度 0~1，没读过是 0 */
    val progress: Float
        get() = if (pageCount <= 1) 0f else (lastPage + 1).toFloat() / pageCount

    /** "第 12 / 356 页" —— lastPage 是 0-based，显示时 +1 */
    val pageLabel: String
        get() = if (pageCount <= 0) "页数未知" else "第 ${(lastPage + 1).coerceAtMost(pageCount)} / $pageCount 页"

    /** 大小显示成 "42.3 MB" */
    val sizeLabel: String get() = formatSize(sizeBytes)

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes <= 0 -> "未知大小"
            bytes < 1024 * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
            else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
        }
    }
}

/**
 * 从文件名猜课程（纯函数，可单测）。
 *
 * 大学教材的文件名五花八门：
 *   `高等数学A（第七版）上册.pdf`、`《线性代数》同济六版扫描版.pdf`、`大学物理 电磁学.pdf`
 * 而课表里的课程名是教务系统给的：`高等数学A`、`线性代数`、`大学物理`。
 *
 * 做法分三步，命中即止：
 *  1. 两边都归一化（去括号、版本号、"上册/扫描版"这类噪声、空格标点，统一小写）
 *  2. 课程名整串出现在文件名里 → 命中
 *  3. 课程名去掉末尾的字母数字（`高等数学A1` → `高等数学`）再找一次，要求长度 ≥2
 * 多个命中时取**最长**的那个课程名（"大学物理" 比 "物理" 更准）。
 */
object TextbookMatcher {

    /** 归一化：只留汉字、字母、数字 */
    fun normalize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        }
        return sb.toString()
    }

    /** 去掉文件名里的常见噪声词，进一步降低误判 */
    private val noise = listOf(
        "电子版", "高清版", "高清", "扫描版", "扫描", "影印版", "教材", "课本",
        "讲义", "习题", "答案", "下册", "上册", "全集", "修订版"
    )

    private fun clean(fileName: String): String {
        // 先去扩展名，再按噪声词切掉，最后归一化
        val noExt = fileName.substringBeforeLast('.').let {
            // 文件名里偶尔带路径分隔符（部分文件管理器会这样），只取最后一段
            it.substringAfterLast('/').substringAfterLast('\\')
        }
        var s = noExt
        noise.forEach { s = s.replace(it, " ") }
        return normalize(s)
    }

    /**
     * 去掉课程名末尾的「修饰尾巴」：字母、数字、括号都算。
     *
     * 教务系统的课程名常带这些：`高等数学(2)`、`大学体育(1)`、`高等数学A1`。
     * 教材封面上一般只写主干名（`高等数学`），所以退一步用主干去匹配。
     */
    private fun stripTail(name: String): String =
        name.trimEnd { ch ->
            // 注意 isLetter() 对汉字也返回 true，这里只剥 ASCII 字母，
            // 否则「高等数学」会被整个剥空
            ch.isDigit() || (ch.isLetter() && ch.code < 128) || ch == '(' || ch == ')' ||
                ch == '（' || ch == '）' || ch.isWhitespace()
        }

    /**
     * @return 匹配到的课程名；匹配不上返回空串（归入"未分类"）
     */
    fun bestCourse(fileName: String, courses: List<String>): String {
        val fn = clean(fileName)
        if (fn.isEmpty()) return ""

        var best = ""
        var bestLen = 0

        courses.forEach { course ->
            val cn = normalize(course)
            if (cn.length < 2) return@forEach

            // 整串命中
            if (fn.contains(cn) && cn.length > bestLen) {
                best = course
                bestLen = cn.length
            }
        }
        if (best.isNotEmpty()) return best

        // 退一步：去掉课程名末尾的字母数字再找
        courses.forEach { course ->
            val core = normalize(stripTail(course))
            if (core.length >= 2 && fn.contains(core) && core.length > bestLen) {
                best = course
                bestLen = core.length
            }
        }
        return best
    }
}
