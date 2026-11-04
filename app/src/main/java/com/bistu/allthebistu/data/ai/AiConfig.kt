package com.bistu.allthebistu.data.ai

/**
 * 接入大模型需要的三项配置。
 *
 * 为什么把 Key 放在**设置里让用户自己填**，而不是编译进 APK：
 * 仓库 2026-10-31 已经转 public，写死的 Key 等于公开；而且 APK 能被反编译，
 * 谁都能拿去刷。用户自填是唯一既安全又不需要后端服务器的做法。
 *
 * 接口一律按 **OpenAI 兼容格式** 调用（`POST {baseUrl}/chat/completions`），
 * 国内几家（DeepSeek / 阿里百炼 / 月之暗面 / 智谱）都支持这套，换供应商只改
 * 地址和模型名，代码一行不用动。
 */
data class AiConfig(
    /** 用户填的密钥，空串 = 没配 */
    val apiKey: String = "",
    /** 接口根地址，不带 /chat/completions */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 模型名 */
    val model: String = DEFAULT_MODEL
) {

    /** 配没配 Key —— 界面上的「未配置 / 已配置」就看这个 */
    val configured: Boolean get() = apiKey.isNotBlank()

    /**
     * 打码后的 Key，给列表行显示用。
     *
     * 只留头 3 位和尾 4 位：足够用户确认「这是不是我以为的那把」，
     * 又不至于在别人瞄一眼屏幕时就泄露完整密钥。
     */
    val maskedKey: String
        get() = when {
            apiKey.isBlank() -> ""
            apiKey.length <= 8 -> "••••"
            else -> "${apiKey.take(3)}••••••${apiKey.takeLast(4)}"
        }

    companion object {
        /** DeepSeek 官方：国内直连、便宜、原生支持图片（截图录入用得上） */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** V4.1-Flash 的模型名，2026-09 起旧名 deepseek-chat 已逐步回收 */
        const val DEFAULT_MODEL = "deepseek-flash"

        /**
         * 收敛服务商预设：把各种写法的地址统一成「根地址」。
         *
         * 用户很容易直接粘 `https://api.deepseek.com/v1/chat/completions`，
         * 或者粘贴带空格的字符串 —— 这里替他们清掉，比报错让他们自己找问题友好。
         */
        fun sanitizeBaseUrl(raw: String): String {
            var url = raw.trim()
            if (url.isEmpty()) return DEFAULT_BASE_URL
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
            url = url.trimEnd('/')
            url = url.removeSuffix("/chat/completions")
            return url
        }

        fun sanitizeModel(raw: String): String = raw.trim().ifEmpty { DEFAULT_MODEL }
    }
}

/** 服务商预设：下拉里那一行，换一个就同时把地址和模型带过去 */
data class AiProvider(
    val name: String,
    val baseUrl: String,
    val model: String,
    val note: String
)

object AiProviders {

    val ALL: List<AiProvider> = listOf(
        AiProvider(
            name = "DeepSeek",
            baseUrl = AiConfig.DEFAULT_BASE_URL,
            model = AiConfig.DEFAULT_MODEL,
            note = "国内直连，按量计费，一次问答约一分钱"
        ),
        AiProvider(
            name = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            model = "qwen-plus",
            note = "新用户有免费额度，模型可选 qwen-plus / qwen-turbo"
        ),
        AiProvider(
            name = "自定义",
            baseUrl = AiConfig.DEFAULT_BASE_URL,
            model = AiConfig.DEFAULT_MODEL,
            note = "任何 OpenAI 兼容接口，地址填到 /v1 这一级"
        )
    )

    /** 按当前配置猜是哪个预设：地址和模型都对得上才算，否则算「自定义」 */
    fun matchOf(config: AiConfig): Int {
        val base = AiConfig.sanitizeBaseUrl(config.baseUrl)
        return ALL.indexOfFirst { it.baseUrl == base && it.model == config.model }
            .takeIf { it >= 0 } ?: (ALL.lastIndex)
    }
}
