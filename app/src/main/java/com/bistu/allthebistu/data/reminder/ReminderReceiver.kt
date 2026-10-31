package com.bistu.allthebistu.data.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bistu.allthebistu.data.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 闹钟响了。
 *
 * 这里只做两件事：发通知、把下一批闹钟排上。
 * 第二件事其实是整个滚动窗口机制的核心 —— 忘了它，一周之后就再也不会有提醒，
 * 而且完全没有任何报错，属于「静默失效」里最难查的一类。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // 日程提醒和上课提醒共用一个 Receiver，靠 action 分流：
        // 两个功能的通知逻辑太像了，再写一个 Receiver 只会多一份要维护的 goAsync
        if (intent.action == EventReminderScheduler.ACTION_EVENT_REMINDER) {
            onEventAlarm(context, intent)
            return
        }
        if (intent.action != CourseReminderScheduler.ACTION_COURSE_REMINDER) return

        val plan = intent.toPlan() ?: run {
            AppLog.w(TAG, "闹钟广播缺少课程信息，忽略")
            return
        }
        val advance = intent.getIntExtra(
            CourseReminderScheduler.EXTRA_ADVANCE,
            ReminderPlanner.DEFAULT_ADVANCE
        )

        ReminderNotifier.show(context, plan, advance)
        AppLog.i(TAG, "提醒触发：${plan.courseName} @ ${plan.room}")

        // 广播接收器默认活不到协程跑完，必须 goAsync 把生命周期拖住（上限 10 秒，
        // 读一次 Room + 排十几个闹钟远远够用）
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CourseReminderScheduler.reschedule(app)
            } catch (t: Throwable) {
                AppLog.w(TAG, "响铃后重排失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * 日程闹钟：发通知，然后把未来两周的日程重排一遍。
     *
     * 重排同样不能省：日程是滚动窗口排的，响过一次之后要补上后面的重复场次。
     */
    private fun onEventAlarm(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EventReminderScheduler.EXTRA_TITLE).orEmpty()
        val start = intent.getLongExtra(EventReminderScheduler.EXTRA_START, 0L)
        if (start <= 0L) {
            AppLog.w(TAG, "日程闹钟缺少时间，忽略")
            return
        }
        val advance = intent.getIntExtra(
            EventReminderScheduler.EXTRA_ADVANCE,
            ReminderPlanner.DEFAULT_ADVANCE
        )
        ReminderNotifier.showEvent(
            context = context,
            title = title,
            startAt = start,
            advanceMinutes = advance,
            note = intent.getStringExtra(EventReminderScheduler.EXTRA_NOTE).orEmpty(),
            // 通知 id 用开始时刻的低位：同一条日程的不同场次不会互相覆盖
            notificationId = (start / 60_000L).toInt()
        )
        AppLog.i(TAG, "日程提醒触发：$title")

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                EventReminderScheduler.reschedule(app)
            } catch (t: Throwable) {
                AppLog.w(TAG, "日程响铃后重排失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private fun Intent.toPlan(): ReminderPlan? {
        val name = getStringExtra(CourseReminderScheduler.EXTRA_NAME) ?: return null
        val start = getLongExtra(CourseReminderScheduler.EXTRA_START, 0L)
        if (start <= 0L) return null

        return ReminderPlan(
            courseName = name,
            room = getStringExtra(CourseReminderScheduler.EXTRA_ROOM).orEmpty(),
            teacher = getStringExtra(CourseReminderScheduler.EXTRA_TEACHER).orEmpty(),
            weekNumber = getIntExtra(CourseReminderScheduler.EXTRA_WEEK, 0),
            startAt = start,
            endAt = getLongExtra(CourseReminderScheduler.EXTRA_END, start),
            triggerAt = getLongExtra(CourseReminderScheduler.EXTRA_TRIGGER, start),
            courseKey = getStringExtra(CourseReminderScheduler.EXTRA_KEY).orEmpty()
        )
    }

    private companion object {
        const val TAG = "ReminderReceiver"
    }
}
