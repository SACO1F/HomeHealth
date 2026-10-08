package com.example.homehealth.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.domain.repository.FamilyRepository
import com.example.homehealth.domain.repository.MedicationReminderRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MedicationAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var reminders: MedicationReminderRepository
    @Inject lateinit var family: FamilyRepository
    @Inject lateinit var notifications: NotificationHelper
    @Inject lateinit var settingsPrefs: SettingsPrefs

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == MedicationAlarmScheduler.ACTION_FIRE) {
                    val id = intent.getStringExtra(MedicationAlarmScheduler.EXTRA_REMINDER_ID)
                    val time = intent.getStringExtra(MedicationAlarmScheduler.EXTRA_TIME)
                    val reminder = id?.let { reminders.getById(it) }
                    if (reminder != null && time != null && reminder.active &&
                        time in reminder.dailyTimes()) {
                        MedicationAlarmScheduler.scheduleTime(
                            context, reminder, time, System.currentTimeMillis() + 60_000
                        )
                        family.getMember(reminder.memberId)?.let { member ->
                            notifications.showMedicationReminder(reminder, member.name, time)
                        }
                    }
                } else {
                    reminders.getAll().forEach { MedicationAlarmScheduler.schedule(context, it) }
                    // 时间/时区变更后重新对齐每日检查时刻：PeriodicWorkRequest 的固定 24h
                    // 周期会随夏令时/跨时区漂移，不重排的话 8:00 会越走越偏。
                    if (settingsPrefs.hasAcceptedConsent) {
                        DailyCheckScheduler.schedule(context, reschedule = true)
                    }
                }
            } catch (e: Exception) {
                Log.e("MedicationAlarm", "Failed to process medication alarm", e)
            } finally {
                pending.finish()
            }
        }
    }
}
