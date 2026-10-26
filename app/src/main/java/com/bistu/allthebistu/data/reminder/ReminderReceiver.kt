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
