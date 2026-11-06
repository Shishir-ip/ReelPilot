package com.reelpilot.app.worker

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
        if (!busy.compareAndSet(false, true)) return Result.success()
        try {
            val ctx = applicationContext
            val settings = SettingsRepo.get(ctx).current()

            // Pick up any Telegram messages that arrived while the poller was dead.
            TelegramIngest.poll(ctx)

            if (!settings.autoPost) return Result.success()
            val now = System.currentTimeMillis()
            val forceNow = inputData.getBoolean("force", false)
            if (settings.nextPostAt > now && !forceNow) return Result.success()

            val dao = AppDatabase.get(ctx).queueDao()
            val item = dao.oldestWithStatus(Status.READY) ?: return Result.success()

            if (!ReelAccessibilityService.isServiceEnabled(ctx)) {
                AppLog.log("POST", "Skipped — accessibility service not enabled yet")
                return Result.success()
            }
            if (!isInstagramInstalled(ctx)) {
                dao.update(item.copy(status = Status.FAILED, error = "Instagram app not installed"))
                TelegramClient(settings.botToken).sendMessage(item.chatId, "❌ Instagram is not installed on this phone.")
                return Result.success()
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
                // Queue up the next download if anything is waiting.
                scheduleOneTime(ctx)
            } else {
                dao.update(item.copy(status = Status.FAILED, error = msg.take(300)))
                // Advance the schedule anyway so failures don't retry in a tight loop.
                val nextAt = System.currentTimeMillis() + settings.intervalHours * 60 * 60 * 1000L
                SettingsRepo.get(ctx).setNextPostAt(nextAt)
                TelegramClient(settings.botToken)
                    .sendMessage(item.chatId, "⚠️ Posting failed: $msg\nIt will not be retried automatically — retry it from the app queue.")
            }
        } finally {
            busy.set(false)
        }
        return Result.success()
    }

    private fun isInstagramInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(ReelAccessibilityService.INSTAGRAM_PACKAGE, 0)
        true
    } catch (e: Exception) {
        false
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

        /** Run a check soon (after a download finishes, settings change, etc.). */
        fun scheduleOneTime(context: Context, forceNow: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONESHOT,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PostWorker>()
                    .setConstraints(
                        androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                            .build()
                    )
                    .setInputData(workDataOf("force" to forceNow))
                    .build()
            )
        }
    }
}
