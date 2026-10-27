package com.bistu.allthebistu.data.classroom

/**
 * 教室课表 → 空教室 的反查。
 *
 * ## 为什么要反查
 * 教务系统**没有**「给我一堆空教室」这种接口，只有「教室课表」：
 * 告诉你某个教室在哪些周、星期几、第几节有课。想知道「周三下午三四节哪些教室空着」，
 * 只能把所有教室的课表拉下来，再逐个判断该时段有没有课——差集就是空教室。
 *
 * ## 判空规则（三条，缺一不可）
 * 1. **周次**：这节课要在查询的这一周里才算数（`1-8,10-16` 这种要展开成集合）
 * 2. **星期**：同一天
 * 3. **节次区间**：查询区间与课程区间**有交集**就算占着。
 *    注意边界——课程占 1-2 节、查 3-4 节，**不算冲突**（3 > 2），这个最容易写错。
 *
 * 所以「空」= 该教室里**不存在**同时满足这三条的课程。
 */
object EmptyRoomFinder {

    /**
     * 一间教室的一条排课记录。
     *
     * 字段刻意用「周次集合 + 节次区间」而不是原始字符串：
     * 解析（`1-8周(单)` 之类）属于适配层的事，这里只做纯判断，好测。
     */
    data class RoomLesson(
        /** 教室名，如 `A-101`、`文理A101` */
        val room: String,
        /** 教学楼 / 校区名，用于分组 */
        val building: String,
        /** 星期 1..7 */
        val dayOfWeek: Int,
        /** 起始节（含） */
        val beginSection: Int,
        /** 结束节（含） */
        val endSection: Int,
        /** 这节课在哪些教学周上（1 起） */
        val weeks: Set<Int>,
    ) {
        /** 第 [week] 周、星期 [day]、[from]..[to] 节这个时段，这间教室是否被占用 */
        fun occupies(week: Int, day: Int, from: Int, to: Int): Boolean {
            if (dayOfWeek != day) return false
            if (week !in weeks) return false
            // 区间相交才算冲突：课程[1,2] 与查询[3,4] 不相交 → 不占
            return beginSection <= to && endSection >= from
        }
    }

    /**
     * 某个时段空着的教室。
     *
     * @param lessons 该教学楼（或全部）所有教室的排课记录
     * @param allRooms 全部教室名（**必须显式给**：一间课都没排的教室在课表里根本不出现，
     *                 只从 [lessons] 里取教室名会把它漏掉）
     * @param week 教学周
     * @param dayOfWeek 星期 1..7
     * @param fromSection 起始节（含）
     * @param toSection 结束节（含）
     * @return 空教室名，按「楼栋 + 房间号数字序」排好
     */
    fun findEmpty(
        lessons: List<RoomLesson>,
        allRooms: List<String>,
        week: Int,
        dayOfWeek: Int,
        fromSection: Int,
        toSection: Int,
    ): List<String> {
        if (fromSection > toSection) return emptyList()

        val busy = HashSet<String>()
        for (lesson in lessons) {
            if (lesson.occupies(week, dayOfWeek, fromSection, toSection)) {
                busy += lesson.room
            }
        }
        return allRooms
            .filter { it.isNotBlank() && it !in busy }
            .distinct()
            .sortedWith(::compareRooms)
    }

    /**
     * 教室名排序：**数字按数值比**，不要按字典序。
     *
     * 字典序会把 `A-10` 排到 `A-9` 前面（`"1" < "9"`），一排教室编号看着就乱。
     */
    fun compareRooms(a: String, b: String): Int {
        val ta = tokenize(a)
        val tb = tokenize(b)
        var i = 0
        while (i < ta.size && i < tb.size) {
            val x = ta[i]
            val y = tb[i]
            val cmp = if (x is Long && y is Long) {
                x.compareTo(y)
            } else {
                x.toString().compareTo(y.toString(), ignoreCase = true)
            }
            if (cmp != 0) return cmp
            i++
        }
        return ta.size.compareTo(tb.size)
    }

    /** 把 `A-101` 切成 ["a-", 101]；数字与文字交界处断开，方便按数值比较 */
    private fun tokenize(s: String): List<Any> {
        val out = ArrayList<Any>()
        val sb = StringBuilder()
        var lastWasDigit = false
        for (ch in s) {
            val isDigit = ch.isDigit()
            if (sb.isNotEmpty() && isDigit != lastWasDigit) {
                out += if (lastWasDigit) sb.toString().toLongOrNull() ?: sb.toString() else sb.toString()
                sb.clear()
            }
            sb.append(ch)
            lastWasDigit = isDigit
        }
        if (sb.isNotEmpty()) {
            out += if (lastWasDigit) sb.toString().toLongOrNull() ?: sb.toString() else sb.toString()
        }
        return out
    }

    /**
     * 把教务的周次字符串展开成周次集合。
     *
     * 支持实测见过的几种写法：
     * - `1-16`            → 1..16
     * - `1-8,10-16`       → 跳过 9
     * - `1-16周`           → 去掉单位
     * - `1-16(单)` / `单周`  → 只留奇数周
     * - `1-16(双)` / `双周`  → 只留偶数周
     * - 单个数 `5`          → setOf(5)
     *
     * 解析不出来就返回**空集合**（当作「这学期不上」而不是「每周都上」）：
     * 宁可少算一间空教室，也不要把有课的教室说成空的。
     */
    fun parseWeeks(raw: String): Set<Int> {
        if (raw.isBlank()) return emptySet()

        val odd = raw.contains("单")
        val even = raw.contains("双")

        val cleaned = raw
            .replace("周", "")
            .replace("(", "").replace(")", "")
            .replace("（", "").replace("）", "")
            .replace("单", "").replace("双", "")
            .replace("，", ",")
            .trim()

        val result = LinkedHashSet<Int>()
        for (part in cleaned.split(",")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            if (p.contains("-") || p.contains("~")) {
                val bounds = p.split("-", "~")
                val from = bounds.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
                val to = bounds.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
                if (from <= 0 || to < from) continue
                for (w in from..to) result += w
            } else {
                p.toIntOrNull()?.let { if (it > 0) result += it }
            }
        }

        return when {
            odd && !even -> result.filter { it % 2 == 1 }.toSet()
            even && !odd -> result.filter { it % 2 == 0 }.toSet()
            else -> result
        }
    }
}
