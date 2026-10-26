package com.bistu.allthebistu

import android.app.Application
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.reminder.CourseReminderScheduler
import com.bistu.allthebistu.data.reminder.ReminderNotifier
import com.bistu.allthebistu.data.reminder.ReminderWork

/**
 * 应用入口。
 *
 * 干三件事：
 *  1. 装日志落盘（登录页、WebView 页都是独立入口，掉登录往往发生在还没进 MainActivity 时）
 *  2. 建通知渠道（渠道建得晚，第一条通知会来不及弹）
 *  3. 把课表提醒的闹钟补一遍 —— 冷启动是最可靠的重排时机
 */
class BistuApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.install(this)

        // 渠道必须提前建好：Android 8 之后没有渠道的通知会被直接丢弃，
        // 而第一条提醒可能就是冷启动后几分钟内的那节课
        ReminderNotifier.ensureChannel(this)

        // 每日兜底任务：注册是幂等的，重复调用不会叠出多个
        ReminderWork.ensureScheduled(this)

        // 冷启动补排一次闹钟。放在后台线程，不拖慢启动；
        // 关闭提醒时这里会顺手把残留闹钟撤销掉
        CourseReminderScheduler.rescheduleAsync(this)
    }
}
