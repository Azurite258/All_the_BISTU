package com.bistu.allthebistu.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bistu.allthebistu.data.event.EventStore
import com.bistu.allthebistu.data.event.UserEvent
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * 自定义日程的提醒闹钟。
 *
 * 和 [CourseReminderScheduler] 是**两条独立管线**，故意的：
 *  - 课程闹钟每次重排都是「先撤销自己那批、再按课表重排」，日程要是塞进同一个 code 池，
 *    课堂表一刷新就把日程的闹钟一起撤了；
 *  - 日程按真实日期展开（重复规则在 [UserEvent.occursOn] 里算），和「第几周第几节」无关。
 *
 * 所以这里自己存一份 requestCode 列表（另一个 SharedPreferences），互不干扰。
 * 触发时间同样是「开始时刻 − 提前量」，提前量跟着「课程提醒」里那个设置走 ——
 * 入口是同一个开关，用户不会愿意为日程再调一次。
 */
object EventReminderScheduler {

    const val ACTION_EVENT_REMINDER = "com.bistu.allthebistu.action.EVENT_REMINDER"

    const val EXTRA_TITLE = "extra_event_title"
    const val EXTRA_START = "extra_event_start"
    const val EXTRA_NOTE = "extra_event_note"
    const val EXTRA_TAG = "extra_event_tag"
    const val EXTRA_ADVANCE = "extra_event_advance"

    private const val TAG = "EventReminder"

    /** 只排未来两周：和课程提醒一样用滚动窗口，避免几百个闹钟一次性铺满 */
    private const val WINDOW_DAYS = 14

    /** requestCode 起点，避开课程提醒那批（课程用的是小数字） */
    private const val CODE_BASE = 2_000_000

    private const val PREFS = "event_reminder_alarms"
    private const val KEY_CODES = "codes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun rescheduleAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { reschedule(app) }
                .onFailure { AppLog.w(TAG, "重排失败: ${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /**
     * 按当前设置与本地日程重排全部闹钟。幂等（先撤销自己那批，再重排）。
     */
    suspend fun reschedule(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLog.w(TAG, "取不到 AlarmManager，跳过")
            return@withLock
        }

        // ① 先撤销上一批
        val previous = loadCodes(app)
        previous.forEach { code ->
            pendingIntentOf(app, code, create = false)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        saveCodes(app, emptyList())

        // ② 开关没开就到此为止：日程提醒挂在「课前提醒」同一个开关上
        val settings = SettingsStore(app).settings.first()
        if (!settings.reminderEnabled) return@withLock

        val events = EventStore(app).events.value
        if (events.isEmpty()) return@withLock

        val advanceMs = settings.reminderAdvanceMinutes.coerceAtLeast(0) * 60_000L
        val now = System.currentTimeMillis()
        val exact = canScheduleExact(alarmManager)

        val codes = ArrayList<Int>()
        var index = 0
        events.forEach { event ->
            occurrencesWithin(event, now, WINDOW_DAYS).forEach { startMillis ->
                val triggerAt = startMillis - advanceMs
                if (triggerAt <= now) return@forEach
                val code = CODE_BASE + index++
                val pi = pendingIntentFor(app, code, event, startMillis, settings.reminderAdvanceMinutes)
                if (exact) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
                codes += code
            }
        }
        saveCodes(app, codes)
        if (codes.isNotEmpty()) AppLog.i(TAG, "已排 ${codes.size} 个日程提醒（精确=$exact）")
    }

    /** 关提醒 / 退出登录时撤销 */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java) ?: return
        val codes = loadCodes(app)
        codes.forEach { code ->
            pendingIntentOf(app, code, create = false)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        saveCodes(app, emptyList())
    }

    // ───────────────────────────────────────────────────────────
    // 内部
    // ───────────────────────────────────────────────────────────

    /** 未来 [days] 天里，这条日程都会落在哪几个时刻（按重复规则展开） */
    private fun occurrencesWithin(event: UserEvent, now: Long, days: Int): List<Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val result = ArrayList<Long>()
        repeat(days + 1) { offset ->
            val day = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            if (event.occursOn(day.timeInMillis)) {
                result += UserEvent.withDateOf(event.startAt, day.timeInMillis)
            }
        }
        return result
    }

    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    private fun pendingIntentOf(context: Context, code: Int, create: Boolean): PendingIntent? {
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, code, baseIntent(context), flags)
    }

    private fun pendingIntentFor(
        context: Context,
        code: Int,
        event: UserEvent,
        startMillis: Long,
        advanceMinutes: Int
    ): PendingIntent {
        val intent = baseIntent(context).apply {
            putExtra(EXTRA_TITLE, event.title)
            putExtra(EXTRA_START, startMillis)
            putExtra(EXTRA_NOTE, event.note)
            putExtra(EXTRA_TAG, event.tag)
            putExtra(EXTRA_ADVANCE, advanceMinutes)
        }
        return PendingIntent.getBroadcast(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: error("PendingIntent 创建失败")
    }

    private fun baseIntent(context: Context): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_EVENT_REMINDER)

    private fun loadCodes(context: Context): List<Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CODES, "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    private fun saveCodes(context: Context, codes: List<Int>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CODES, codes.joinToString(","))
            .apply()
    }
}
