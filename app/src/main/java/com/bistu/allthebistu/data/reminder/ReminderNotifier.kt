package com.bistu.allthebistu.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bistu.allthebistu.MainActivity
import com.bistu.allthebistu.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上课提醒的通知。
 *
 * 和「系统日历同步」是两条独立的通道，不是替代关系：
 *  - 这里发的是 App 自己的通知，秒级准时、文案可控，但要求 App 被授权通知权限，
 *    并且闹钟得由我们自己的 AlarmManager 维护；
 *  - 系统日历那条走 CalendarContract，由系统日历 App 负责弹提醒，
 *    App 被杀掉也能响，代价是要多申请一个日历权限。
 *
 * 两条都开不冲突（会收到两次），所以设置页里写成「二选一或都开」，让用户自己决定。
 */
object ReminderNotifier {

    const val CHANNEL_ID = "course_reminder"

    private const val CHANNEL_NAME = "上课提醒"
    private const val CHANNEL_DESC = "上课前的提前提醒，时间由「我的 → 课程提醒」决定"

    /** 通知 id 用 requestCode：同一节课重排后仍然覆盖同一条，不会堆一串 */
    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            // HIGH：要弹出横幅 + 响铃。上课提醒的价值全在「打断你一下」，
            // 用 DEFAULT 的话通知会安静地躺在通知栏里，等看到时课已经上完了
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            setShowBadge(true)
            // 锁屏上显示课程名，不显示具体内容之外的隐私信息（本来也没隐私）
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 发一条上课提醒。
     *
     * 没有通知权限时 [NotificationManagerCompat.notify] 会被系统静默丢弃
     * （不抛异常、不报错），所以这里不需要额外判断——权限没给就是收不到，
     * 界面上的开关旁会提示去授权。
     */
    fun show(context: Context, plan: ReminderPlan, advanceMinutes: Int) {
        ensureChannel(context)

        val timeText = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(plan.startAt))
        val minutesLeft = ((plan.startAt - System.currentTimeMillis()) / 60_000L)
            .coerceAtLeast(0L)
            .toInt()

        // 正文一行给全：几点上课、在哪、还有多久。
        // 老师名放在展开后的长文本里，因为一行塞不下，硬塞会被 ellipsis 切成半截
        val place = plan.room.ifBlank { "教室未知" }
        val shortText = if (minutesLeft > 0) {
            "$timeText · $place · 还有 $minutesLeft 分钟"
        } else {
            "$timeText · $place · 现在开始"
        }
        val longText = buildString {
            append("$timeText 开始")
            if (plan.room.isNotBlank()) append("，地点 $place")
            if (plan.teacher.isNotBlank()) append("，${plan.teacher}")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_course)
            .setContentTitle(plan.courseName)
            .setContentText(shortText)
            .setSubText("第 ${plan.weekNumber} 周 · 提前 $advanceMinutes 分钟")
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
            // 闹钟晚到（Doze / 精确闹钟被系统降级）时，标题上的时间仍然是对的
            .setWhen(plan.startAt)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, notificationIdOf(plan)))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationIdOf(plan), notification)
        }
    }

    fun notificationIdOf(plan: ReminderPlan): Int = plan.requestCode

    private fun openAppIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // 点通知进来不要重复建任务栈
            putExtra(EXTRA_FROM_REMINDER, true)
        }
        return PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 通知里带的一个标记，MainActivity 目前不用，留给以后做「点进来定位到今天」 */
    const val EXTRA_FROM_REMINDER = "from_course_reminder"
}
