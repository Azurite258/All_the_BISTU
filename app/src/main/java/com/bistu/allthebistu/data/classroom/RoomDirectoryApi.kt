package com.bistu.allthebistu.data.classroom

import org.json.JSONObject

/**
 * 教室清单（教务「教室课表」页面的数据源）。
 *
 * ## 接口
 * `POST /jwapp/sys/kbapp/modules/qxkbcx/jslb.do`
 * 模型 `S_KBCK_JASXX`（教室信息），返回：
 * ```
 * {"code":"0","datas":{"jslb":{"totalSize":686,"pageNumber":1,"pageSize":10,
 *   "rows":[{"CODE":"050122","JASMC":"WLA-506","JXLDM_DISPLAY":"文理楼A座 沙河校区",
 *            "LC":5.0,"SKZWS":121.0,"JASLXDM_DISPLAY":"多媒体","SFPK":"1",...}]}}}
 * ```
 *
 * ## 字段对应（实测样本核对过）
 * | 接口字段 | 含义 |
 * |---|---|
 * | `CODE` | 教室代码（点「课表」时用它查课表） |
 * | `JASMC` | 教室名称，如 `WLA-506` |
 * | `JXLDM_DISPLAY` | 所属教学楼，带校区后缀，如 `文理楼A座 沙河校区` |
 * | `XXXQDM_DISPLAY` | 校区，如 `沙河校区` |
 * | `LC` | 楼层（可能是 null，比如实验中心的房间） |
 * | `SKZWS` | 上课座位数 |
 * | `JASLXDM_DISPLAY` | 教室类型：多媒体 / 实验室 / 语音室 |
 * | `SFPK` | 是否存在排课，`"1"` = 有 |
 *
 * ## 为什么这份清单重要
 * 之前只能从「排过课的教室」反推教室名，**整学期一节课都没排的教室会整个消失**
 * （比如工程训练中心的实训室）。这份接口一次给出全部 686 间，不依赖有没有课。
 */
object RoomDirectoryApi {

    const val PATH = "https://jwxt.bistu.edu.cn/jwapp/sys/kbapp/modules/qxkbcx/jslb.do"

    /** 一间教室 */
    data class Room(
        /** 教室代码（如 `050122`），查该教室课表要用它 */
        val code: String,
        /** 教室名（如 `WLA-506`） */
        val name: String,
        /** 所属教学楼，已去掉校区后缀（如 `文理楼A座`） */
        val building: String,
        /** 校区（如 `沙河校区`） */
        val campus: String,
        /** 楼层；null 表示教务没填 */
        val floor: Int?,
        /** 教室类型：多媒体 / 实验室 / 语音室 */
        val type: String,
        /** 上课座位数 */
        val seats: Int,
        /** 是否有排课 */
        val hasSchedule: Boolean,
    )

    /** 一页结果 */
    data class Page(
        val rooms: List<Room>,
        /** 教务侧总数（686），用来判断有没有拿全 */
        val total: Int,
    )

    /**
     * 解析列表响应。
     *
     * 结构是 `datas.<模型名>.rows`，模型名跟着 action 走（这里是 `jslb`），
     * 所以不去硬编码键名，扫第一个带 `rows` 的对象即可——以后换 action 也能用。
     */
    fun parse(raw: String): Page {
        val root = JSONObject(raw)
        val code = root.optString("code")
        if (code.isNotEmpty() && code != "0") {
            throw IllegalStateException("教室清单返回 code=$code，${root.optString("msg")}")
        }

        val datas = root.optJSONObject("datas")
            ?: throw IllegalStateException("教室清单没有 datas（可能没登录）")

        val node = datas.keys().asSequence()
            .mapNotNull { datas.optJSONObject(it) }
            .firstOrNull { it.has("rows") }
            ?: throw IllegalStateException("教室清单里没有 rows")

        val arr = node.optJSONArray("rows")
        val rooms = buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { row -> toRoom(row)?.let { add(it) } }
                }
            }
        }
        return Page(rooms = rooms, total = node.optInt("totalSize", rooms.size))
    }

    /** 单行 → [Room]；教室代码或名字缺失就丢掉（没法用来查课表） */
    internal fun toRoom(row: JSONObject): Room? {
        val code = row.optString("CODE").trim()
        val name = row.optString("JASMC").trim()
        if (code.isEmpty() || name.isEmpty()) return null

        return Room(
            code = code,
            name = name,
            building = refineBuilding(stripCampus(row.optString("JXLDM_DISPLAY")), name),
            campus = row.optString("XXXQDM_DISPLAY").trim(),
            floor = row.optDouble("LC", Double.NaN).takeIf { !it.isNaN() }?.toInt(),
            type = row.optString("JASLXDM_DISPLAY").trim(),
            seats = row.optDouble("SKZWS", 0.0).toInt(),
            hasSchedule = row.optString("SFPK").trim() == "1",
        )
    }

    /** 信息楼教室号的楼块前缀：`XXA-306` / `XXD-207(智)` → A / D */
    private val INFO_BLOCK = Regex("^XX([A-D])(?![A-Za-z])")

    /**
     * 信息楼在教务里是**一整栋**（`JXLDM_DISPLAY` 都写「信息楼 沙河校区」），
     * 但实际分 A/B/C/D 四个区，楼块就藏在教室号开头（`XXA-306`、`XXD-207`）。
     *
     * 不拆开的话「信息楼」一个按钮里塞几百间教室，点一下要拉很久，
     * 而且查「D 座有没有空教室」得连着别的座一起等。
     */
    internal fun refineBuilding(building: String, roomName: String): String {
        if (!building.startsWith("信息楼")) return building
        val block = INFO_BLOCK.find(roomName)?.groupValues?.get(1) ?: return building
        return "信息楼${block}座"
    }

    /** 信息楼四块固定排最前，按 A→D；其余楼栋走 [buildingRank] */
    private val INFO_ORDER = listOf("信息楼A座", "信息楼B座", "信息楼C座", "信息楼D座")

    /**
     * 教学楼在筛选条里的排序权重（小的在前）：
     * 信息楼 A→D → 文理楼 A→C → 工学类场地（工程训练中心/信控中心）→ 其他按名字。
     */
    internal fun buildingRank(name: String): Int {
        INFO_ORDER.indexOf(name).let { if (it >= 0) return it }
        if (name.startsWith("信息楼")) return INFO_ORDER.size
        if (name.startsWith("文理楼")) {
            val letter = name.getOrNull(3)
            val offset = if (letter == null) 0 else (letter - 'A').coerceIn(0, 5)
            return 10 + offset
        }
        if (name.startsWith("工学") || name.contains("工程训练") || name.contains("信控")) return 20
        return 30
    }

    /**
     * `文理楼A座 沙河校区` → `文理楼A座`
     *
     * 校区名放在楼名后面、用空格分隔，界面上按楼分组时带着校区很啰嗦，
     * 剪掉尾巴。没有空格就原样返回。
     */
    internal fun stripCampus(building: String): String {
        val s = building.trim()
        val cut = s.indexOf(' ')
        return if (cut > 0) s.substring(0, cut).trim() else s
    }

    /**
     * 按楼分组，楼内按「楼层 → 教室名数值序」排。
     *
     * 楼栋顺序不按字典序（中文按拼音排会乱），走 [buildingRank]：
     * 信息楼 A→D 最前，其次文理楼，再工学类场地。
     */
    fun groupByBuilding(rooms: List<Room>): Map<String, List<Room>> =
        rooms.groupBy { it.building }
            .entries
            .sortedWith(compareBy({ buildingRank(it.key) }, { it.key }))
            .associate { (building, list) ->
                building to list.sortedWith(
                    compareBy<Room> { it.floor ?: Int.MAX_VALUE }
                        .thenComparator { a, b -> EmptyRoomFinder.compareRooms(a.name, b.name) }
                )
            }
}
