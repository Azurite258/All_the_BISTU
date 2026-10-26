package com.bistu.allthebistu.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bistu.allthebistu.data.ScheduleRepository
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.prefs.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 课程提醒的闹钟调度。
 *
 * ── 为什么用 AlarmManager 而不是只靠 WorkManager ──
 * WorkManager 的定时精度是「大约」，Doze 下偏十几分钟很正常。
 * 上课提醒迟到 10 分钟等于没提醒，所以主通道必须是 AlarmManager 的精确闹钟，
 * WorkManager 只当**兜底**（每天跑一次，检查闹钟还在不在，不在就重排）。
 *
 * ── 为什么只排一周 ──
 * 见 [ReminderPlanner] 的说明：整学期两百多个闹钟，任何一次重启 / 时区变化
 * 都会让剩下的全部失效，而 App 可能一个月不打开。改成滚动窗口后，
 * 靠「每次响铃后重排 + 每天兜底 + 开机重排」三条线续命。
 *
 * ── 重排是幂等的 ──
 * 每次都是「先全部撤销，再按当前设置重排」，所以设置改一次、课表刷新一次、
 * 开机一次，结果都收敛到同一份闹钟集合，不会越排越多。
 */
object CourseReminderScheduler {

    private const val TAG = "CourseReminder"

    /** 闹钟广播的 action，Receiver 靠它过滤掉别的广播 */
    const val ACTION_COURSE_REMINDER = "com.bistu.allthebistu.action.COURSE_REMINDER"

    const val EXTRA_NAME = "extra_course_name"
    const val EXTRA_ROOM = "extra_course_room"
    const val EXTRA_TEACHER = "extra_course_teacher"
    const val EXTRA_WEEK = "extra_course_week"
    const val EXTRA_START = "extra_course_start"
    const val EXTRA_END = "extra_course_end"
    const val EXTRA_TRIGGER = "extra_course_trigger"
    const val EXTRA_KEY = "extra_course_key"
    const val EXTRA_ADVANCE = "extra_advance_minutes"

    /** 存「已经排了哪些 requestCode」，撤销时要用；不能用数据库，重排要先于读表 */
    private const val PREFS = "reminder_alarms"
    private const val KEY_CODES = "codes"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 三个触发源（响铃 / 开机 / 设置变更）可能同时重排，串行化避免互相踩掉 */
    private val mutex = Mutex()

    /**
     * 后台重排，不阻塞调用方。
     *
     * Application.onCreate、设置项回调、广播接收器都走这个入口 ——
     * 它们全在主线程，读库 + 排闹钟绝不能同步做。
     */
    fun rescheduleAsync(context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { reschedule(app) }
                .onFailure {
                    AppLog.w(TAG, "重排失败: ${it.javaClass.simpleName}: ${it.message}")
                }
        }
    }

    /**
     * 按当前设置与本地课表重排全部闹钟。幂等。
     */
    suspend fun reschedule(context: Context) = mutex.withLock {
        val app = context.applicationContext
        val alarmManager = app.getSystemService(AlarmManager::class.java)
        if (alarmManager == null) {
            AppLog.w(TAG, "取不到 AlarmManager，跳过本次重排")
            return@withLock
        }

        // ① 先无条件撤销上一批。
        // 开关关掉、课表更新、提前量改了 —— 三种情况都靠这一步收敛到新状态。
        val previous = loadCodes(app)
        previous.forEach { code ->
            pendingIntentOf(app, code, create = false)?.let {
                alarmManager.cancel(it)
                it.cancel()
            }
        }
        saveCodes(app, emptyList())

        // ② 读当前设置。用 first() 而不是 collect：这里要的是「此刻的真值」
        val settings = SettingsStore(app).settings.first()
        if (!settings.reminderEnabled) {
            if (previous.isNotEmpty()) {
                AppLog.i(TAG, "提醒已关闭，撤销了 ${previous.size} 个闹钟")
            }
            return@withLock
        }

        // ③ 课表只读本地缓存。示例数据（未登录时 assets 里那份）不会进数据库，
        //    所以不会给假课表排提醒。
        val data = ScheduleRepository(app).loadCached()?.data
        if (data == null) {
            AppLog.w(TAG, "没有本地课表，本次不排提醒（登录并同步一次后自动生效）")
            return@withLock
        }
        if (data.termWeeks.isEmpty()) {
            // 没有周历就算不出「第 N 周周几」是哪天，硬猜会提醒错日子
            AppLog.w(TAG, "课表缺教学周历，算不出上课日期，本次不排提醒")
            return@withLock
        }

        val plans = ReminderPlanner.plan(
            data = data,
            advanceMinutes = settings.reminderAdvanceMinutes,
            fromMs = System.currentTimeMillis()
        )
        if (plans.isEmpty()) {
            AppLog.d(TAG, "未来 ${ReminderPlanner.WINDOW_DAYS} 天内没有需要提醒的课")
            return@withLock
        }

        val exact = canScheduleExact(alarmManager)
        val codes = ArrayList<Int>(plans.size)
        plans.forEach { plan ->
            val pi = pendingIntentForPlan(app, plan, settings.reminderAdvanceMinutes)
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
            } else {
                // 精确闹钟被系统禁掉时的降级：至少别丢，晚几分钟也胜过没有
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pi)
            }
            codes += plan.requestCode
        }
        saveCodes(app, codes)

        val format = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        AppLog.i(
            TAG,
            "已排 ${plans.size} 个提醒（精确=$exact，提前 ${settings.reminderAdvanceMinutes} 分钟）：" +
                plans.joinToString("；") {
                    "${it.courseName} ${format.format(Date(it.triggerAt))}"
                }
        )
    }

    /** 撤销全部闹钟（关提醒、退出登录、清数据时用） */
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
        if (codes.isNotEmpty()) AppLog.i(TAG, "已撤销全部 ${codes.size} 个提醒闹钟")
    }

    // ───────────────────────────────────────────────────────────
    // 内部
    // ───────────────────────────────────────────────────────────

    /**
     * 精确闹钟能不能用。
     *
     * 33+ 声明了 USE_EXACT_ALARM（课表/日程类应用的正当用途）后这里恒为 true；
     * 万一某个 ROM 仍把它关掉，[canScheduleExactAlarms] 会返回 false，
     * 上层自动降级成不精确闹钟，而不是抛异常。
     */
    private fun canScheduleExact(alarmManager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { alarmManager.canScheduleExactAlarms() }.getOrDefault(false)
        } else {
            true
        }

    /**
     * 按 requestCode 取 PendingIntent。
     *
     * 注意 FLAG_NO_CREATE：撤销时不能用 FLAG_UPDATE_CURRENT，
     * 那会**顺手建出一个**永远不响的 PendingIntent，等于把垃圾留在系统里。
     */
    private fun pendingIntentOf(context: Context, code: Int, create: Boolean): PendingIntent? {
        val flags = if (create) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, code, baseIntent(context), flags)
    }

    private fun pendingIntentForPlan(
        context: Context,
        plan: ReminderPlan,
        advanceMinutes: Int
    ): PendingIntent {
        val intent = baseIntent(context).apply {
            putExtra(EXTRA_NAME, plan.courseName)
            putExtra(EXTRA_ROOM, plan.room)
            putExtra(EXTRA_TEACHER, plan.teacher)
            putExtra(EXTRA_WEEK, plan.weekNumber)
            putExtra(EXTRA_START, plan.startAt)
            putExtra(EXTRA_END, plan.endAt)
            putExtra(EXTRA_TRIGGER, plan.triggerAt)
            putExtra(EXTRA_KEY, plan.courseKey)
            putExtra(EXTRA_ADVANCE, advanceMinutes)
        }
        return PendingIntent.getBroadcast(
            context,
            plan.requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        ) ?: error("PendingIntent 创建失败")
    }

    /** 所有闹钟共用一个 action；extras 不参与 PendingIntent 的相等判断，所以能靠它过滤 */
    private fun baseIntent(context: Context): Intent =
        Intent(context, ReminderReceiver::class.java).setAction(ACTION_COURSE_REMINDER)

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
