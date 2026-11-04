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
 * 摆在卡片里等用户勾选，勾中才转成 UserEvent 落库。
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
 * 这一层是**纯函数**（除了取当前时间），单测能直接跑，不需要网络。
 * 之所以把它单独拎出来：模型返回的 JSON 永远不能全信，边界校验才是重点，
 * 而这些东西必须能离线测。
 */
object StudyPlanner {

    /** 一天里可安排的时间窗：早 8 点到晚 10 点，再晚就不该排了 */
    private const val DAY_START = 8 * 60
    private const val DAY_END = 22 * 60

    /** 小于一小时的空档不排 —— 排了也做不完一件事 */
    private const val MIN_SLOT = 60

    /** 每天最多给几段，避免提示词过长、也避免模型把一天排满 */
    private const val MAX_SLOT_PER_DAY = 3

    private val json = Json { ignoreUnknownKeys = true }

    // ══════════════════════════════════════════════════════════
    // 一、算空闲时段
    // ══════════════════════════════════════════════════════════

    /**
     * 算出未来 [horizonDays] 天里可以安排学习的空档。
     *
     * 做法是「一天内把上课区间合并，再取补集」，不区分工作日周末 ——
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
    // 二、拼提示词
    // ══════════════════════════════════════════════════════════

    /**
     * 系统提示词：定角色 + 定输出格式。
     *
     * 格式要求写得非常死（只输出 JSON、字段名、取值范围），因为
     * 国内这几家的 JSON 模式支持参差，靠提示词约束 + 解析时兜底最稳。
     */
    fun systemPrompt(): String = """
        你是一个大学学习规划助手，帮学生把本周的空闲时间排成具体可执行的学习任务。

        只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块、不要在 JSON 前后加字。
        格式如下：
        {"items":[{"day":0,"start":"19:00","end":"20:30","title":"复习高等数学·极限","tag":"作业","note":""}]}

        约束：
        1. day 是相对今天的天数，0=今天，1=明天，最多 6。
        2. start/end 用 24 小时制 HH:mm，必须落在给定空闲时段之内，单条时长 45~120 分钟。
        3. title 不超过 14 个汉字，要具体到「哪门课、做什么」，禁止只写「学习」「复习」「自习」。
        4. tag 只能是 作业 / 考试 / 课程 / 生活 / 其他 之一。
        5. 一共 5~9 条，优先排最近三天，每天不超过 2 条，留出休息时间。
        6. note 可以写要点，没有就留空字符串。
    """.trimIndent()

    /** 用户内容：课表摘要 + 空闲时段 + 学习目标 */
    fun userPrompt(
        courses: List<Course>,
        week: Int,
        today: Long,
        todayDow: Int,
        slots: List<FreeSlot>,
        goal: String
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
            val dayName = if (it.dayOffset == 0) "今天" else "第 ${it.dayOffset} 天后"
            "$dayName（day=${it.dayOffset}）${it.startClock}-${it.endClock}，共 ${it.length} 分钟"
        }.ifEmpty { "（没有可用的整块空闲时间）" }

        return """
            今天是第 $week 周 ${weekdayName(todayDow)}（${dateText(today)}）。
            本学期的课表（本周实际有课的）：
            $timetable

            可以安排的空闲时段：
            $slotText

            我的目标：${goal.ifBlank { "把本周的作业和复习排进这些空档，别排太满" }}

            请按系统提示里的 JSON 格式生成计划。
        """.trimIndent()
    }

    // ══════════════════════════════════════════════════════════
    // 三、解析回复
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
                val day = obj["day"]?.jsonPrimitive?.content?.trim()?.toIntOrNull() ?: return@runCatching null
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
     * 示例计划：没配 Key 时给用户看效果用的。
     *
     * 不是编造的假时间 —— 它是按**真实课表算出的真实空档**排的，
     * 只是任务名用模板生成。所以用户看到的位置就是他晚上真的有空的时候，
     * 转换成日程后也确实落得进去。
     */
    fun samplePlan(
        courses: List<Course>,
        slots: List<FreeSlot>,
        todayMillis: Long = System.currentTimeMillis()
    ): List<PlanDraft> {
        if (slots.isEmpty()) return emptyList()
        val subjects = courses
            .filter { it.isArranged }
            .distinctBy { it.name }
            .map { it.name }
            .ifEmpty { listOf("高等数学", "大学英语") }

        val templates = listOf(
            "复习《%s》· 本周内容梳理" to EventTag.HOMEWORK,
            "写《%s》作业" to EventTag.HOMEWORK,
            "预习《%s》下一章" to EventTag.OTHER,
            "整理《%s》错题" to EventTag.EXAM
        )

        val drafts = mutableListOf<PlanDraft>()
        // 每天最多取一段、总共 4 条：示例的用途是看排版，不是真的排满一周
        slots.filterIndexed { index, _ -> index % 2 == 0 }.take(4).forEachIndexed { i, slot ->
            val subject = subjects[i % subjects.size]
            val (titleTemplate, tag) = templates[i % templates.size]
            val base = startOfDay(todayMillis, slot.dayOffset)
            val length = minOf(90, slot.length)
            drafts += PlanDraft(
                startAt = base + slot.startMinutes * 60_000L,
                endAt = base + (slot.startMinutes + length) * 60_000L,
                title = titleTemplate.format(subject).take(24),
                tag = tag
            )
        }
        return drafts
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
