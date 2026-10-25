package com.bistu.allthebistu

import android.app.Application
import com.bistu.allthebistu.data.log.AppLog

/**
 * 应用入口。
 *
 * 现在只干一件事：把日志落盘能力装起来。
 * 必须在这里装而不是在 Activity 里 —— 登录页、WebView 页都是独立入口，
 * 掉登录往往发生在还没进 MainActivity 的冷启动阶段。
 */
class BistuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)
    }
}
