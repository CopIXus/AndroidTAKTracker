package com.copix.androidtaktracker.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.copix.androidtaktracker.host.TrackingHost
import java.util.concurrent.TimeUnit

class ServiceWatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val host = TrackingHost.get(applicationContext)
        if (host.config.value.startup.startOnBoot) {
            TrackingForegroundService.start(applicationContext)
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<ServiceWatchdogWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "att-watchdog",
                ExistingPeriodicWorkPolicy.UPDATE,
                req,
            )
        }
    }
}
