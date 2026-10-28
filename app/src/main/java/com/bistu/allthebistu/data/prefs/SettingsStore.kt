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
    /** 底部导航风格：[BottomBarStyle.FLAT] / [BottomBarStyle.GLASS] */
    val bottomBarStyle: Int = BottomBarStyle.DEFAULT,
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
    val wallpaperScrim: Int = WallpaperTone.DEFAULT
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

        /** 底部导航风格（扁平 / 光效） */
        val BOTTOM_BAR_STYLE = intPreferencesKey("bottom_bar_style")

        /** 课程提醒开关 */
        val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")

        /** 提前分钟数 */
        val REMINDER_ADVANCE = intPreferencesKey("reminder_advance_minutes")

        /** 系统日历同步开关 */
        val CALENDAR_SYNC = booleanPreferencesKey("calendar_sync_enabled")

        /** 生活页「电费说明」弹窗是否已经看过（只弹一次） */
        val ELECTRIC_NOTICE_SHOWN = booleanPreferencesKey("electric_notice_shown")
        val WALLPAPER_SCRIM = intPreferencesKey("wallpaper_scrim")
    }

    /** 读取设置流 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
            uiStyle = UiStyle.sanitize(prefs[Keys.UI_STYLE] ?: UiStyle.DEFAULT),
            courseSchemeOverrides = readCourseOverrides(prefs),
            fontLevel = FontLevel.sanitize(prefs[Keys.FONT_LEVEL] ?: FontLevel.MEDIUM),
            nickname = prefs[Keys.NICKNAME].orEmpty(),
            bottomBarStyle = BottomBarStyle.sanitize(
                prefs[Keys.BOTTOM_BAR_STYLE] ?: BottomBarStyle.DEFAULT
            ),
            reminderEnabled = prefs[Keys.REMINDER_ENABLED] ?: false,
            reminderAdvanceMinutes = ReminderPlanner.sanitizeAdvance(
                prefs[Keys.REMINDER_ADVANCE] ?: ReminderPlanner.DEFAULT_ADVANCE
            ),
            calendarSyncEnabled = prefs[Keys.CALENDAR_SYNC] ?: false,
            wallpaperScrim = WallpaperTone.sanitize(
                prefs[Keys.WALLPAPER_SCRIM] ?: WallpaperTone.DEFAULT
            )
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
        if (legacy != null && !map.containsKey(UiStyle.ORIGINAL)) {
            map[UiStyle.ORIGINAL] = legacy
        }
        return map
    }

    /** 设置字体挡位（课表排版 + 全局字体一起变） */
    suspend fun setFontLevel(level: Int) {
        context.dataStore.edit { it[Keys.FONT_LEVEL] = FontLevel.sanitize(level) }
    }

    /** 设置底部导航风格 */
    suspend fun setBottomBarStyle(style: Int) {
        context.dataStore.edit { it[Keys.BOTTOM_BAR_STYLE] = BottomBarStyle.sanitize(style) }
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
