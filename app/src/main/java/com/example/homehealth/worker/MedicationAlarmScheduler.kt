package com.example.homehealth.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.homehealth.data.local.entity.MedicationReminder
import java.util.Calendar

/** 每个服药时刻只维护下一次闹钟；触发后由接收器继续安排下一天。 */
object MedicationAlarmScheduler {
    const val ACTION_FIRE = "com.example.homehealth.MEDICATION_ALARM"
    const val EXTRA_REMINDER_ID = "reminder_id"
    const val EXTRA_TIME = "time"

    fun exactAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    fun cancel(context: Context, reminder: MedicationReminder) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        reminder.dailyTimes().forEach { time ->
            val intent = pendingIntent(context, reminder.id, time)
            manager.cancel(intent)
            intent.cancel()
        }
    }

    fun schedule(context: Context, reminder: MedicationReminder) {
        reminder.dailyTimes().forEach { scheduleTime(context, reminder, it) }
    }

    fun scheduleTime(
        context: Context,
        reminder: MedicationReminder,
        time: String,
        afterMs: Long = System.currentTimeMillis()
    ) {
        if (!reminder.active || time !in reminder.dailyTimes()) return
        val trigger = nextTrigger(reminder, time, afterMs) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = pendingIntent(context, reminder.id, time)
        try {
            if (exactAvailable(context)) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            } else {
                // 未获精确闹钟授权时按指定时刻安排近似通知，界面会提示可能延迟。
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
            }
        } catch (_: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, intent)
        }
    }

    internal fun nextTrigger(reminder: MedicationReminder, time: String, afterMs: Long): Long? {
        if (!MedicationReminder.isValidTime(time)) return null
        val (hour, minute) = time.split(':').map(String::toInt)
        val candidate = Calendar.getInstance().apply {
            timeInMillis = maxOf(afterMs, reminder.startDate)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= maxOf(afterMs, reminder.startDate)) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        return candidate.takeIf { reminder.endDate == null || it <= reminder.endDate }
    }

    private fun pendingIntent(context: Context, reminderId: String, time: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "$reminderId|$time".hashCode(),
            Intent(context, MedicationAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra(EXTRA_REMINDER_ID, reminderId)
                putExtra(EXTRA_TIME, time)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
