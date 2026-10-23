package com.bistu.allthebistu.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File

/**
 * 一块学分要求（通识教育 / 专业教育 / 专业拓展…）。
 *
 * 教务给的层级是「总要求 → 一级分类 → 二级分类 → 课程」，
 * 界面上只需要一级分类，二级的先不展开。
 */
@Serializable
data class CreditCategory(
    val name: String,
    val required: Double,
    /** 已修（成绩通过后才算） */
    val earned: Double,
    /** 已选（选了课但还没出成绩） */
    val selection: Double
)

/**
 * 学业完成情况（教务「学业完成查询」/ 个人学业监测报告）。
 *
 * 来源接口：`/jwapp/sys/byshapp/api/grbg/queryXyzhbx.do`
 * 顶层是 `datas.queryXyzhbx.fanbx`（方案内），里面既有总学分，
 * 也有 children 组成的一级分类。
 */
@Serializable
data class StudyReport(
    /** 培养方案代码，如 2026070101 */
    val programCode: String = "",
    /** 要求总学分，如 132.0 */
    val creditsRequired: Double = 0.0,
    /** 已修学分（成绩通过） */
    val creditsEarned: Double = 0.0,
    /** 已选学分（本学期选了、还没出成绩的部分也算） */
    val creditsSelection: Double = 0.0,
    val categories: List<CreditCategory> = emptyList(),
    /** 抓取时间，用于界面显示「数据时间」 */
    val fetchedAt: Long = 0L
) {
    /** 毕业进度：已修 / 要求 */
    val earnedProgress: Float
        get() = if (creditsRequired <= 0.0) 0f
        else (creditsEarned / creditsRequired).toFloat().coerceIn(0f, 1f)
}

/** 解析教务返回的学业报告 JSON */
object StudyReportParser {

    fun parse(rawJson: String): StudyReport {
        val root = JSONObject(rawJson)
        val code = root.optString("code")
        require(code == "0") { "学业接口返回失败: code=$code, msg=${root.optString("msg")}" }

        val detail = root.optJSONObject("datas")?.optJSONObject("queryXyzhbx")
            ?: throw IllegalStateException("响应里没有 queryXyzhbx")

        // 方案内（fanbx）。方案外（fawbx）目前为空，先忽略。
        val fan = detail.optJSONObject("fanbx")
            ?: throw IllegalStateException("响应里没有 fanbx（方案内）")

        val children = fan.optJSONArray("children")
        val categories = buildList {
            if (children != null) {
                for (i in 0 until children.length()) {
                    val c = children.optJSONObject(i) ?: continue
                    val name = c.optString("courseCategoryName")
                    if (name.isBlank()) continue
                    add(
                        CreditCategory(
                            name = name,
                            required = c.optDouble("creditsRequired", 0.0),
                            earned = c.optDouble("creditsEarned", 0.0),
                            selection = c.optDouble("creditsSelection", 0.0)
                        )
                    )
                }
            }
        }

        return StudyReport(
            programCode = fan.optString("educationalProgramCode"),
            creditsRequired = fan.optDouble("creditsRequired", 0.0),
            creditsEarned = fan.optDouble("creditsEarned", 0.0),
            creditsSelection = fan.optDouble("creditsSelection", 0.0),
            categories = categories,
            fetchedAt = System.currentTimeMillis()
        )
    }
}

/**
 * 学业报告的本地缓存（离线可看）。
 *
 * 用文件而不是 Room：这份数据就一个对象，加表要为数据库写迁移，
 * 而它的生命周期跟登录态绑定——退出登录会整个删掉。
 */
class StudyReportStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val file: File get() = File(context.filesDir, FILE_NAME)

    fun save(report: StudyReport) {
        runCatching { file.writeText(json.encodeToString(StudyReport.serializer(), report)) }
            .onFailure { Log.w(TAG, "保存学业报告失败: ${it.message}") }
    }

    fun load(): StudyReport? {
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString(StudyReport.serializer(), file.readText())
        }.getOrElse {
            Log.w(TAG, "读取学业报告失败: ${it.message}")
            null
        }
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    companion object {
        private const val TAG = "StudyReportStore"
        private const val FILE_NAME = "study_report.json"
    }
}
