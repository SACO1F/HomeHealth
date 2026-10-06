package com.example.homehealth.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
                }
            } catch (e: Exception) {
                Log.e("MedicationAlarm", "Failed to process medication alarm", e)
            } finally {
                pending.finish()
            }
        }
    }
}
