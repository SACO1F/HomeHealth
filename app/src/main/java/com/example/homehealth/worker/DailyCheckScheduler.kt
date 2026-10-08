package com.example.homehealth.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 每日健康检查任务的排期（单一入口）。
 *
 * 抽成 object 的原因：排期逻辑有两个调用方 —— 应用启动（[com.example.homehealth.HomeHealthApp]）
 * 与系统时间/时区变更广播（[MedicationAlarmReceiver]）。若两处各写一份，改一处忘一处必然漂移。
 *
 * 关于漂移：`PeriodicWorkRequest` 的周期是固定 24h，一旦用户跨时区或经历夏令时切换，
 * 对齐的 8:00 会整体偏移且不会自动纠正。因此时间/时区变更时用
 * [ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE] 按新的本地时间重算一次。
 */
object DailyCheckScheduler {

    /** 每日对齐的执行时刻（本地时间） */
    private const val RUN_HOUR = 8

    /**
     * 排期每日健康检查。
     *
     * @param reschedule 为 true 时取消并重建任务（用于时区/时间变更后重新对齐）；
     *   为 false 时若任务已存在则保持不动（避免每次冷启动都重置周期）。
     */
    fun schedule(context: Context, reschedule: Boolean = false) {
        val now = Calendar.getInstance()
        val nextRun = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, RUN_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val delay = nextRun.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<DailyCheckWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DailyCheckWorker.WORK_NAME,
            if (reschedule) {
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            } else {
                ExistingPeriodicWorkPolicy.KEEP
            },
            request
        )
    }
}
