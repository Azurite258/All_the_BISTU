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
 * 系统层面的「世界变了」广播。
 *
 * 不接这个，用一次重启就能让整个提醒功能永久哑掉 ——
 * AlarmManager 里的闹钟**不跨进程死亡存活**，重启后必须全部重排。
 * 另外换时区、用户手动改时间也会让已排的 epoch 时刻错位，同样要重排。
 */
class ReminderBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED_ACTIONS) return

        AppLog.i(TAG, "收到 $action，重排课程提醒")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                CourseReminderScheduler.reschedule(app)
            } catch (t: Throwable) {
                AppLog.w(TAG, "重排失败: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderBoot"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED
        )
    }
}
