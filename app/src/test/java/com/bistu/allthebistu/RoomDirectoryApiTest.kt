package com.bistu.allthebistu

import com.bistu.allthebistu.data.classroom.RoomDirectoryApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教室清单解析。
 *
 * 样本就是 2026-10-27 从教务「教室课表」页面抓下来的真实响应（截取了 5 行），
 * 字段名一个都不许改——这里钉住的是教务的真实契约。
 */
class RoomDirectoryApiTest {

    private val sample = """
    {"code":"0","datas":{"jslb":{"totalSize":686,"pageNumber":1,"pageSize":10,"rows":[
      {"JASMC":"SJA-201仿真实训室","JXLDM_DISPLAY":"工程训练中心","SFPK":"0","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":35.0,"NAME":"SJA-201仿真实训室[050552]",
       "SFPK_DISPLAY":"否","CODE":"050552","WID":"fff9b221","LC":2.0,"JXLDM":"510",
       "JASLXDM":"07","DWDM":"14","JASLXDM_DISPLAY":"实验室","DWDM_DISPLAY":"工程训练中心"},
      {"JASMC":"WLA-506","JXLDM_DISPLAY":"文理楼A座 沙河校区","SFPK":"1","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":121.0,"NAME":"WLA-506[050122]","SFPK_DISPLAY":"是",
       "CODE":"050122","WID":"fa435b1f","LC":5.0,"JXLDM":"501","JASLXDM":"02","DWDM":"17",
       "JASLXDM_DISPLAY":"多媒体","DWDM_DISPLAY":"教务处"},
      {"JASMC":"WLC-210(语)","JXLDM_DISPLAY":"文理楼C座 沙河校区","SFPK":"1","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":40.0,"NAME":"WLC-210(语)[050353]","SFPK_DISPLAY":"是",
       "CODE":"050353","WID":"f96073cc","LC":2.0,"JXLDM":"503","JASLXDM":"08","DWDM":"17",
       "JASLXDM_DISPLAY":"语音室","DWDM_DISPLAY":"教务处"},
      {"JASMC":"电子技术实验室A1","JXLDM_DISPLAY":"信控中心 沙河校区","SFPK":"0","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":35.0,"NAME":"电子技术实验室A1[050358]","SFPK_DISPLAY":"否",
       "CODE":"050358","WID":"f1319bd6","LC":null,"JXLDM":"04","JASLXDM":"07","DWDM":"16",
       "JASLXDM_DISPLAY":"实验室","DWDM_DISPLAY":"电子信息与控制国家级实验教学中心"},
      {"JASMC":"XXD-205","JXLDM_DISPLAY":"信息楼 沙河校区","SFPK":"1","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":120.0,"NAME":"XXD-205[050330]","SFPK_DISPLAY":"是",
       "CODE":"050330","WID":"f0aa51eb","LC":2.0,"JXLDM":"05","JASLXDM":"02","DWDM":"17",
       "JASLXDM_DISPLAY":"多媒体","DWDM_DISPLAY":"教务处"},
      {"JASMC":"XXA-306","JXLDM_DISPLAY":"信息楼 沙河校区","SFPK":"1","XXXQDM":"10",
       "XXXQDM_DISPLAY":"沙河校区","SKZWS":40.0,"NAME":"XXA-306[050303]","SFPK_DISPLAY":"是",
       "CODE":"050303","WID":"f4830d2d","LC":3.0,"JXLDM":"05","JASLXDM":"02","DWDM":"17",
       "JASLXDM_DISPLAY":"多媒体","DWDM_DISPLAY":"教务处"}
    ]}}}
    """.trimIndent()

    @Test
    fun `能解析出全部字段`() {
        val page = RoomDirectoryApi.parse(sample)
        assertEquals(6, page.rooms.size)
        assertEquals(686, page.total)

        val wla = page.rooms.first { it.name == "WLA-506" }
        assertEquals("050122", wla.code)
        assertEquals("文理楼A座", wla.building)
        assertEquals("沙河校区", wla.campus)
        assertEquals(5, wla.floor)
        assertEquals("多媒体", wla.type)
        assertEquals(121, wla.seats)
        assertTrue(wla.hasSchedule)
    }

    @Test
    fun `没排课的教室 hasSchedule 为 false`() {
        val page = RoomDirectoryApi.parse(sample)
        val sj = page.rooms.first { it.name.startsWith("SJA-201") }
        assertFalse(sj.hasSchedule)
        assertEquals("工程训练中心", sj.building)
    }

    @Test
    fun `楼层为空时不崩`() {
        val page = RoomDirectoryApi.parse(sample)
        val lab = page.rooms.first { it.name == "电子技术实验室A1" }
        assertNull(lab.floor)
    }

    @Test
    fun `教学楼名会剪掉校区尾巴`() {
        assertEquals("文理楼A座", RoomDirectoryApi.stripCampus("文理楼A座 沙河校区"))
        assertEquals("信息楼", RoomDirectoryApi.stripCampus("信息楼 沙河校区"))
        assertEquals("工程训练中心", RoomDirectoryApi.stripCampus("工程训练中心"))
    }

    @Test
    fun `按楼分组并按楼层排序`() {
        val grouped = RoomDirectoryApi.groupByBuilding(RoomDirectoryApi.parse(sample).rooms)
        assertTrue(grouped.containsKey("文理楼A座"))
        assertTrue(grouped.containsKey("工程训练中心"))
        // 文理楼 A 座只有一间（样本里裁过），楼内排序规则由 compareRooms 保证
        assertEquals(listOf("WLA-506"), grouped.getValue("文理楼A座").map { it.name })
    }

    /**
     * 信息楼在教务里只有「信息楼」一个楼名，但实际分 A/B/C/D 四块。
     * 不拆开的话一个按钮里塞几百间教室，查一次要等很久。
     */
    @Test
    fun `信息楼按楼块拆开并排在最前`() {
        val grouped = RoomDirectoryApi.groupByBuilding(RoomDirectoryApi.parse(sample).rooms)

        // 前四位就是信息楼 A → D（样本里只有 A 和 D）
        assertEquals(
            listOf("信息楼A座", "信息楼D座", "文理楼A座", "文理楼C座"),
            grouped.keys.take(4).toList()
        )
        assertEquals(listOf("XXA-306"), grouped.getValue("信息楼A座").map { it.name })
        assertEquals(
            listOf("XXD-205"),
            grouped.getValue("信息楼D座").map { it.name }
        )

        // 工学类场地（工程训练中心/信控中心）排在文理楼之后
        val keys = grouped.keys.toList()
        assertTrue(keys.indexOf("工程训练中心") > keys.indexOf("文理楼C座"))
        assertTrue(keys.indexOf("信控中心") > keys.indexOf("文理楼C座"))
    }

    @Test
    fun `楼名拆分只在信息楼生效`() {
        // 信息楼教室号自带楼块
        assertEquals("信息楼A座", RoomDirectoryApi.refineBuilding("信息楼", "XXA-306"))
        assertEquals("信息楼D座", RoomDirectoryApi.refineBuilding("信息楼", "XXD-207(智)"))
        // 认不出楼块就保留原楼名，不瞎猜
        assertEquals("信息楼", RoomDirectoryApi.refineBuilding("信息楼", "会议室"))
        // 别的楼栋原样保留（文理楼 A/B/C 本来就是分开的三个楼名）
        assertEquals(
            "文理楼A座",
            RoomDirectoryApi.refineBuilding("文理楼A座", "WLA-506")
        )
    }

    @Test
    fun `楼栋排序权重`() {
        assertEquals(0, RoomDirectoryApi.buildingRank("信息楼A座"))
        assertEquals(3, RoomDirectoryApi.buildingRank("信息楼D座"))
        assertEquals(10, RoomDirectoryApi.buildingRank("文理楼A座"))
        assertEquals(11, RoomDirectoryApi.buildingRank("文理楼B座"))
        assertEquals(12, RoomDirectoryApi.buildingRank("文理楼C座"))
        assertEquals(20, RoomDirectoryApi.buildingRank("工学楼A座"))
        assertEquals(22, RoomDirectoryApi.buildingRank("工学楼C座"))
        // 工程训练中心 / 信控中心 这类场地排最后，组内按名字
        assertEquals(30, RoomDirectoryApi.buildingRank("工程训练中心"))
        assertEquals(30, RoomDirectoryApi.buildingRank("学生发展中心"))
    }

    /** 工学楼要整组排在文理楼之后（用户要的顺序：信息 → 文理 → 工学） */
    @Test
    fun `工学楼排在文理楼之后`() {
        assertTrue(
            RoomDirectoryApi.buildingRank("工学楼A座") >
                RoomDirectoryApi.buildingRank("文理楼C座")
        )
        assertTrue(
            RoomDirectoryApi.buildingRank("工学楼C座") <
                RoomDirectoryApi.buildingRank("工程训练中心")
        )
    }

    @Test
    fun `业务失败要抛异常而不是返回空清单`() {
        try {
            RoomDirectoryApi.parse("""{"code":"1","msg":"未登录","datas":null}""")
            throw AssertionError("应该抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("未登录") == true)
        }
    }

    @Test
    fun `没有教室代码的行被丢掉`() {
        val json = """{"code":"0","datas":{"jslb":{"totalSize":1,"rows":[{"JASMC":"A-101"}]}}}"""
        assertEquals(0, RoomDirectoryApi.parse(json).rooms.size)
    }

    @Test
    fun `datas 里的模型名换了也能解析`() {
        // 换 action（jslb → 别的）时键名会变，不能写死
        val json = """{"code":"0","datas":{"other":{"totalSize":1,"rows":[
            {"CODE":"1","JASMC":"A-101","SKZWS":40}
        ]}}}"""
        assertEquals(1, RoomDirectoryApi.parse(json).rooms.size)
    }

    /**
     * 0.16 装机后报「教室清单拿不到数据」的真实形态：
     * 少了必需参数 XNXQDM 时，服务端 **不报错**，只回 `datas:{}` + searchMeta。
     *
     * 这种响应必须抛出来，绝不能悄悄当成「0 间教室」——那样界面会显示
     * 「这个时段教室都排满了」，把接口问题伪装成正常结果。
     */
    @Test
    fun `缺少参数导致的空 datas 要抛异常而不是当成 0 间`() {
        val json = """{"code":"0","datas":{},"searchMeta":{"name":"jslb"}}"""
        val e = assertThrows(IllegalStateException::class.java) {
            RoomDirectoryApi.parse(json)
        }
        assertTrue(e.message?.contains("rows") == true)
    }
}
