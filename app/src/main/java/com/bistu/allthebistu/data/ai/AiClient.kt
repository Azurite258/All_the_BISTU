package com.bistu.allthebistu.data.ai

import com.bistu.allthebistu.data.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 一次对话调用的结果。
 *
 * 刻意不用异常往外抛：调用点在 Compose 的协程里，异常会直接崩界面，
 * 而这里所有失败（没网、Key 错、余额不足、模型抽风）都是**可以原样展示给用户**的。
 */
sealed interface AiResult {

    /** 拿到了模型的文字回复（可能是一段 JSON 文本） */
    data class Ok(val text: String) : AiResult

    /** 失败了。[message] 是给用户看的一句话，[detail] 是技术细节（日志/小字） */
    data class Err(val message: String, val detail: String = "") : AiResult
}

/**
 * 大模型调用（OpenAI 兼容格式）。
 *
 * 只用到了一个接口：`POST {baseUrl}/chat/completions`。
 * 国内四家（DeepSeek / 阿里百炼 / 月之暗面 / 智谱）都是这套协议，
 * 所以换供应商只需要用户改设置里的两行，不需要发版。
 *
 * ## 为什么不用 SSE 流式
 * 学习计划的回复只有几百 token，整段等 3~5 秒完全可接受；
 * 流式要多写一套解析和状态机，收益很小。真要加，加在这里，界面不用动。
 */
object AiClient {

    private const val TAG = "AiClient"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            // 整体上限比 readTimeout 略大：给「服务端已经开始回、但很慢」留一点余量，
            // 又不会让用户对着转圈等两分钟
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 发一轮对话。
     *
     * @param system 系统提示词（角色 + 输出格式约束）
     * @param user 用户内容（课表摘要 + 任务）
     */
    suspend fun chat(
        config: AiConfig,
        system: String,
        user: String,
        maxTokens: Int = 1200
    ): AiResult = withContext(Dispatchers.IO) {
        if (!config.configured) {
            return@withContext AiResult.Err("还没有配置 API Key", "去「我的 → API Key 设置」里填一个")
        }

        val payload = buildJsonObject {
            put("model", AiConfig.sanitizeModel(config.model))
            put("temperature", 0.4)
            put("max_tokens", maxTokens)
            put("stream", false)
            put(
                "messages",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "system")
                            put("content", system)
                        }
                    )
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("content", user)
                        }
                    )
                }
            )
        }

        val url = AiConfig.sanitizeBaseUrl(config.baseUrl) + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey.trim()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext AiResult.Err(
                        message = friendlyError(response.code, body),
                        detail = "HTTP ${response.code} · ${body.take(160)}"
                    )
                }
                val content = extractContent(body)
                if (content.isNullOrBlank()) {
                    AiResult.Err("模型没有返回内容", body.take(160))
                } else {
                    AiResult.Ok(content.trim())
                }
            }
        } catch (e: SocketTimeoutException) {
            AppLog.w(TAG, "调用超时: ${e.message}")
            AiResult.Err("等太久了，模型没有在 90 秒内回完", "可以重试一次，或换个更快的模型")
        } catch (e: UnknownHostException) {
            AppLog.w(TAG, "域名解析失败: ${e.message}")
            AiResult.Err("连不上服务器", "检查网络，或看看接口地址是否填错")
        } catch (e: IOException) {
            AppLog.w(TAG, "网络错误: ${e.javaClass.simpleName}: ${e.message}")
            AiResult.Err("网络请求失败", "${e.javaClass.simpleName}: ${e.message}")
        } catch (t: Throwable) {
            AppLog.w(TAG, "调用异常: ${t.javaClass.simpleName}: ${t.message}")
            AiResult.Err("调用出错了", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 连通性测试：发一句最短的话，验证「Key 对不对、地址通不通、模型名存不存在」。
     *
     * maxTokens 压到 8：这一步只是为了看握手，不需要它真的写东西，省钱。
     */
    suspend fun test(config: AiConfig): AiResult =
        chat(config, "你是连通性测试助手，只回复 OK。", "ping", maxTokens = 8)

    /**
     * 把常见 HTTP 错误翻成用户能看懂的一句话。
     *
     * 401 / 402 / 429 是最常遇到的三个，也是用户唯一能自己解决的三个，
     * 所以单独给具体的处置建议，其余归为「服务端返回错误」。
     */
    private fun friendlyError(code: Int, body: String): String = when (code) {
        401, 403 -> "API Key 无效或没有权限"
        402 -> "账户余额不足，去服务商后台充点钱"
        404 -> "接口地址或模型名不对（404）"
        429 -> "请求太频繁或额度用完了，等会儿再试"
        in 500..599 -> "模型服务端出错了（$code），稍后重试"
        else -> "请求被拒绝（HTTP $code）"
    }.let { base ->
        // 服务商的错误说明往往比状态码有用（比如「model not found」），带上一小段
        val hint = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
                ?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        if (hint.isNullOrBlank()) base else "$base：${hint.take(60)}"
    }

    /** 从 OpenAI 兼容响应里取 `choices[0].message.content` */
    private fun extractContent(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
    }.getOrNull()
}
