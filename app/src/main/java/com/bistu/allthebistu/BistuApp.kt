package com.bistu.allthebistu

import android.app.Application
import com.bistu.allthebistu.data.event.EventStore
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.patch.CoursePatchStore
import com.bistu.allthebistu.data.privacy.UserDataWipers
import com.bistu.allthebistu.data.reminder.CourseReminderScheduler
import com.bistu.allthebistu.data.reminder.EventReminderScheduler
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
        // 自定义日程是另一条管线，同样在冷启动补排一次
        EventReminderScheduler.rescheduleAsync(this)

        // 课程的备注 / 隐藏 / 编辑 / 复制都存本机，属于个人数据：
        // 退出登录时跟着一起清掉，否则换个人登录会看到上一个人的备注
        UserDataWipers.register("课程改动") { CoursePatchStore.wipe(this) }

        // 自定义日程同理：是用户自己写的东西，退出登录要一起清掉
        UserDataWipers.register("自定义日程") { EventStore.wipe(this) }
    }
}
