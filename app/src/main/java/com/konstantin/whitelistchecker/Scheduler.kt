package com.konstantin.whitelistchecker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Фоновая периодическая проверка через WorkManager.
 *
 * WorkManager не умеет периодические задачи чаще 15 минут, поэтому короткие
 * интервалы (30 сек – 10 мин) отрабатывает таймер внутри приложения, а сюда
 * попадают только интервалы от 15 минут.
 */
object Scheduler {

    const val MIN_BACKGROUND_INTERVAL_SEC = 15 * 60

    private const val WORK_NAME = "whitelist_periodic_check"

    fun apply(context: Context, autoCheck: Boolean, intervalSeconds: Int) {
        val workManager = WorkManager.getInstance(context)

        if (!autoCheck || intervalSeconds < MIN_BACKGROUND_INTERVAL_SEC) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val minutes = (intervalSeconds / 60).coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<CheckWorker>(minutes, TimeUnit.MINUTES).build()
        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
