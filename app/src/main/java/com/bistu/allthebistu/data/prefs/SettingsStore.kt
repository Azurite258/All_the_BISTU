package com.bistu.allthebistu.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 应用设置 */
data class AppSettings(
    /** 主题模式：0=跟随系统 1=浅色 2=深色 */
    val themeMode: Int = ThemeMode.SYSTEM,
    /** 课程配色方案：0=学校配色 1=柔和 2=高对比 */
    val colorScheme: Int = CourseColorScheme.SCHOOL,
    /** 课表字体挡位：0=小(5字/行) 1=中(4字/行) 2=大(3字/行) */
    val fontLevel: Int = FontLevel.MEDIUM
)

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
    }

    /** 读取设置流 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
            colorScheme = prefs[Keys.COLOR_SCHEME] ?: CourseColorScheme.SCHOOL,
            fontLevel = FontLevel.sanitize(prefs[Keys.FONT_LEVEL] ?: FontLevel.MEDIUM)
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

    /** 设置课表字体挡位 */
    suspend fun setFontLevel(level: Int) {
        context.dataStore.edit { it[Keys.FONT_LEVEL] = FontLevel.sanitize(level) }
    }
}
