package com.reelpilot.app.worker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reelpilot.app.PostActivity
import com.reelpilot.app.accessibility.ReelAccessibilityService
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.telegram.TelegramIngest
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs every 15 minutes (network-constrained). When the posting interval has
 * elapsed and a downloaded reel is waiting, wakes the screen, shares the
 * video into Instagram and drives the composer to completion.
 */
class PostWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Expedited runs (right after a download) need a foreground slot;
        // if the OS refuses, we just continue as a normal worker.
        runCatching { setForeground(getForegroundInfo()) }
        PostWorker.runCheck(applicationContext, inputData.getBoolean("force", false))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notif = NotificationCompat.Builder(applicationContext, "posts")
            .setContentTitle("ReelPilot posting…")
            .setContentText("Waking Instagram to share your reel")
            .setSmallIcon(com.reelpilot.app.R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(2001, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(2001, notif)
        }
    }

    companion object {
        const val PERIODIC = "reelpilot_post_check"
        const val ONESHOT = "reelpilot_post_once"

        @Volatile
        private var busy = AtomicBoolean(false)

        @Volatile
        private var resultDeferred: CompletableDeferred<Pair<Boolean, String>>? = null

        fun completeResult(ok: Boolean, msg: String) {
            resultDeferred?.complete(Pair(ok, msg))
        }

        /**
         * The whole posting check, callable from anywhere (worker, download
         * worker, UI). Guarded by [busy] so only one posting runs at a time.
         */
        suspend fun runCheck(ctx: Context, forceNow: Boolean) {
            if (!busy.compareAndSet(false, true)) return
            try {
                val settings = SettingsRepo.get(ctx).current()

                // Pick up any Telegram messages that arrived while the poller was dead.
                TelegramIngest.poll(ctx)

                if (!settings.autoPost) return

                // Assign slots to any newly downloaded items, then pick the
                // one whose scheduled time has arrived.
                QueueScheduler.reschedule(ctx)
                val dao = AppDatabase.get(ctx).queueDao()
                val now = System.currentTimeMillis()
                val item = dao.dueReady(now)
                    ?: (if (forceNow) dao.oldestWithStatus(Status.READY) else null)
                    ?: return

                if (!ReelAccessibilityService.isServiceEnabled(ctx)) {
                    AppLog.log("POST", "Skipped — accessibility service not enabled yet")
                    return
                }
                if (!isInstagramInstalled(ctx)) {
                    dao.update(item.copy(status = Status.FAILED, error = "Instagram app not installed"))
                    TelegramClient(settings.botToken).sendMessage(
                        item.chatId,
                        "❌ Instagram is not installed on this phone.\nInstall Instagram, log in once, then tap ↻ Retry on this item in the app's Queue tab."
                    )
                    return
                }

                dao.update(item.copy(status = Status.POSTING))
                AppLog.log("POST", "Posting: ${item.title}")

                resultDeferred = CompletableDeferred()
                val intent = Intent(ctx, PostActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(PostActivity.EXTRA_URI, item.localUri)
                    putExtra(PostActivity.EXTRA_CAPTION, item.caption.ifBlank { settings.caption })
                }
                ctx.startActivity(intent)

                val (ok, msg) = resultDeferred!!.await()
                AppLog.log("POST", "Result: ok=$ok, $msg")

                if (ok) {
                    dao.update(item.copy(status = Status.POSTED))
                    val nextAt = System.currentTimeMillis() + settings.intervalHours * 60 * 60 * 1000L
                    SettingsRepo.get(ctx).setNextPostAt(nextAt)
                    val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextAt))
                    TelegramClient(settings.botToken).sendMessage(
                        item.chatId,
                        "🚀 Posted: ${item.title}\n⏰ Next reel at $hhmm"
                    )
                } else {
                    dao.update(item.copy(status = Status.FAILED, error = msg.take(300)))
                    // Advance the schedule anyway so failures don't retry in a tight loop.
                    val nextAt = System.currentTimeMillis() + settings.intervalHours * 60 * 60 * 1000L
                    SettingsRepo.get(ctx).setNextPostAt(nextAt)
                    TelegramClient(settings.botToken)
                        .sendMessage(item.chatId, "⚠️ Posting failed: $msg\nIt will not be retried automatically — retry it from the app queue.")
                }
                // Re-slot the remaining queue after this post / failure.
                QueueScheduler.reschedule(ctx)
            } finally {
                busy.set(false)
            }
        }

        private fun isInstagramInstalled(ctx: Context): Boolean = try {
            ctx.packageManager.getPackageInfo(ReelAccessibilityService.INSTAGRAM_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }

        /** Keep the 15-minute safety net running forever. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PostWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .build()
            )
        }

        /**
         * Run a check soon. `expedited = true` bypasses WorkManager's batching
         * so posting starts within seconds of a download finishing.
         */
        fun scheduleOneTime(context: Context, forceNow: Boolean = false, expedited: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<PostWorker>()
                .setInputData(workDataOf("force" to forceNow))
            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            } else {
                builder.setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT,
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }
    }
}
