package com.bistu.allthebistu.data.ai

import com.bistu.allthebistu.data.Course
import com.bistu.allthebistu.data.event.EventTag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Calendar

/**
 * AI 生成的一条计划草案。
 *
 * 和 [com.bistu.allthebistu.data.event.UserEvent] 的区别：草案**还没有 id**，
 * 摆在列表里等用户勾选，勾中才转成 UserEvent 落库。
 * 直接落库是绝对不行的 —— 模型偶尔会把时间排在半夜，或者同一件事排三遍。
 */
data class PlanDraft(
    val startAt: Long,
    val endAt: Long,
    val title: String,
    val tag: Int = EventTag.OTHER,
    val note: String = ""
) {
    val startClock: String get() = clockOf(startAt)
    val endClock: String get() = clockOf(endAt)
    val durationMinutes: Int get() = ((endAt - startAt) / 60_000L).toInt()

    /** "周三 19:00-20:30" */
    fun dayTimeLabel(): String = "${StudyPlanner.weekdayName(dayOfWeek(startAt))} $startClock-$endClock"

    /** "11-05 周三" —— 跨天计划里用日期更直观 */
    fun dateLabel(): String {
        val c = Calendar.getInstance().apply { timeInMillis = startAt }
        return "%d-%02d %s".format(
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            StudyPlanner.weekdayName(dayOfWeek(startAt))
        )
    }

    companion object {
        fun clockOf(millis: Long): String {
            val c = Calendar.getInstance().apply { timeInMillis = millis }
            return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }

        /** 1 = 周一 … 7 = 周日 */
        fun dayOfWeek(millis: Long): Int {
            val d = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
            return if (d == Calendar.SUNDAY) 7 else d - 1
        }
    }
}

/** 一段空闲时间。dayOffset 是相对今天的天数，0 = 今天 */
data class FreeSlot(
    val dayOffset: Int,
    val startMinutes: Int,
    val endMinutes: Int
) {
    val length: Int get() = endMinutes - startMinutes
    val startClock: String get() = "%02d:%02d".format(startMinutes / 60, startMinutes % 60)
    val endClock: String get() = "%02d:%02d".format(endMinutes / 60, endMinutes % 60)
}

/**
 * 学习计划的提示词构造 + 回复解析。
 *
 * 两种模式，对应二级页里那两组时长：
 *
 * - **短时**（3 小时 / 5 小时，今天内做完）：核心是「怎么切段效率高」——
 *   先难后易、每段之间留休息、最后留时间回顾。
 * - **多天**（1 / 3 / 7 天）：核心是「什么时候复习什么」——按遗忘曲线，
 *   当天学、次日第一次、第 3~4 天第二次、第 6~7 天第三次，并且**避开上课时间**。
 *
 * 这一层是纯函数（除了取当前时间），单测能直接跑，不需要网络。
 * 模型返回的 JSON 永远不能全信，所以边界校验才是重点。
 */
object StudyPlanner {

    /** 一天里可安排的时间窗：早 8 点到晚 10 点半，再晚就不该排了 */
    private const val DAY_START = 8 * 60
    private const val DAY_END = 22 * 60 + 30

    /** 小于一小时的空档不排 —— 排了也做不完一件事 */
    private const val MIN_SLOT = 60

    /** 每天最多给几段，避免提示词过长、也避免模型把一天排满 */
    private const val MAX_SLOT_PER_DAY = 3

    private val json = Json { ignoreUnknownKeys = true }

    // ══════════════════════════════════════════════════════════
    // 一、算空闲时段（多天模式要避开上课）
    // ══════════════════════════════════════════════════════════

    /**
     * 算出未来 [horizonDays] 天里可以安排学习的空档。
     *
     * 做法是「一天内把上课区间合并，再取补集」。不区分工作日周末 ——
     * 学生周末也要学习，把周末排除反而会让模型无处可排。
     *
     * @param todayDow 今天是周几（1..7），由调用方传，方便单测固定
     * @param nowMinutes 现在几点（分钟），今天只排这之后的时段
     */
    fun freeSlots(
        courses: List<Course>,
        week: Int,
        todayDow: Int,
        nowMinutes: Int,
        horizonDays: Int = 7
    ): List<FreeSlot> {
        val result = mutableListOf<FreeSlot>()
        for (offset in 0 until horizonDays) {
            val dow = ((todayDow - 1 + offset) % 7) + 1
            val busy = courses
                .filter { it.isArranged && it.dayOfWeek == dow && it.isInWeek(week) }
                .mapNotNull { course ->
                    val start = parseClock(course.beginTime)
                    val end = parseClock(course.endTime)
                    if (start == null || end == null || end <= start) null else start to end
                }
                .sortedBy { it.first }

            // 今天要从「现在 + 30 分钟」开始算，别把过去的时间排进去
            val dayStart = if (offset == 0) {
                maxOf(DAY_START, ((nowMinutes + 30) / 30) * 30)
            } else {
                DAY_START
            }
            if (dayStart >= DAY_END) continue

            var cursor = dayStart
            val gaps = mutableListOf<Pair<Int, Int>>()
            busy.forEach { (start, end) ->
                if (start > cursor) gaps += cursor to minOf(start, DAY_END)
                cursor = maxOf(cursor, end)
            }
            if (cursor < DAY_END) gaps += cursor to DAY_END

            gaps.filter { it.second - it.first >= MIN_SLOT }
                .take(MAX_SLOT_PER_DAY)
                .forEach { (s, e) -> result += FreeSlot(offset, s, e) }
        }
        return result
    }

    // ══════════════════════════════════════════════════════════
    // 二、短时模式（今天内 3 / 5 小时）
    // ══════════════════════════════════════════════════════════

    fun shortSystemPrompt(): String = """
        你是一个大学学习规划助手。学生告诉你「要学什么」和「今天还有几个小时」，你要把内容拆成可执行的时段。

        只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要在前后加字：
        {"items":[{"day":0,"start":"14:40","end":"15:25","title":"高数 · 极限与导数","tag":"作业","note":"先过一遍例题，再做 5 道练习"}]}

        约束：
        1. day 固定为 0（只排今天）。
        2. start / end 用 24 小时制 HH:mm，必须落在给定的时间窗内，从给定开始时刻起、首尾相接排到结束时刻。
        3. 单段 30~60 分钟；相邻两段之间留 5~10 分钟休息（休息不要写进计划）。
        4. 顺序先难后易；**最后一段用来回顾今天学过的全部内容**。
        5. title 不超过 12 个汉字，格式「科目 · 做什么」。
        6. tag 只能是 作业 / 考试 / 课程 / 生活 / 其他 之一。
        7. note 写具体做法（做几道题、背哪几页），20 字以内。
    """.trimIndent()

    fun shortUserPrompt(
        content: String,
        startMillis: Long,
        totalMinutes: Int
    ): String {
        val startMin = PlanDraft.clockOf(startMillis)
        val endMin = PlanDraft.clockOf(startMillis + totalMinutes * 60_000L)
        val hours = if (totalMinutes % 60 == 0) "${totalMinutes / 60}" else "%.1f".format(totalMinutes / 60f)
        return """
            要学的内容：
            ${content.trim()}

            可用时间：今天 $startMin–$endMin，合计 $totalMinutes 分钟（约 $hours 小时）。
            请按系统提示的 JSON 格式，把它拆成连续的学习时段，中间留出短休息。
        """.trimIndent()
    }

    // ══════════════════════════════════════════════════════════
    // 三、多天模式（1 / 3 / 7 天，按遗忘曲线排复习）
    // ══════════════════════════════════════════════════════════

    fun multiDaySystemPrompt(): String = """
        你是一个大学学习规划助手。学生告诉你「要学什么」和「跨几天」，你要按遗忘曲线排出「学 + 复习」的时间表。

        只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要在前后加字：
        {"items":[{"day":0,"start":"19:00","end":"20:30","title":"高数 · 第一遍过教材","tag":"作业","note":"极限定义与两个重要极限"}]}

        约束：
        1. day 是相对今天的天数，0=今天；最大值见用户消息里给出的跨度。
        2. start / end 用 24 小时制 HH:mm。**必须避开用户课表里的上课时间**，也不要排在 22:30 之后、08:00 之前。
        3. 同一块内容按这个节奏安排：当天第一遍学 → 第 2 天复习一次 → 第 3~4 天再复习 → 第 6~7 天最后回顾。
           跨度不够就把后面的节点省掉，不要硬塞成每天重复。
        4. 每天 1~3 段，单段 45~90 分钟。
        5. title 不超过 12 个汉字，写清是「学」还是「复习」、哪块内容。
        6. tag 只能是 作业 / 考试 / 课程 / 生活 / 其他 之一；复习类用「考试」。
        7. note 写这一遍的重点，20 字以内。
    """.trimIndent()

    fun multiDayUserPrompt(
        content: String,
        days: Int,
        courses: List<Course>,
        week: Int,
        today: Long,
        todayDow: Int,
        slots: List<FreeSlot>
    ): String {
        val timetable = courses
            .filter { it.isArranged && it.isInWeek(week) }
            .sortedWith(compareBy({ it.dayOfWeek }, { it.beginSection }))
            .joinToString("\n") {
                "${weekdayName(it.dayOfWeek)} ${it.beginTime}-${it.endTime} ${it.name}" +
                        if (it.room.isBlank()) "" else "（${it.room}）"
            }
            .ifEmpty { "（本周没有排课）" }

        val slotText = slots.joinToString("\n") {
            val dayName = if (it.dayOffset == 0) "今天" else "第 ${it.dayOffset + 1} 天"
            "$dayName（day=${it.dayOffset}）${it.startClock}-${it.endClock}，共 ${it.length} 分钟"
        }.ifEmpty { "（没有可用的整块空闲时间，请安排在 20:00 之后）" }

        return """
            今天是第 $week 周 ${weekdayName(todayDow)}（${dateText(today)}），计划跨度 $days 天（day 取值 0..${days - 1}）。

            要学的内容：
            ${content.trim()}

            本周课表（这些时间不能占用）：
            $timetable

            可以安排的空闲时段：
            $slotText

            请按系统提示的 JSON 格式，排出「学习 + 复习」的时间表。
        """.trimIndent()
    }

    // ══════════════════════════════════════════════════════════
    // 四、解析回复
    // ══════════════════════════════════════════════════════════

    /**
     * 解析模型返回的 JSON。
     *
     * 三处兜底，都是实测会遇到的情况：
     * - 模型爱加 ```json 代码块，甚至前后写一句「好的，这是计划：」；
     * - 偶尔给 `start` 写 "19:00:00"；
     * - `day` 给成字符串 "0" 或者超范围。
     *
     * 任何一条解析不了就**丢掉那一条**，不整体失败 —— 7 条里坏 1 条，
     * 用户还有 6 条可用，比弹个「解析失败」重来一次好。
     */
    fun parse(text: String, todayMillis: Long = System.currentTimeMillis()): List<PlanDraft> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyList()

        val root = runCatching {
            json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrNull() ?: return emptyList()

        val items = runCatching { root["items"]?.jsonArray }.getOrNull() ?: return emptyList()

        return items.mapNotNull { element ->
            runCatching {
                val obj = element.jsonObject
                val day = obj["day"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
                    ?: return@runCatching null
                if (day !in 0..6) return@runCatching null

                val startMin = parseClock(obj["start"]?.jsonPrimitive?.content) ?: return@runCatching null
                val endMin = parseClock(obj["end"]?.jsonPrimitive?.content) ?: return@runCatching null
                if (endMin <= startMin) return@runCatching null
                if (endMin - startMin < 15) return@runCatching null

                val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isBlank()) return@runCatching null

                val base = startOfDay(todayMillis, day)
                PlanDraft(
                    startAt = base + startMin * 60_000L,
                    endAt = base + endMin * 60_000L,
                    title = title.take(24),
                    tag = tagOf(obj["tag"]?.jsonPrimitive?.content),
                    note = obj["note"]?.jsonPrimitive?.content?.trim().orEmpty().take(60)
                )
            }.getOrNull()
        }
            .distinctBy { it.startAt to it.title }
            .sortedBy { it.startAt }
    }

    /**
     * 示例计划（没配 Key 时给用户看效果）。
     *
     * 按真实时长切段：50 分钟一段、中间 10 分钟休息，最后一段做回顾。
     * 内容按「、，,；;换行」拆成若干主题轮流分配 —— 所以用户看到的就是
     * 他自己输入的内容被排进了真实的时间槽，不是随机编的假条目。
     */
    fun sampleShortPlan(
        content: String,
        startMillis: Long,
        totalMinutes: Int
    ): List<PlanDraft> {
        val topics = content
            .split('、', '，', ',', '；', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("今天的内容") }

        val drafts = mutableListOf<PlanDraft>()
        var cursor = startMillis + 5 * 60_000L      // 先留 5 分钟准备
        val hardEnd = startMillis + totalMinutes * 60_000L
        var index = 0

        while (cursor + 30 * 60_000L <= hardEnd - 30 * 60_000L && drafts.size < 8) {
            val block = minOf(50 * 60_000L, (hardEnd - cursor).coerceAtLeast(0L))
            if (block < 25 * 60_000L) break
            val topic = topics[index % topics.size]
            drafts += PlanDraft(
                startAt = cursor,
                endAt = cursor + block,
                title = "学习 · $topic".take(24),
                tag = EventTag.HOMEWORK
            )
            cursor += block + 10 * 60_000L
            index++
        }

        // 末尾补一段「回顾」——和提示词里对模型的要求保持一致
        if (hardEnd - cursor >= 15 * 60_000L) {
            drafts += PlanDraft(
                startAt = cursor,
                endAt = hardEnd,
                title = "回顾 · 今天学过的内容",
                tag = EventTag.EXAM
            )
        }
        return drafts
    }

    /**
     * 多天示例：第 1 天学、第 2 天复习、第 4 天再复习、第 7 天回顾。
     * 只在用户选 7 天时才会用满四个节点，短跨度自动裁掉后面的。
     */
    fun sampleMultiDayPlan(
        content: String,
        days: Int,
        startMillis: Long
    ): List<PlanDraft> {
        val topics = content
            .split('、', '，', ',', '；', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf("今天的内容") }

        // 每个知识点自己走一遍「学 → 复习 → 再复习 → 回顾」，
        // 而不是每天换一个知识点 —— 复习当然复习的是前一天学的那块
        // 标题里不写「第几天」——列表已经按天分组了，再写一遍会和分组标题打架，
        // 而且内容一长标题就被挤成省略号。第几遍放到 note 里。
        val nodes = listOf(
            0 to "学习 · %s",
            1 to "复习 · %s",
            3 to "再复习 · %s",
            6 to "回顾 · %s"
        )
        val validNodes = nodes.filter { it.first < days }
        val startDay = startOfDay(startMillis, 0)

        val drafts = mutableListOf<PlanDraft>()
        topics.take(2).forEachIndexed { topicIndex, topic ->
            // 第二个知识点错开 90 分钟，避免两条排在同一时刻
            val startMin = 19 * 60 + topicIndex * 90
            validNodes.forEachIndexed { nodeIndex, (offset, template) ->
                val begin = startDay + offset * 86_400_000L + startMin * 60_000L
                drafts += PlanDraft(
                    startAt = begin,
                    endAt = begin + 60 * 60_000L,
                    title = template.format(topic).take(24),
                    tag = if (nodeIndex == 0) EventTag.HOMEWORK else EventTag.EXAM,
                    note = if (nodeIndex == 0) "第 1 遍 · 通读并整理要点" else "第 ${nodeIndex + 1} 遍 · 先回忆再看"
                )
            }
        }
        return drafts.sortedBy { it.startAt }
    }

    // ══════════════════════════════════════════════════════════
    // 小工具
    // ══════════════════════════════════════════════════════════

    /** "08:00" / "8:00" / "08:00:00" → 480；解析不了返回 null */
    fun parseClock(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split(':')
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** 今天 0 点往后推 [dayOffset] 天的 0 点 */
    fun startOfDay(todayMillis: Long, dayOffset: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = todayMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }.timeInMillis

    private fun tagOf(raw: String?): Int {
        val name = raw?.trim().orEmpty()
        val index = EventTag.names.indexOf(name)
        return if (index >= 0) index else EventTag.OTHER
    }

    private fun dateText(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%d月%d日".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    fun weekdayName(dow: Int): String = when (dow) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }
}
