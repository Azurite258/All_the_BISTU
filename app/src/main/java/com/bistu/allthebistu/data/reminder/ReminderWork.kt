package com.bistu.allthebistu.data.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bistu.allthebistu.data.log.AppLog
import java.util.concurrent.TimeUnit

/**
 * 每日兜底重排。
 *
 * AlarmManager 的闹钟理论上由「响铃后重排」续着，但下面几种情况会把它断掉：
 *  - 系统清理后台 / 某 ROM 的省电策略把已排闹钟抹了
 *  - 用户手动「强行停止」App（会把 alarms 一起清掉）
 *  - 补丁升级、清缓存这类系统行为
 *
 * 断了以后不会报错，只是默默不再提醒，所以必须有一条**自己会回来**的线。
 * WorkManager 的时间不精确（Doze 下可能晚几小时），但每天总会跑一次，
 * 跑一次就把未来一周的闹钟全部补齐 —— 精度由 AlarmManager 保证，它只负责「别断」。
 */
class ReminderRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            CourseReminderScheduler.reschedule(applicationContext)
            Result.success()
        } catch (t: Throwable) {
            AppLog.w(TAG, "兜底重排失败: ${t.javaClass.simpleName}: ${t.message}")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "ReminderWorker"
    }
}

object ReminderWork {

    private const val WORK_NAME = "course_reminder_daily"

    /**
     * 注册每日兜底任务。幂等，重复调用不会叠出多个任务。
     *
     * 用 UPDATE 而不是 KEEP：以后要调周期或加约束时，
     * 已经在用户机器上跑着的旧任务会跟着升级，不用等它自然结束。
     */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderRefreshWorker>(1, TimeUnit.DAYS)
            // 首次延后一小时：冷启动瞬间不做网络/数据库以外的重活，
            // 而且 Application 里已经有一次即时重排了
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()

        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }.onFailure {
            AppLog.w("ReminderWork", "注册每日兜底任务失败: ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
