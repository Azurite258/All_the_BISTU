package com.bistu.allthebistu.data.classroom

import com.bistu.allthebistu.data.classroom.EmptyRoomFinder.RoomLesson
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「按教室查课表」响应的解析。
 *
 * ## 接口
 * `POST /jwapp/sys/kbapp/api/qxkbcx/getScheduleDetail.do`
 * 表单：`XNXQDM=<学期>&CODE=<教室代码>&KBLX=01`
 *
 * ## 为什么解析写得这么宽松
 * 这个接口是课表组件 `kbxs` 渲染用的，和「我的课表」共用同一个渲染器，
 * 但**外层包了一层随接口变的键名**（我的课表是 `datas.getMyScheduleDetail`，
 * 这里未知）。写死键名就会「换个接口就全空」，所以这里**递归找 `arrangedList`**，
 * 不管它被塞在第几层。
 *
 * 行内字段和我的课表一致（实测样本）：
 * `dayOfWeek`(1..7)、`beginSection`/`endSection`、`week`（位图字符串）、`placeName`。
 *
 * ## 教室归属
 * 查的就是这间教室的课表，所以每行都归给**被查询的教室**，
 * 不用 `placeName` 反推——同名教室、多教室合班都会让反推出错。
 * `placeName` 只留着打日志。
 */
object RoomTimetableParser {

    /** 递归深度上限：响应最多 3~4 层，再深就是异常结构了 */
    private const val MAX_DEPTH = 5

    /**
     * @param raw 接口原始响应
     * @param roomName 被查询的教室名（用于归属和日志）
     * @return 这间教室的占用记录；没有课就返回空 list（不是失败）
     */
    fun parse(raw: String, roomName: String): List<RoomLesson> {
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw IllegalStateException("教室课表不是 JSON")

        val code = root.optString("code")
        if (code.isNotEmpty() && code != "0") {
            throw IllegalStateException("教室课表返回 code=$code，${root.optString("msg")}")
        }

        val datas = root.optJSONObject("datas") ?: return emptyList()
        val list = deepFindArray(datas, "arrangedList") ?: return emptyList()

        val out = ArrayList<RoomLesson>(list.length())
        for (i in 0 until list.length()) {
            val row = list.optJSONObject(i) ?: continue
            toLesson(row, roomName)?.let { out += it }
        }
        return out
    }

    /**
     * 一行 → 占用记录；判断不了就返回 null（宁可少算，不把有课的教室当空的）。
     */
    internal fun toLesson(row: JSONObject, roomName: String): RoomLesson? {
        val day = row.optInt("dayOfWeek", 0)
        val begin = row.optInt("beginSection", 0)
        val end = row.optInt("endSection", 0)
        if (day !in 1..7) return null
        if (begin <= 0 || end < begin) return null

        // 周次：优先位图（实测格式 "001111111111"），退回到周次文本
        val weekRaw = row.optString("week").ifBlank {
            row.optString("weeksAndTeachers")
        }
        val weeks = EmptyRoomFinder.parseWeeks(weekRaw)
        if (weeks.isEmpty()) return null

        return RoomLesson(
            room = roomName,
            building = "",
            dayOfWeek = day,
            beginSection = begin,
            endSection = end,
            weeks = weeks,
        )
    }

    /** 在 JSON 树里递归找第一个数组字段（键名不固定，所以不能直接取） */
    internal fun deepFindArray(obj: JSONObject, key: String, depth: Int = 0): JSONArray? {
        if (depth > MAX_DEPTH) return null
        obj.optJSONArray(key)?.let { return it }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val child = obj.optJSONObject(keys.next()) ?: continue
            deepFindArray(child, key, depth + 1)?.let { return it }
        }
        return null
    }
}
