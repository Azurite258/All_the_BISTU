package com.bistu.allthebistu.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 应用设置 */
data class AppSettings(
    /** 主题模式：0=跟随系统 1=浅色 2=深色 */
    val themeMode: Int = ThemeMode.SYSTEM,
    /** 课程配色方案：0=学校配色 1=柔和 2=高对比 */
    val colorScheme: Int = CourseColorScheme.SCHOOL,
    /** 字体挡位：0=小 1=中 2=大。既决定课表一行几个字，也决定全局 fontScale */
    val fontLevel: Int = FontLevel.MEDIUM,
    /** 自建昵称。空字符串 = 没设，账号卡上退回显示「点击设置昵称」 */
    val nickname: String = ""
) {
    /** 有没有设过昵称 */
    val hasNickname: Boolean get() = nickname.isNotBlank()
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
        val COLOR_SCHEME = intPreferencesKey("color_scheme")
        val FONT_LEVEL = intPreferencesKey("font_level")

        /** 自建昵称（只存本机，不跟学校系统同步） */
        val NICKNAME = stringPreferencesKey("nickname")

        /** 生活页「电费说明」弹窗是否已经看过（只弹一次） */
        val ELECTRIC_NOTICE_SHOWN = booleanPreferencesKey("electric_notice_shown")
    }

    /** 读取设置流 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
            colorScheme = prefs[Keys.COLOR_SCHEME] ?: CourseColorScheme.SCHOOL,
            fontLevel = FontLevel.sanitize(prefs[Keys.FONT_LEVEL] ?: FontLevel.MEDIUM),
            nickname = prefs[Keys.NICKNAME].orEmpty()
        )
    }

    /** 设置主题模式 */
    suspend fun setThemeMode(mode: Int) {
        context.dataStore.edit { it[Keys.THEME_MODE] = mode }
    }

    /** 设置配色方案 */
    suspend fun setColorScheme(scheme: Int) {
        context.dataStore.edit { it[Keys.COLOR_SCHEME] = scheme }
    }

    /** 设置字体挡位（课表排版 + 全局字体一起变） */
    suspend fun setFontLevel(level: Int) {
        context.dataStore.edit { it[Keys.FONT_LEVEL] = FontLevel.sanitize(level) }
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
