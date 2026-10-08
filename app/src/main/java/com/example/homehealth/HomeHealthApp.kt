package com.example.homehealth

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.example.homehealth.data.SettingsPrefs
import com.example.homehealth.worker.DailyCheckScheduler
import com.example.homehealth.worker.NotificationHelper
import com.example.homehealth.worker.MedicationAlarmScheduler
import com.example.homehealth.domain.repository.MedicationReminderRepository
import dagger.hilt.android.HiltAndroidApp
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

    @Inject lateinit var settingsPrefs: SettingsPrefs

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        notificationHelper.createChannels()
        // 首启同意之前不调度任何会处理/通知健康数据的任务：
        // 新装用户或同意版本号递增时，用户必须先看到说明并同意，才可能出现健康预警。
        // 同意后由 MainActivity 在用户点「同意」时立即排期。
        if (settingsPrefs.hasAcceptedConsent) {
            DailyCheckScheduler.schedule(this)
        }
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
}
