package com.bistu.allthebistu.data.reminder

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.bistu.allthebistu.data.ScheduleRepository
import com.bistu.allthebistu.data.log.AppLog
import com.bistu.allthebistu.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * 把整学期课表写进系统日历 —— 也就是「完全无感」的那条路。
 *
 * 和 App 自己的通知相比，这条路的差别是**责任方变了**：
 * 提醒由系统日历 App 负责弹出，所以 App 可以不被唤醒、不被保活、甚至可以被强行停止，
 * 到点照样响。代价是多要一个日历权限。
 *
 * ── 为什么必须建一份「专属日历」──
 * 直接写进用户主日历（尤其是 Google 账户）有两个后果：
 *  1. 课程事件会被同步到云端，用户换设备能看到，但也会污染他的日历；
 *  2. 重排时要「先删后写」，按时间范围删就可能误伤用户自己建的日程。
 * 所以这里固定用一个本地账户（ACCOUNT_TYPE_LOCAL）下的专属日历，
 * 清理时只按 calendar_id 删自己的东西，绝不可能碰到别人的日程。
 * 本地账户不会同步上云。
 *
 * 建不出来（个别 ROM 收紧）就**放弃同步**，不退回写主日历 ——
 * 宁可少一个功能，也不能替用户往他的日历里塞东西。
 */
object CourseCalendarSync {

    private const val TAG = "CalendarSync"

    /** 专属日历的账户名。用包名做前缀，避免和其他 App 撞 */
    private const val ACCOUNT_NAME = "com.bistu.allthebistu.course"

    private const val DISPLAY_NAME = "课表"

    private const val PREFS = "course_calendar"
    private const val KEY_CALENDAR_ID = "calendar_id"

    /** 每批提交的操作数。CalendarProvider 一次塞几百条 insert 容易超 Binder 限制 */
    private const val BATCH_SIZE = 40

    data class Result(
        val ok: Boolean,
        val eventCount: Int,
        val reason: String = ""
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 设置里开着同步就同步一次，否则什么都不做。
     *
     * 课表刷新之后调用它，日历就能跟着更新 —— 不然用户改了课、看了新周次，
     * 日历里还是开学时那份，比不同步更糟。
     */
    suspend fun syncIfEnabled(context: Context): Result {
        val settings = SettingsStore(context).settings.first()
        if (!settings.calendarSyncEnabled) return Result(false, 0, "同步未开启")
        return sync(context, settings.reminderAdvanceMinutes)
    }

    /**
     * 按当前缓存的课表重建整学期日程。
     *
     * 幂等：先清空专属日历里的全部事件，再按最新课表写一遍。
     */
    suspend fun sync(context: Context, advanceMinutes: Int): Result = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) {
            return@withContext Result(false, 0, "没有日历权限")
        }

        val data = ScheduleRepository(context).loadCached()?.data
            ?: return@withContext Result(false, 0, "本地没有课表")
        if (data.termWeeks.isEmpty()) {
            return@withContext Result(false, 0, "课表缺教学周历，算不出日期")
        }

        val calendarId = ensureCalendar(context)
            ?: return@withContext Result(false, 0, "系统不允许创建本地日历")

        val plans = ReminderPlanner.planWholeTerm(
            data = data,
            advanceMinutes = advanceMinutes,
            nowMs = System.currentTimeMillis()
        )

        val resolver = context.contentResolver
        // 清空自己那份日历：只按 calendar_id 删，用户的日程一条都碰不到
        val cleared = runCatching {
            resolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString())
            )
        }.getOrElse {
            AppLog.w(TAG, "清空旧日程失败: ${it.javaClass.simpleName}: ${it.message}")
            return@withContext Result(false, 0, "清空旧日程失败")
        }
        AppLog.d(TAG, "清掉旧日程 $cleared 条，准备写入 ${plans.size} 条")

        if (plans.isEmpty()) {
            return@withContext Result(true, 0, "没有需要写入的课")
        }

        val timezone = TimeZone.getDefault().id
        var written = 0

        plans.chunked(BATCH_SIZE).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>(chunk.size * 2)
            chunk.forEach { plan ->
                val event = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                    put(CalendarContract.Events.TITLE, plan.courseName)
                    put(
                        CalendarContract.Events.DESCRIPTION,
                        buildString {
                            append("第 ${plan.weekNumber} 周")
                            if (plan.teacher.isNotBlank()) append(" · ${plan.teacher}")
                        }
                    )
                    put(CalendarContract.Events.EVENT_LOCATION, plan.room)
                    put(CalendarContract.Events.DTSTART, plan.startAt)
                    put(CalendarContract.Events.DTEND, plan.endAt)
                    put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
                    // 有闹钟标记，日历 App 才会去读 Reminders 表
                    put(CalendarContract.Events.HAS_ALARM, 1)
                    put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
                    // 标记来源，方便以后排查「这条到底是谁写的」
                    put(CalendarContract.Events.CUSTOM_APP_PACKAGE, context.packageName)
                }

                // 记住这条 Events insert 在本次 batch 里的下标，
                // 下一条 Reminders insert 要用 back reference 指回来拿 event_id
                val eventIndex = ops.size
                ops += ContentProviderOperation
                    .newInsert(CalendarContract.Events.CONTENT_URI)
                    .withValues(event)
                    .build()

                val reminder = ContentValues().apply {
                    put(CalendarContract.Reminders.MINUTES, advanceMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                ops += ContentProviderOperation
                    .newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValues(reminder)
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, eventIndex)
                    .build()
            }
            runCatching {
                resolver.applyBatch(CalendarContract.AUTHORITY, ops)
            }.onSuccess {
                written += chunk.size
            }.onFailure {
                AppLog.w(TAG, "写入日历失败: ${it.javaClass.simpleName}: ${it.message}")
                return@withContext Result(false, written, "写入系统中途失败")
            }
        }

        AppLog.i(TAG, "已写入系统日历：$written 条（提前 $advanceMinutes 分钟提醒）")
        Result(true, written, "")
    }

    /**
     * 清空本 App 写的全部日程（关掉同步时调用）。
     */
    suspend fun clear(context: Context): Int = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext 0
        val calendarId = findCalendarId(context) ?: return@withContext 0
        val deleted = runCatching {
            context.contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events.CALENDAR_ID} = ?",
                arrayOf(calendarId.toString())
            )
        }.getOrDefault(0)
        AppLog.i(TAG, "关闭同步，清掉 $deleted 条日程")
        deleted
    }

    // ───────────────────────────────────────────────────────────
    // 专属日历
    // ───────────────────────────────────────────────────────────

    private fun ensureCalendar(context: Context): Long? {
        findCalendarId(context)?.let { return it }
        return createCalendar(context)
    }

    private fun findCalendarId(context: Context): Long? {
        val resolver = context.contentResolver

        // 先看缓存里的 id 还在不在
        val cached = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_CALENDAR_ID, -1L)
        if (cached > 0 && calendarExists(context, cached)) return cached

        // 再看系统里有没有同名账户的日历（重装 App 后缓存丢了，但日历还在）
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.ACCOUNT_NAME} = ? AND " +
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?"
        val args = arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL)

        val found = runCatching {
            resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, args, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()

        if (found != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_CALENDAR_ID, found).apply()
        }
        return found
    }

    private fun calendarExists(context: Context, calendarId: Long): Boolean =
        runCatching {
            context.contentResolver.query(
                ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId),
                arrayOf(CalendarContract.Calendars._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)

    private fun createCalendar(context: Context): Long? {
        // 建日历必须走「同步适配器」通道，否则 CalendarProvider 会拒绝
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            .build()

        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, DISPLAY_NAME)
            put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF3B6EF5.toInt())
            put(
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_OWNER
            )
            put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            // 本地日历不参与云同步；置 1 是为了让日历 App 正常显示事件
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.VISIBLE, 1)
        }

        return runCatching {
            context.contentResolver.insert(uri, values)?.let { inserted ->
                val id = ContentUris.parseId(inserted)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(KEY_CALENDAR_ID, id).apply()
                AppLog.i(TAG, "已创建专属日历 id=$id")
                id
            }
        }.onFailure {
            AppLog.w(TAG, "创建本地日历失败: ${it.javaClass.simpleName}: ${it.message}")
        }.getOrNull()
    }
}
