package com.bistu.allthebistu.data.classroom

import org.json.JSONArray
import org.json.JSONObject

/**
 * 「课程时间地点表」的解析与字段适配。
 *
 * ## 数据来源
 * `POST https://jwxt.bistu.edu.cn/jwapp/sys/kbapp/modules/qxkbcx.do`
 * 表单：`action=rwsjdd&XNXQDM=<学期>&page=1&rows=2000`
 * ——这是教务「全校总课表 → 课程时间地点表」页面背后真正的数据接口
 * （前端在 kbapp 的 `*default/modules/qxkbcx/sjdd/sjdd.js` 里配置的）。
 *
 * 返回 `{code:"0", datas:{rwsjdd:{total,rows:[...]}}}`，一行一个教学班安排。
 *
 * ## 为什么字段名要"容错"
 * 这套系统的列名是拼音缩写，而且**同一份数据在不同学校/版本叫法不一样**
 * （教室 `JASMC`/`JASDM`/`CDMC`，节次 `JC`/`KSDJ+JSDJ`/`DJ`）。
 * 所以这里按候选名依次尝试，解析不了的行**不猜**，计入 [Parsed.unmapped]
 * 并把首行的实际字段名记进 [Parsed.sampleKeys]，方便对着真数据补。
 */
object RoomScheduleParser {

    /** 一行都没解析出来时，把原始响应前这么多字符留给上层做排查 */
    private const val RAW_KEEP = 4000

    /** 接口原始返回 */
    data class Payload(
        val rows: List<JSONObject>,
        val total: Int,
        val raw: String,
    )

    /** 解析结果 */
    data class Parsed(
        val lessons: List<EmptyRoomFinder.RoomLesson>,
        /** 出现过的教学楼（用于界面筛选） */
        val buildings: List<String>,
        /** 出现过的教室（⚠️ 只含「这学期排过课」的教室，见下方注释） */
        val rooms: List<String>,
        /** 字段对不上、被丢掉的行数 */
        val unmapped: Int,
        /** 首行的字段名，排查用 */
        val sampleKeys: String,
    )

    /**
     * 从响应文本里取出 rows。
     *
     * 兼容三种壳：`datas.rwsjdd.rows`（实际）、`datas.rows`、根上的 `rows`。
     * `code` 非 "0" 说明业务失败（通常是会话过期），直接抛出去让上层走换票。
     */
    fun parse(raw: String): Payload {
        val root = JSONObject(raw)
        val code = root.optString("code")
        if (code.isNotEmpty() && code != "0") {
            throw IllegalStateException("时间地点表返回 code=$code，${root.optString("msg")}")
        }

        val datas = root.optJSONObject("datas")
        val node = datas?.optJSONObject("rwsjdd")
            ?: datas?.takeIf { it.has("rows") }
            ?: root

        val arr: JSONArray? = node.optJSONArray("rows")
            ?: root.optJSONArray("rows")

        val rows = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { add(it) }
                }
            }
        }

        val total = node.optInt("total", 0).takeIf { it > 0 }
            ?: datas?.optInt("total", 0)?.takeIf { it > 0 }
            ?: rows.size

        return Payload(rows = rows, total = total, raw = raw.take(RAW_KEEP))
    }

    /** rows → 占用记录 */
    fun toLessons(payload: Payload): Parsed {
        val lessons = ArrayList<EmptyRoomFinder.RoomLesson>(payload.rows.size)
        var unmapped = 0

        payload.rows.forEach { row ->
            val lesson = toLesson(row)
            if (lesson == null) unmapped++ else lessons += lesson
        }

        val buildings = lessons.map { it.building }.filter { it.isNotBlank() }.distinct().sorted()
        // ⚠️ 只能拿到「排过课」的教室：整学期一节课都没有的教室不出现在这张表里，
        //    所以空教室结果会漏掉它们。要补全得另找教室清单接口（还没找到）。
        val rooms = lessons.map { it.room }.filter { it.isNotBlank() }.distinct()

        return Parsed(
            lessons = lessons,
            buildings = buildings,
            rooms = rooms,
            unmapped = unmapped,
            sampleKeys = payload.rows.firstOrNull()?.keys()?.asSequence()?.joinToString(",") ?: "",
        )
    }

    /** 单行 → [EmptyRoomFinder.RoomLesson]，认不出来返回 null（不猜） */
    internal fun toLesson(row: JSONObject): EmptyRoomFinder.RoomLesson? {
        val room = pick(row, "JASMC", "JASDM", "JSMC", "CDMC", "JAS", "ROOMMC")
            ?.trim().orEmpty()
        if (room.isEmpty()) return null

        val building = pick(row, "JXLMC", "JXLDM", "JXDDMC", "XXXQMC", "JXDD")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: buildingOf(room)

        // 星期：教务「教室课表」列表里叫 SKXQ（上课星期），课程时间地点表里叫 XQ
        val day = parseDay(pick(row, "SKXQ", "XQ", "XQMC", "XQJ", "XINGQI", "WEEKDAY"))
            ?: return null
        val (begin, end) = parseSections(row) ?: return null

        val weekText = pick(row, "SKZC", "SKZCMC", "ZC", "ZCMS", "SKZCMS").orEmpty()
        val weeks = EmptyRoomFinder.parseWeeks(weekText)
        // 周次解析不出来 → 整行丢弃：宁可少算一间空教室，也不能把有课的教室说成空的
        if (weeks.isEmpty()) return null

        return EmptyRoomFinder.RoomLesson(
            room = room,
            building = building,
            dayOfWeek = day,
            beginSection = begin,
            endSection = end,
            weeks = weeks,
        )
    }

    /** 按候选字段名取第一个非空值 */
    private fun pick(row: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            if (!row.has(k) || row.isNull(k)) continue
            val v = row.optString(k).trim()
            if (v.isNotEmpty() && v != "null") return v
        }
        return null
    }

    /**
     * 从教室号反推楼栋（课表里没有楼栋字段时的兜底）。
     *
     * 学校教室号是「楼栋缩写-房间号」；对应关系从教务处「教室课表」页面的真实数据确认过：
     * `WLA-313` → 文理楼A座，`WLB-602` → 文理楼B座，`XXC-309` → 信息楼C座，
     * `SJA-201` → 工程训练中心。
     *
     * 认不出来就返回空串（界面按「未分楼」处理），不硬编。
     */
    internal fun buildingOf(room: String): String {
        val code = room.substringBefore('-').trim().uppercase()
        if (code.length < 2) return ""
        val letter = code.getOrNull(2)
        return when (code.take(2)) {
            "WL" -> letter?.let { "文理楼$it" } ?: ""
            "XX" -> letter?.let { "信息楼$it" } ?: ""
            "GX" -> letter?.let { "工学楼$it" } ?: ""
            "SJ" -> "工程训练中心"
            else -> ""
        }
    }

    /**
     * 星期：`3` / `周三` / `星期三` 都认。
     */
    internal fun parseDay(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        s.toIntOrNull()?.let { return if (it in 1..7) it else null }

        // 周一..周日 / 星期一..星期日 / 周天
        // ⚠️ 不能用 indexOf 扫一个数组：日 和 天 都得落在 7 上，
        //    排成一列的话「周天」会被算成 8。
        val map = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4,
            "五" to 5, "六" to 6, "日" to 7, "天" to 7,
        )
        return map.entries.firstOrNull { s.contains(it.key) }?.value
    }

    /**
     * 节次：返回 (起, 止)，认不出来返回 null。
     *
     * 三种写法都要覆盖：
     * 1. `KSDJ=3, JSDJ=4`（开始/结束节，最省事）
     * 2. `JC="3-4"` / `JC="第3-4节"`（字符串区间）
     * 3. `DJ="2"`（大节序号）→ 按「第 n 大节 = 第 2n-1 ~ 2n 小节」换算
     */
    internal fun parseSections(row: JSONObject): Pair<Int, Int>? {
        val ks = pick(row, "KSDJ", "KSJC", "QSDJ")
        val js = pick(row, "JSDJ", "JSJC", "ZZDJ")
        val a = ks?.toIntOrNull()
        val b = js?.toIntOrNull()
        if (a != null && b != null && a in 1..20 && b in a..20) return a to b

        val range = pick(row, "JC", "JCMC", "SJDD", "JCSM")
        if (range != null) {
            val nums = Regex("""\d+""").findAll(range).map { it.value.toInt() }.toList()
            if (nums.isNotEmpty()) {
                val from = nums.first()
                val to = nums.getOrNull(1) ?: from
                if (from in 1..20 && to in from..20) return from to to
            }
        }

        val dj = pick(row, "DJ", "DJMC")?.toIntOrNull()
        if (dj != null && dj in 1..10) return (dj * 2 - 1) to (dj * 2)

        return null
    }
}
