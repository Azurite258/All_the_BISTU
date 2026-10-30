package com.bistu.allthebistu.data

import com.bistu.allthebistu.data.log.AppLog
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// ═══════════════════════════════════════════════════════════════
// 一、原始响应模型（完全对应教务系统 JSON）
// ═══════════════════════════════════════════════════════════════

@Serializable
data class ScheduleResponse(
    val code: String = "",
    val datas: ScheduleDatas? = null,
    val msg: String? = null
)

@Serializable
data class ScheduleDatas(
    @SerialName("getMyScheduleDetail")
    val detail: ScheduleDetail? = null
)

@Serializable
data class ScheduleDetail(
    val subTitles: List<SubTitle> = emptyList(),
    /** 已排课：有明确的星期 / 节次 / 教室 */
    val arrangedList: List<RawCourse> = emptyList(),
    val mergeArrangedList: List<RawCourse> = emptyList(),
    /**
     * 未排课：本学期选了、但教务没给具体时间的课
     * （线上课、安全教育、职业规划这类，通常只有学分没有节次）。
     * 它们不进课表网格，但**要算进已选学分**。
     */
    val notArrangeList: List<RawCourse> = emptyList(),
    /** 实践环节（实习、课程设计等），结构同上 */
    val practiceList: List<RawCourse> = emptyList(),
    val termName: String? = null
)

@Serializable
data class SubTitle(
    val label: String = "",
    val value: String = ""
)

/**
 * 原始课程条目（教务系统返回的单条 arrangedList）。
 *
 * 注意：同一个时间段可能有多条记录被合并成一条，用 ";" 分隔。
 * 例如：
 *   weeksAndTeachers = "13周[讲授]/张老师[主讲]; 3-12周[讲授]/张老师[主讲]"
 *   placeName = "A101; A101"
 */
@Serializable
data class RawCourse(
    val courseName: String = "",
    val courseCode: String = "",
    val credit: String = "",
    val weeksAndTeachers: String = "",
    val placeName: String? = null,
    val campusName: String = "",
    val teachClassName: String = "",
    val courseSerialNo: String = "",
    val teachClassId: String = "",
    val teachingTarget: String? = null,
    val color: String? = null,
    /** 周次位图：第 i 位为 '1' 表示第 i+1 周有课 */
    val week: String = "",
    val dayOfWeek: Int = 0,
    val beginSection: Int = 0,
    val endSection: Int = 0,
    val beginTime: String = "",
    val endTime: String = "",
    val titleDetail: List<String> = emptyList(),
    val cellDetail: List<CellText> = emptyList()
)

@Serializable
data class CellText(
    val text: String = "",
    val color: String? = null
)

// ═══════════════════════════════════════════════════════════════
// 二、应用层模型（给 UI 用的干净数据）
// ═══════════════════════════════════════════════════════════════

/**
 * 一条课程安排（已经展开合并记录、解析好周次）。
 *
 * 举例：基础英语 3-5 周在 WLC-305，6-13 周在 WLC-204
 * 会被展开成两条 Course：
 *   Course(name="基础英语", weeks=[3,4,5], room="WLC-305", ...)
 *   Course(name="基础英语", weeks=[6..13], room="WLC-204", ...)
 */
data class Course(
    val name: String,
    val code: String,
    val credit: String,
    val teacher: String,
    val room: String,
    val campus: String,
    /** 有课的周次集合，如 setOf(3,4,5,6) */
    val weeks: Set<Int>,
    /** 星期几：1=周一 ... 7=周日 */
    val dayOfWeek: Int,
    /** 开始节次 1-14 */
    val beginSection: Int,
    /** 结束节次 1-14 */
    val endSection: Int,
    val beginTime: String,
    val endTime: String,
    /** 学校配好的颜色，如 "#FFF0CC" */
    val colorHex: String,
    val teachClassId: String = "",
    /**
     * 本地改动（备注 / 隐藏 / 编辑 / 复制）用来认课的**稳定键**。
     *
     * 它由 [com.bistu.allthebistu.data.patch.CoursePatchLogic] 在应用改动时
     * 按「教务系统原始那条课」算出来写回去，之后再改星期/节次也不会变——
     * 所以用户把课从周三挪到周五，备注和删除标记还认得出是同一门课。
     * 没打过补丁的课程是空串。
     */
    val patchKey: String = "",
    /** 这条是「复制」出来的克隆课程，不属于教务系统原始数据 */
    val isClone: Boolean = false
) {
    /** 占几节 */
    val sectionCount: Int get() = endSection - beginSection + 1

    /**
     * 是否有具体上课时间。
     *
     * 未排课的课程（notArrangeList）dayOfWeek / beginSection 都是 0
     * 或 null，只能参与学分统计，不能画进课表网格。
     */
    val isArranged: Boolean get() = dayOfWeek in 1..7 && beginSection >= 1

    /** 指定周次是否有课 */
    fun isInWeek(weekNumber: Int): Boolean = weekNumber in weeks
}

/**
 * 教学周（来自教务系统 getTermWeeks.do）。
 *
 * 例：第 7 周 = 2026-10-19 ~ 2026-10-25
 */
data class TermWeek(
    /** 周次，从 1 开始 */
    val weekNumber: Int,
    /** 该周周一，格式 "yyyy-MM-dd" */
    val startDate: String,
    /** 该周周日，格式 "yyyy-MM-dd" */
    val endDate: String
)

/** 完整课表 */
data class ScheduleData(
    val termCode: String,
    val termName: String,
    val studentName: String,
    val studentId: String,
    val major: String,
    val className: String,
    val campus: String,
    val courses: List<Course>,
    /**
     * 教学周列表（按周次升序）。
     *
     * 为空表示教务系统没给（离线/示例数据/接口失败），
     * 此时退化为按「课程覆盖的周次范围」显示。
     */
    val termWeeks: List<TermWeek> = emptyList()
) {
    /** 所有课程覆盖的周次范围 */
    val weekRange: IntRange
        get() {
            val all = courses.flatMap { it.weeks }
            return if (all.isEmpty()) 1..20 else all.min()..all.max()
        }

    /**
     * 课表应显示的周次范围。
     *
     * 优先用教务系统的教学周（可能比课程覆盖范围更宽，
     * 例如第 14-18 周没课但仍属于学期）；
     * 没有教学周数据时退化为 [weekRange]。
     */
    val displayWeekRange: IntRange
        get() = if (termWeeks.isNotEmpty()) {
            termWeeks.first().weekNumber..termWeeks.last().weekNumber
        } else {
            weekRange
        }

    /** 指定周次的教学周信息 */
    fun termWeekOf(weekNumber: Int): TermWeek? =
        termWeeks.firstOrNull { it.weekNumber == weekNumber }

    /** 指定周次的课程 */
    fun coursesInWeek(weekNumber: Int): List<Course> =
        courses.filter { it.isInWeek(weekNumber) }
}

// ═══════════════════════════════════════════════════════════════
// 三、解析器：把原始响应转成应用模型
// ═══════════════════════════════════════════════════════════════

object ScheduleParser {

    private const val TAG = "ScheduleParser"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 解析完整响应 */
    fun parse(rawJson: String, termCode: String): ScheduleData {
        val resp = json.decodeFromString<ScheduleResponse>(rawJson)
        require(resp.code == "0") { "接口返回失败: code=${resp.code}, msg=${resp.msg}" }

        val detail = resp.datas?.detail
            ?: throw IllegalStateException("响应里没有 getMyScheduleDetail")

        // 从 subTitles 提取学生信息
        val info = detail.subTitles.associate { it.label to it.value }

        // 已排课放前面（课表按它画），未排课与实践环节跟在后面，只用于学分统计
        val arranged = detail.arrangedList.flatMap { expandCourse(it) }
        val notArranged = detail.notArrangeList.flatMap { expandCourse(it) }
        val practice = detail.practiceList.flatMap { expandCourse(it) }
        auditParse(detail.arrangedList, arranged)
        val courses = mergeDuplicates(arranged) + mergeDuplicates(notArranged) + mergeDuplicates(practice)

        return ScheduleData(
            termCode = termCode,
            termName = detail.termName ?: termCode,
            studentName = info["学生"]?.substringBefore("[") ?: "",
            studentId = info["学生"]?.substringAfter("[")?.substringBefore("]") ?: "",
            major = info["专业"] ?: "",
            className = info["班级"] ?: "",
            campus = info["校区"] ?: "",
            courses = courses
        )
    }

    /**
     * 解析审计：把「原始几条 → 展开几条 → 谁被丢了、为什么」打进日志。
     *
     * 起因是有人反馈 App 课表比教务网页少一门课（程序设计基础，3-5 节）。
     * 静态看代码有四处会让一门课**静默消失**：
     *   ① week 位图不是预期的 "001111" 格式，[parseWeekBitmap] 解出空集
     *   ② weeksAndTeachers 里某段周次文本解析不出数字，被 filter 掉
     *   ③ 星期 / 节次为 0，[Course.isArranged] 判定不过，画不进网格
     *   ④ 主键碰撞被 REPLACE 覆盖
     * 光靠读代码判断不了是哪一处，所以让数据自己说话。
     * 这些日志会跟着「诊断日志」一起导出，不用连电脑。
     */
    private fun auditParse(rawList: List<RawCourse>, expanded: List<Course>) {
        AppLog.d(TAG, "解析审计: 原始 ${rawList.size} 条 → 展开 ${expanded.size} 条")
        rawList.forEach { raw ->
            AppLog.d(
                TAG,
                "原始课: [${raw.courseName}] 星期=${raw.dayOfWeek} 节=${raw.beginSection}-${raw.endSection} " +
                        "位图=${raw.week} 教室=${raw.placeName} 周次/教师=${raw.weeksAndTeachers}"
            )
        }
        expanded.filter { it.weeks.isEmpty() }.forEach {
            AppLog.w(
                TAG,
                "周次为空→界面上看不到: [${it.name}] 星期=${it.dayOfWeek} 节=${it.beginSection}-${it.endSection}"
            )
        }
        expanded.filter { !it.isArranged }.forEach {
            AppLog.w(
                TAG,
                "星期/节次缺失→画不进网格: [${it.name}] 星期=${it.dayOfWeek} 节=${it.beginSection}-${it.endSection}"
            )
        }
    }

    /**
     * 合并「同一时间、同一地点」的多条课程记录。
     *
     * 教务系统会把同一门课拆成多段（例如「3-12周」和「13周」），
     * 展开后它们的课程码、星期、节次、教室完全一致，只有周次不同。
     * 这种情况下必须合并周次，否则：
     *   ① 数据库主键冲突会导致一条被覆盖丢失
     *   ② 界面上会出现两个重叠的课程块
     *
     * 注意：如果教室不同（如基础英语 3-5周在 WLC-305、6-13周在 WLC-204），
     * 则不会被合并，仍显示为两条独立记录。
     */
    private fun mergeDuplicates(courses: List<Course>): List<Course> {
        val map = LinkedHashMap<String, Course>()
        courses.forEach { c ->
            val key = "${c.code}|${c.dayOfWeek}|${c.beginSection}|${c.endSection}|${c.room}"
            val existing = map[key]
            if (existing == null) {
                map[key] = c
            } else {
                map[key] = existing.copy(weeks = existing.weeks + c.weeks)
            }
        }
        return map.values.toList()
    }

    /**
     * 展开一条原始记录。
     * 如果 weeksAndTeachers / placeName 里有 ";"，说明是多条合并的，
     * 需要拆成多条 Course。
     */
    private fun expandCourse(raw: RawCourse): List<Course> {
        val weekParts = raw.weeksAndTeachers.split(";").map { it.trim() }
        val roomParts = (raw.placeName ?: "").split(";").map { it.trim() }

        // 没有分号：单条记录
        if (weekParts.size <= 1) {
            val weeks = parseWeekBitmap(raw.week)
            // 位图有内容却解不出任何周次 → 说明服务端给的不是 "001111..." 这种格式，
            // 这门课会因为 weeks 为空而在界面上彻底消失（isInWeek 永远 false）
            if (weeks.isEmpty() && raw.week.isNotBlank()) {
                AppLog.w(
                    TAG,
                    "位图解不出周次（格式和预期不符）: [${raw.courseName}] week=${raw.week}"
                )
            }
            return listOf(
                buildCourse(
                    raw = raw,
                    weeks = weeks,
                    teacher = extractTeacher(raw.weeksAndTeachers),
                    room = raw.placeName ?: ""
                )
            )
        }

        // 有分号：逐段展开
        val built = weekParts.mapIndexed { index, weekText ->
            val room = roomParts.getOrElse(index) { roomParts.firstOrNull() ?: "" }
            // 从 "3-5周[讲授]/李老师[主讲]" 这种文本里解析周次
            val weeks = parseWeekText(weekText)
            if (weeks.isEmpty()) {
                AppLog.w(
                    TAG,
                    "周次文本解析为空，该段被丢弃: [${raw.courseName}] 段=${weekText}"
                )
            }
            buildCourse(
                raw = raw,
                weeks = weeks,
                teacher = extractTeacher(weekText),
                room = room
            )
        }
        if (built.isNotEmpty() && built.all { it.weeks.isEmpty() }) {
            AppLog.w(
                TAG,
                "整条都被丢弃（所有段的周次都解析失败）: [${raw.courseName}] 原文=${raw.weeksAndTeachers}"
            )
        }
        return built.filter { it.weeks.isNotEmpty() }
    }

    private fun buildCourse(
        raw: RawCourse,
        weeks: Set<Int>,
        teacher: String,
        room: String
    ) = Course(
        name = raw.courseName,
        code = raw.courseCode,
        credit = raw.credit,
        teacher = teacher,
        room = room,
        campus = raw.campusName,
        weeks = weeks,
        dayOfWeek = raw.dayOfWeek,
        beginSection = raw.beginSection,
        endSection = raw.endSection,
        beginTime = raw.beginTime,
        endTime = raw.endTime,
        colorHex = raw.color ?: "#CCCCCC",
        teachClassId = raw.teachClassId
    )

    // ───────────────────────────────────────────────────────────
    // 位图解析：week = "001111111111" → setOf(3..12)
    // ───────────────────────────────────────────────────────────
    fun parseWeekBitmap(bitmap: String): Set<Int> {
        if (bitmap.isBlank()) return emptySet()

        // 正常位图只由 0/1 组成。出现别的字符，说明服务端换了表达方式
        // （实测过 "3-5"、"1,3,5"、"3-16周" 这类文本形式）。
        // 直接按位图解析的后果很隐蔽：字符串里只要没有 '1' 就得到空集，
        // 于是这门课 weeks 为空、任何一周都不显示 —— 看起来就是「课表少了一门课」。
        if (bitmap.any { it != '0' && it != '1' }) {
            val asText = parseWeekText(bitmap)
            if (asText.isEmpty()) {
                AppLog.w(TAG, "week 既不是位图也解析不出周次: \"$bitmap\"")
            } else {
                AppLog.w(TAG, "week 是文本格式，已按文本解析: \"$bitmap\" → $asText")
            }
            return asText
        }

        val result = mutableSetOf<Int>()
        bitmap.forEachIndexed { index, ch ->
            if (ch == '1') result.add(index + 1)
        }
        return result
    }

    // ───────────────────────────────────────────────────────────
    // 文本周次解析："3-5周" → setOf(3,4,5)
    //             "1-16周(单)" → setOf(1,3,5..15)
    //             "1,3,5周" → setOf(1,3,5)
    // ───────────────────────────────────────────────────────────
    fun parseWeekText(text: String): Set<Int> {
        if (text.isBlank()) return emptySet()

        // 关键：先按 "/" 分割取第一部分（周次部分），教师名在后面
        val weekPart = text.split("/").first()

        val isOdd = weekPart.contains("单")
        val isEven = weekPart.contains("双")

        // 只保留数字、连字符、逗号
        var cleaned = weekPart
            .replace(Regex("""\[[^]]*]"""), "")          // 去掉 [讲授] [实验]
            .replace(Regex("""[（(][^）)]*[）)]"""), "")   // 去掉 (单) (双)
            .replace("周", "")
            .replace("第", "")
            .trim()
        // 过滤掉所有非数字、连字符、逗号字符（比如残留的教师名）
        cleaned = cleaned.replace(Regex("""[^\d\-,]"""), "")

        val weeks = mutableSetOf<Int>()
        cleaned.split(",").forEach { seg ->
            val s = seg.trim()
            if (s.isEmpty()) return@forEach
            if (s.contains("-")) {
                val parts = s.split("-")
                if (parts.size == 2) {
                    val start = parts[0].trim().toIntOrNull()
                    val end = parts[1].trim().toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        weeks.addAll(start..end)
                    }
                }
            } else {
                s.toIntOrNull()?.let { weeks.add(it) }
            }
        }

        return when {
            isOdd -> weeks.filter { it % 2 == 1 }.toSet()
            isEven -> weeks.filter { it % 2 == 0 }.toSet()
            else -> weeks
        }
    }

    // ───────────────────────────────────────────────────────────
    // 教师名提取
    // "3-12周[讲授]/张老师[主讲]" → "张老师"
    // "1周[实验]/张翠平[主讲]/李四[助教]" → "张翠平, 李四"
    // ───────────────────────────────────────────────────────────
    fun extractTeacher(raw: String): String {
        if (raw.isBlank()) return ""

        val names = raw.split("/").mapNotNull { part ->
            // 去掉 "3-12周[讲授]" 这类周次段
            if (part.contains("周")) {
                // 取 "周" 后面的内容
                val afterWeek = part.substringAfterLast("周").trim()
                if (afterWeek.isNotEmpty()) {
                    // 去掉 [主讲] [实验] 等标记
                    afterWeek.replace(Regex("""\[[^]]*]"""), "").trim()
                        .takeIf { it.isNotEmpty() }
                } else null
            } else {
                // 不含"周"的段，可能就是教师名
                part.replace(Regex("""\[[^]]*]"""), "").trim()
                    .takeIf { it.isNotEmpty() && !it.matches(Regex("""^[\d\-,\s]+$""")) }
            }
        }

        return names.distinct().joinToString(", ")
    }
}
