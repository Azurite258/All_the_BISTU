package com.bistu.allthebistu.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bistu.allthebistu.data.reminder.ReminderPlanner
import com.bistu.allthebistu.ui.theme.UiStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 应用设置 */
data class AppSettings(
    /** 主题模式：0=跟随系统 1=浅色 2=深色 */
    val themeMode: Int = ThemeMode.SYSTEM,
    /** 界面风格，见 [com.bistu.allthebistu.ui.theme.UiStyle] */
    val uiStyle: Int = UiStyle.DEFAULT,
    /**
     * 用户为「某套界面风格」手动选过的课程配色：风格 → 方案。
     *
     * 键是风格而不是全局，这样切到别的风格再切回来，
     * 用户在那套风格下挑的颜色还在，不会被配套默认值盖掉。
     */
    val courseSchemeOverrides: Map<Int, Int> = emptyMap(),
    /** 字体挡位：0=小 1=中 2=大。既决定课表一行几个字，也决定全局 fontScale */
    val fontLevel: Int = FontLevel.MEDIUM,
    /** 自建昵称。空字符串 = 没设，账号卡上退回显示「点击设置昵称」 */
    val nickname: String = "",
    /** 课程提醒总开关。关掉后闹钟会被全部撤销，不是「只是不弹」 */
    val reminderEnabled: Boolean = false,
    /** 上课前多少分钟提醒。0..240，5 分钟一档，收敛逻辑见 ReminderPlanner.sanitizeAdvance */
    val reminderAdvanceMinutes: Int = ReminderPlanner.DEFAULT_ADVANCE,
    /** 是否把整学期课表写进系统日历（由系统负责弹提醒，App 不用活着） */
    val calendarSyncEnabled: Boolean = false,
    /**
     * 壁纸蒙层强度 0..100：越大越遮、字越清楚；0 = 完全露出照片。
     * 只在设了壁纸时有意义，值本身始终保存，换壁纸时不用重设。
     */
    val wallpaperScrim: Int = WallpaperTone.DEFAULT,
    /**
     * 大模型密钥。**只存本机**，不进仓库、不进日志。
     *
     * 之所以不在 [AppSettings] 之外单独放一个加密存储：DataStore 存在应用私有目录，
     * 未 root 的设备上其他应用读不到；为了它引一个 androidx.security 依赖不划算。
     */
    val aiApiKey: String = "",
    /** 接口根地址（OpenAI 兼容），见 [com.bistu.allthebistu.data.ai.AiConfig] */
    val aiBaseUrl: String = com.bistu.allthebistu.data.ai.AiConfig.DEFAULT_BASE_URL,
    /** 模型名 */
    val aiModel: String = com.bistu.allthebistu.data.ai.AiConfig.DEFAULT_MODEL,
    /**
     * 用户的长期学习偏好，自由文本（如「晚上 10 点后不学」「周三有社团」）。
     *
     * 和「要学什么」区别开：那是**这一次**的内容，每次生成都要重填；
     * 这条是**长期**的，存下来以后每次自动带上，用户不用反复描述自己。
     */
    val aiStudyNote: String = ""
) {
    /**
     * 当前生效的课程配色。
     *
     * 优先级：用户为这套风格手动选过的 > 风格配套的默认值。
     * 换界面风格时课块默认跟着变，但用户改过的选择会按风格记住。
     */
    val colorScheme: Int
        get() = courseSchemeOverrides[uiStyle] ?: UiStyle.defaultCourseScheme(uiStyle)

    /** 有没有设过昵称 */
    val hasNickname: Boolean get() = nickname.isNotBlank()

    /** 有没有配过 AI 密钥 —— 学习页那张卡片的「未配置 / 已配置」就看这个 */
    val hasAiKey: Boolean get() = aiApiKey.isNotBlank()

    /** 当前这套 AI 配置打包成一个对象，省得三处调用各拼一次 */
    val aiConfig: com.bistu.allthebistu.data.ai.AiConfig
        get() = com.bistu.allthebistu.data.ai.AiConfig(
            apiKey = aiApiKey,
            baseUrl = aiBaseUrl,
            model = aiModel
        )
}

/**
 * 壁纸蒙层的取值规范。
 *
 * 为什么叫「蒙层强度」而不是「壁纸不透明度」：真正影响文字可读性的，
 * 是压在图上那层主题色的浓淡，而不是图本身多透明。调这个数就是调那层。
 */
object WallpaperTone {
    const val DEFAULT = 70
    const val MIN = 0
    const val MAX = 100

    fun sanitize(value: Int): Int = value.coerceIn(MIN, MAX)
}

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

object CourseColorScheme {
    /**
     * 跟随界面风格（默认）。
     *
     * 每套风格自带一组 HSL 参数（见 [com.bistu.allthebistu.ui.theme.UiStyle.courseTone]），
     * 课块据此实时计算，换风格课块就整套跟着变 —— 这是与方向稿一比一复刻的要求。
     * 下面三套是用户显式指定的覆盖值，选了就不再跟随风格。
     */
    const val FOLLOW_STYLE = -1

    /** 使用教务系统返回的原始颜色 */
    const val SCHOOL = 0
    /** 莫兰迪色系：低饱和灰调 */
    const val MORANDI = 1
    /** 多巴胺色系：高饱和亮色（参考小米 15 自选色） */
    const val DOPAMINE = 2
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/** 设置存储（DataStore） */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val FONT_LEVEL = intPreferencesKey("font_level")

        /** 界面风格（六套之一） */
        val UI_STYLE = intPreferencesKey("ui_style")

        /**
         * 0.17 之前的全局课程配色键。
         *
         * 只读不写，用于把老用户的选择迁移成「原版」风格下的覆盖值；
         * 不迁移的话，老用户升级后会突然发现课块颜色变了。
         */
        val LEGACY_COLOR_SCHEME = intPreferencesKey("color_scheme")

        /**
         * 某套风格下的课程配色覆盖值。
         *
         * 用「一风格一个键」而不是一个全局键：用户给 Linear 挑了高对比、
         * 给 iOS 挑了柔和，这两件事要能同时记住。
         */
        fun courseSchemeFor(style: Int) = intPreferencesKey("course_scheme_style_$style")

        /** 自建昵称（只存本机，不跟学校系统同步） */
        val NICKNAME = stringPreferencesKey("nickname")

        /** 课程提醒开关 */
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")

        /** 提前分钟数 */
        val REMINDER_ADVANCE = intPreferencesKey("reminder_advance_minutes")

        /** 系统日历同步开关 */
        val CALENDAR_SYNC = booleanPreferencesKey("calendar_sync_enabled")

        /** 生活页「电费说明」弹窗是否已经看过（只弹一次） */
        val ELECTRIC_NOTICE_SHOWN = booleanPreferencesKey("electric_notice_shown")
        val WALLPAPER_SCRIM = intPreferencesKey("wallpaper_scrim")

        /** 大模型密钥 / 接口地址 / 模型名 */
        val AI_API_KEY = stringPreferencesKey("ai_api_key")
        val AI_BASE_URL = stringPreferencesKey("ai_base_url")
        val AI_MODEL = stringPreferencesKey("ai_model")

        /** 用户的长期学习偏好（自由文本），每次生成计划都会拼进提示词 */
        val AI_STUDY_NOTE = stringPreferencesKey("ai_study_note")
    }

    /** 读取设置流 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
            uiStyle = UiStyle.sanitize(prefs[Keys.UI_STYLE] ?: UiStyle.DEFAULT),
            courseSchemeOverrides = readCourseOverrides(prefs),
            fontLevel = FontLevel.sanitize(prefs[Keys.FONT_LEVEL] ?: FontLevel.MEDIUM),
            nickname = prefs[Keys.NICKNAME].orEmpty(),
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: false,
            reminderAdvanceMinutes = ReminderPlanner.sanitizeAdvance(
                prefs[Keys.REMINDER_ADVANCE] ?: ReminderPlanner.DEFAULT_ADVANCE
            ),
            calendarSyncEnabled = prefs[Keys.CALENDAR_SYNC] ?: false,
            wallpaperScrim = WallpaperTone.sanitize(
                prefs[Keys.WALLPAPER_SCRIM] ?: WallpaperTone.DEFAULT
            ),
            aiApiKey = prefs[Keys.AI_API_KEY].orEmpty(),
            aiBaseUrl = prefs[Keys.AI_BASE_URL] ?: com.bistu.allthebistu.data.ai.AiConfig.DEFAULT_BASE_URL,
            aiModel = prefs[Keys.AI_MODEL] ?: com.bistu.allthebistu.data.ai.AiConfig.DEFAULT_MODEL,
            aiStudyNote = prefs[Keys.AI_STUDY_NOTE].orEmpty()
        )
    }

    /** 设置主题模式 */
    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    /**
     * 换界面风格。
     *
     * 只写风格本身，**不动课程配色**：读取时 [AppSettings.colorScheme]
     * 会按「用户为这套风格选过的 > 这套风格的配套默认」回退。
     * 所以换风格时课块会自动变成配套色，而用户改过的选择按风格留着。
     */
    suspend fun setUiStyle(style: Int) {
        context.dataStore.edit { it[Keys.UI_STYLE] = UiStyle.sanitize(style) }
    }

    /**
     * 给「当前这套界面风格」设置课程配色。
     *
     * 写的是当前风格的覆盖键，不是全局键——否则用户给 Linear 挑的颜色
     * 会连原版一起改掉。
     */
    suspend fun setColorScheme(scheme: Int) {
        context.dataStore.edit { prefs ->
            val style = UiStyle.sanitize(prefs[Keys.UI_STYLE] ?: UiStyle.DEFAULT)
            prefs[Keys.courseSchemeFor(style)] = scheme
        }
    }

    /** 读出各风格的课程配色覆盖值，顺带把 0.16 的全局键迁成「原版」的选择 */
    private fun readCourseOverrides(prefs: Preferences): Map<Int, Int> {
        val map = mutableMapOf<Int, Int>()
        UiStyle.all.forEach { style ->
            prefs[Keys.courseSchemeFor(style)]?.let { map[style] = it }
        }
        val legacy = prefs[Keys.LEGACY_COLOR_SCHEME]
        // 0 = 学校配色，是 0.16 之前的**默认值**，区分不出「用户选过」还是「从没动过」。
        // 当成没选过，让老用户升级后也能拿到与方向稿一致的跟随风格配色。
        if (legacy != null && legacy != CourseColorScheme.SCHOOL && !map.containsKey(UiStyle.ORIGINAL)) {
            map[UiStyle.ORIGINAL] = legacy
        }
        return map
    }

    /** 设置字体挡位（课表排版 + 全局字体一起变） */
    suspend fun setFontLevel(level: Int) {
        context.dataStore.edit { it[Keys.FONT_LEVEL] = FontLevel.sanitize(level) }
    }

    /** 开关课程提醒 */
    suspend fun setReminderEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.REMINDER_ENABLED] = enabled }
    }

    /** 设置提前分钟数（会被收敛到 0..240 / 5 的倍数） */
    suspend fun setReminderAdvanceMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.REMINDER_ADVANCE] = ReminderPlanner.sanitizeAdvance(minutes)
        }
    }

    /** 开关系统日历同步 */
    suspend fun setCalendarSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CALENDAR_SYNC] = enabled }
    }

    /** 设置壁纸蒙层强度（会被收敛到 0..100） */
    suspend fun setWallpaperScrim(value: Int) {
        context.dataStore.edit { it[Keys.WALLPAPER_SCRIM] = WallpaperTone.sanitize(value) }
    }

    /**
     * 设置昵称。传空白等于「不设置昵称」，键会被移除而不是写空串，
     * 这样 [AppSettings.hasNickname] 的判断和存储状态永远一致。
     */
    suspend fun setNickname(nickname: String) {
        val trimmed = nickname.trim().take(NICKNAME_MAX_LENGTH)
        context.dataStore.edit {
            if (trimmed.isEmpty()) it.remove(Keys.NICKNAME) else it[Keys.NICKNAME] = trimmed
        }
    }

    /** 清掉昵称（「不设置昵称」用这个） */
    suspend fun clearNickname() {
        context.dataStore.edit { it.remove(Keys.NICKNAME) }
    }

    /**
     * 保存 AI 配置。
     *
     * 地址和模型都走一遍收敛（[com.bistu.allthebistu.data.ai.AiConfig.sanitizeBaseUrl]）：
     * 用户粘 `.../v1/chat/completions` 这种完整路径是常态，替他裁掉比报错友好。
     * Key 传空等于「清除」，键会被移除，[AppSettings.hasAiKey] 立刻变 false。
     */
    suspend fun setAiConfig(apiKey: String, baseUrl: String, model: String) {
        val key = apiKey.trim()
        val url = com.bistu.allthebistu.data.ai.AiConfig.sanitizeBaseUrl(baseUrl)
        val name = com.bistu.allthebistu.data.ai.AiConfig.sanitizeModel(model)
        context.dataStore.edit {
            if (key.isEmpty()) it.remove(Keys.AI_API_KEY) else it[Keys.AI_API_KEY] = key
            it[Keys.AI_BASE_URL] = url
            it[Keys.AI_MODEL] = name
        }
    }

    /** 只清 Key，地址和模型保留（换供应商时先清掉旧的，不用重填地址） */
    suspend fun clearAiKey() {
        context.dataStore.edit { it.remove(Keys.AI_API_KEY) }
    }

    /**
     * 保存用户的长期学习偏好。
     *
     * 会 trim + 截到 200 字：这段文本每次生成都要塞进提示词，太长会把 token 预算吃光，
     * 而且输入框本身也限制了 200 字，这里再兜一道，防止旧版本写进来的超长值继续传下去。
     * 传空等于清除。
     */
    suspend fun setAiStudyNote(note: String) {
        val text = note.trim().take(200)
        context.dataStore.edit {
            if (text.isEmpty()) it.remove(Keys.AI_STUDY_NOTE) else it[Keys.AI_STUDY_NOTE] = text
        }
    }

    /**
     * 是否还需要弹「电费说明」。
     *
     * 刻意不走 [settings] 那个 Flow：DataStore 是异步读的，Flow 首帧一定是默认值，
     * 会让这个判断在每次冷启动都成立（等于每次进生活页都弹）。这里直接用
     * `first()` 等真实值落盘后再判断。
     */
    suspend fun shouldShowElectricNotice(): Boolean =
        context.dataStore.data.first()[Keys.ELECTRIC_NOTICE_SHOWN] != true

    /** 标记「电费说明」已经看过 */
    suspend fun markElectricNoticeShown() {
        context.dataStore.edit { it[Keys.ELECTRIC_NOTICE_SHOWN] = true }
    }

    companion object {
        /**
         * 昵称长度上限。
         *
         * 不是随手取的值：账号卡那一行要放「昵称 · 学号 · 校区」，
         * 8 个汉字刚好不换行，再长就会被 ellipsis 吃掉后半截。
         */
        const val NICKNAME_MAX_LENGTH = 8
    }
}
