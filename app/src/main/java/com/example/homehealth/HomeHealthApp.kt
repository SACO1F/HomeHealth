package com.example.homehealth

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.homehealth.worker.DailyCheckWorker
import com.example.homehealth.worker.NotificationHelper
import com.example.homehealth.worker.MedicationAlarmScheduler
import com.example.homehealth.domain.repository.MedicationReminderRepository
import dagger.hilt.android.HiltAndroidApp
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class HomeHealthApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var notificationHelper: NotificationHelper

    @Inject lateinit var medicationReminders: MedicationReminderRepository

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        scheduleDailyCheck()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                medicationReminders.getAll().forEach {
                    MedicationAlarmScheduler.schedule(this@HomeHealthApp, it)
                }
            } catch (e: Exception) {
                Log.e("HomeHealthApp", "Failed to restore medication alarms", e)
            }
        }
    }

    /** 每日 8:00 执行异常检查；用药提醒独立按每个设定时刻调度。 */
    private fun scheduleDailyCheck() {
        val now = Calendar.getInstance()
        val nextRun = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = nextRun.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DailyCheckWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
