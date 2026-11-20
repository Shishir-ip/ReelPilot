package com.reelpilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Offline-safe housekeeping loop: watches the picked folder for new videos,
 * checks the battery level, and auto-deletes old posted videos.
 * Runs every 15 minutes with NO constraints so it works even offline.
 */
class WatchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        FolderWatcher.scan(applicationContext)
        Maintenance.checkBattery(applicationContext)
        Maintenance.clean(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK = "reelpilot_watch"

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).build()
            )
        }
    }
}
