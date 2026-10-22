package com.example.study1.data.prefs

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
    val colorScheme: Int = CourseColorScheme.SCHOOL
)

object ThemeMode {
    const val SYSTEM = 0
    const val LIGHT = 1
    const val DARK = 2
}

object CourseColorScheme {
    /** 使用教务系统返回的原始颜色 */
    const val SCHOOL = 0
    /** 柔和马卡龙色 */
    const val SOFT = 1
    /** 高对比度（适合深色模式） */
    const val VIVID = 2
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

/** 设置存储（DataStore） */
class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME_MODE = intPreferencesKey("theme_mode")
        val COLOR_SCHEME = intPreferencesKey("color_scheme")
        /** 是否已经展示过「未登录」引导提示（只提示一次） */
        val LOGIN_PROMPT_SHOWN = booleanPreferencesKey("login_prompt_shown")
    }

    /** 读取设置流 */
    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME_MODE] ?: ThemeMode.SYSTEM,
            colorScheme = prefs[Keys.COLOR_SCHEME] ?: CourseColorScheme.SCHOOL
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

    /** 是否已经展示过「未登录」引导提示 */
    suspend fun hasShownLoginPrompt(): Boolean =
        context.dataStore.data.map { it[Keys.LOGIN_PROMPT_SHOWN] ?: false }
            .first()

    /** 标记「未登录」引导提示已展示过（之后不再提示） */
    suspend fun markLoginPromptShown() {
        context.dataStore.edit { it[Keys.LOGIN_PROMPT_SHOWN] = true }
    }
}
