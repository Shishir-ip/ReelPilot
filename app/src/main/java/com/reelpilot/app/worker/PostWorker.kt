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
import com.reelpilot.app.telegram.NotifyOutbox
import com.reelpilot.app.telegram.TelegramIngest
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
         * Caption for one target app, honoring its per-app mode:
         *  generated → AI caption, then the sent text, then the global default
         *  manual    → the text set for this app in the Caption tab
         *  none      → nothing
         */
        fun captionFor(
            item: com.reelpilot.app.data.QueueItem,
            pkg: String,
            s: com.reelpilot.app.data.AppSettings
        ): String = when (SettingsRepo.captionModeFor(s, pkg)) {
            "none" -> ""
            "manual" -> SettingsRepo.manualCaptionFor(s, pkg)
            else -> item.generatedCaption.ifBlank {
                item.caption.ifBlank { s.caption }
            }
        }.trim()

        /**
         * The whole posting check, callable from anywhere (worker, download
         * worker, UI). Guarded by [busy] so only one posting runs at a time.
         */
        suspend fun runCheck(ctx: Context, forceNow: Boolean) {
            if (!busy.compareAndSet(false, true)) return
            try {
                val settings = SettingsRepo.get(ctx).current()
                val dao = AppDatabase.get(ctx).queueDao()

                // We hold the busy lock, so nothing is legitimately posting
                // right now. Any POSTING row is left over from an attempt
                // that died (process kill / crash) — recover it, otherwise
                // it would never be picked up again (dueReady wants READY).
                val recovered = dao.resetStalePosting()
                if (recovered > 0) {
                    AppLog.log("POST", "Recovered $recovered item(s) stuck in POSTING — back to READY")
                }

                // Pick up any Telegram messages that arrived while the poller was dead.
                TelegramIngest.poll(ctx)

                if (!settings.autoPost) {
                    AppLog.log("POST", "Skipped — auto-post is turned off in Settings")
                    return
                }

                // Assign slots to any newly downloaded items, then pick the
                // one whose scheduled time has arrived.
                QueueScheduler.reschedule(ctx)
                val now = System.currentTimeMillis()
                val picked = dao.dueReady(now)
                    ?: (if (forceNow) dao.oldestWithStatus(Status.READY) else null)
                if (picked == null) {
                    val next = dao.activeItems()
                        .filter { it.status == Status.READY && it.postAt > 0 }
                        .minOfOrNull { it.postAt }
                    AppLog.log(
                        "POST",
                        if (next == null) "Check: nothing ready to post"
                        else "Check: nothing due yet — next slot ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(next))}"
                    )
                    return
                }
                var item: com.reelpilot.app.data.QueueItem = picked

                // The AI caption may still be generating — wait for it (bounded).
                if (item.captionStatus == "pending") {
                    AppLog.log("POST", "Waiting for the caption to finish generating…")
                    val deadline = System.currentTimeMillis() + 10 * 60_000L
                    while (System.currentTimeMillis() < deadline) {
                        val fresh = dao.byId(item.id) ?: return
                        if (fresh.captionStatus != "pending") { item = fresh; break }
                        kotlinx.coroutines.delay(5000)
                    }
                }

                if (!ReelAccessibilityService.isServiceEnabled(ctx)) {
                    AppLog.log("POST", "Skipped — accessibility service not enabled yet")
                    return
                }

                // Multi-target: post this video to every selected app, one
                // after another. Progress is stored on the item so a retry
                // resumes with the remaining apps.
                val targets = SettingsRepo.parseTargets(settings.targets)
                val done = item.doneTargets.split(',').filter { it.isNotBlank() }.toMutableList()
                val pending = targets.filter { it !in done }
                if (pending.isEmpty()) {
                    // Everything already posted (e.g. targets changed mid-way).
                    dao.markPosted(item.id, System.currentTimeMillis())
                    NotifyOutbox.enqueue(ctx, item.chatId, "✅ Already posted to all apps: ${item.title}")
                    return
                }

                val failures = mutableListOf<String>()
                for (pkg in pending) {
                    // Re-read so a caption that finished generating after this
                    // check started is still picked up for the next app.
                    item = dao.byId(item.id) ?: return
                    val label = appLabel(ctx, pkg)
                    if (!isAppInstalled(ctx, pkg)) {
                        failures.add("$label (not installed)")
                        AppLog.log("POST", "Skipping $label — not installed")
                        NotifyOutbox.enqueue(
                            ctx,
                            item.chatId,
                            "⚠️ $label is not installed on this phone — skipping it and continuing with the other apps.\nInstall it (and log in once), then tap ↻ Retry on this item."
                        )
                        continue
                    }
                    // Apps without built-in automation need a recorded flow;
                    // Instagram/Facebook need one only in "manual" mode.
                    val needsFlow = !SettingsRepo.hasBuiltIn(pkg) ||
                        SettingsRepo.flowModeFor(settings, pkg) == "manual"
                    if (needsFlow && !SettingsRepo.hasRecordedFlow(settings, pkg)) {
                        failures.add("$label (no flow)")
                        AppLog.log("POST", "Skipping $label — no recorded flow")
                        NotifyOutbox.enqueue(
                            ctx,
                            item.chatId,
                            "⚠️ No recorded flow for $label — skipping it and continuing with the other apps.\nOpen ReelPilot → Settings → Post to apps → record a flow for it, then retry."
                        )
                        continue
                    }

                    dao.setStatus(item.id, Status.POSTING)
                    AppLog.log("POST", "Posting to $label: ${item.title}")

                    resultDeferred = CompletableDeferred()
                    val intent = Intent(ctx, PostActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(PostActivity.EXTRA_URI, item.localUri)
                        putExtra(PostActivity.EXTRA_CAPTION, captionFor(item, pkg, settings))
                        putExtra(PostActivity.EXTRA_TARGET, pkg)
                    }
                    ctx.startActivity(intent)

                    // Hard ceiling: PostActivity has its own 9-minute flow
                    // timeout, but if it is killed/crashes before reporting,
                    // an unbounded await would hold the busy lock forever and
                    // silently block every future scheduled post.
                    val result = kotlinx.coroutines.withTimeoutOrNull(12 * 60_000L) {
                        resultDeferred!!.await()
                    }
                    if (result == null) {
                        failures.add("$label (interrupted)")
                        NotifyOutbox.enqueue(
                            ctx,
                            item.chatId,
                            "⚠️ Posting to $label was interrupted (screen closed or app killed) — skipping it and continuing with the other apps.\nTap ↻ Retry later to try it again."
                        )
                        AppLog.log("POST", "Timed out waiting for $label result — skipping, lock stays held until all apps are tried")
                        continue
                    }
                    val (ok, msg) = result
                    AppLog.log("POST", "Result for $label: ok=$ok, $msg")

                    if (ok) {
                        done.add(pkg)
                        dao.setDoneTargets(item.id, done.joinToString(","))
                        NotifyOutbox.enqueue(ctx, item.chatId, "🚀 Posted to $label: ${item.title}")
                    } else {
                        failures.add("$label: ${msg.take(120)}")
                        NotifyOutbox.enqueue(
                            ctx,
                            item.chatId,
                            "⚠️ Posting to $label failed: $msg\nSkipping it and continuing with the remaining apps."
                        )
                    }
                    // Let the previous app settle before opening the next one.
                    kotlinx.coroutines.delay(3000)
                }

                // Advance the schedule so failures don't retry in a tight loop.
                val nextAt = System.currentTimeMillis() + settings.intervalHours * 60 * 60 * 1000L
                SettingsRepo.get(ctx).setNextPostAt(nextAt)
                if (failures.isEmpty()) {
                    // All targets done — the item is fully posted.
                    dao.markPosted(item.id, System.currentTimeMillis())
                    val hhmm = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextAt))
                    NotifyOutbox.enqueue(
                        ctx,
                        item.chatId,
                        "✅ Posted to all ${done.size} app(s): ${item.title}\n⏰ Next reel at $hhmm"
                    )
                } else {
                    // Some apps failed — keep the successes, mark the item
                    // FAILED so ↻ Retry resumes with only the failed apps.
                    dao.markFailed(item.id, ("Failed: " + failures.joinToString("; ")).take(280))
                    NotifyOutbox.enqueue(
                        ctx,
                        item.chatId,
                        "⚠️ Posted to ${done.size}/${targets.size} app(s): ${item.title}\nFailed: ${failures.joinToString("; ")}\nTap ↻ Retry in the app Queue — it retries only the failed apps."
                    )
                }
                // Re-slot the remaining queue after this post.
                QueueScheduler.reschedule(ctx)
            } finally {
                busy.set(false)
                // Re-arm the exact wake-up for the next waiting slot (or
                // cancel it when nothing is waiting). Due-but-skipped items
                // are retried by the 15-minute sweep, not a tight alarm loop.
                runCatching { PostAlarm.schedule(ctx) }
            }
        }

        private fun isAppInstalled(ctx: Context, pkg: String): Boolean = try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }

        fun appLabel(ctx: Context, pkg: String): String = try {
            ctx.packageManager.getApplicationInfo(pkg, 0).loadLabel(ctx.packageManager).toString()
        } catch (e: Exception) {
            pkg.substringAfterLast('.').ifBlank { pkg }
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
         * Schedule a posting check aimed at the earliest waiting slot: it
         * fires when the slot time arrives (immediately if due now), so a
         * manually set time is honoured instead of waiting for the next
         * 15-minute sweep.
         *
         * Policy: if a check is RUNNING, append after it (never interrupt a
         * live post). Otherwise REPLACE — APPEND would chain the new check
         * behind a previously enqueued delayed check, so a time change via
         * /time or the Queue editor would not actually fire until the OLD
         * slot arrived (the "shows now but never uploads" bug).
         */
        fun scheduleOneTime(context: Context, forceNow: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            val (delayMs, running) = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val isRunning = try {
                    wm.getWorkInfosForUniqueWork(ONESHOT).get()
                        .any { it.state == androidx.work.WorkInfo.State.RUNNING }
                } catch (e: Exception) {
                    false
                }
                val d = if (forceNow) 0L else {
                    val earliest = try {
                        AppDatabase.get(context).queueDao().activeItems()
                            // POSTING counts too: if it's an orphan from a killed
                            // attempt, the check resets it to READY and posts it.
                            .filter { (it.status == Status.READY || it.status == Status.POSTING) && it.postAt > 0 }
                            .minOfOrNull { it.postAt }
                    } catch (e: Exception) {
                        null
                    }
                    if (earliest == null) -1L
                    else (earliest - System.currentTimeMillis()).coerceAtLeast(0L)
                }
                d to isRunning
            }
            if (delayMs < 0) {
                // Nothing ready to post — drop any stale wake-up.
                PostAlarm.schedule(context)
                return
            }
            val builder = OneTimeWorkRequestBuilder<PostWorker>()
                .setInputData(workDataOf("force" to forceNow))
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            if (delayMs == 0L) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            wm.enqueueUniqueWork(
                ONESHOT,
                if (running) ExistingWorkPolicy.APPEND else ExistingWorkPolicy.REPLACE,
                builder.build()
            )
            // Exact-alarm wake-up so Doze/battery saver can't push the slot
            // into a late maintenance window.
            PostAlarm.schedule(context)
        }
    }
}
