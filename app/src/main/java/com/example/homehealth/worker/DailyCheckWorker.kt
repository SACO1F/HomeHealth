package com.example.homehealth.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.homehealth.domain.usecase.DetectAnomaliesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 每日健康检查任务：
 * 对所有成员执行异常检测，汇总通知。
 */
@HiltWorker
class DailyCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val detectAnomalies: DetectAnomaliesUseCase,
    private val notificationHelper: NotificationHelper
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // 异常检测
            val created = detectAnomalies.invokeAll()
            if (created > 0) {
                notificationHelper.showAnomalySummary(created)
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "daily_health_check"
    }
}
