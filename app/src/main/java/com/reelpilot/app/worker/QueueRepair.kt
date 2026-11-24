package com.reelpilot.app.worker

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.Status
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repairs queue rows left inconsistent by the 2.0.0 race bug (a parallel
 * caption write could revert a finished download back to DOWNLOADING and
 * wipe its localUri). Runs once at app start:
 *
 *  - DOWNLOADING items with no download work running are orphans. If the
 *    video file is known (or can be matched in the ReelPilot gallery folder
 *    by download time), the item is marked READY without re-downloading.
 *    Otherwise it is re-queued so the download runs again.
 *  - Captions stuck in "pending" with no caption work running are re-enqueued.
 */
object QueueRepair {

    suspend fun run(context: Context) {
        val ctx = context.applicationContext
        val dao = AppDatabase.get(ctx).queueDao()
        val wm = WorkManager.getInstance(ctx)

        val dlBusy = withContext(Dispatchers.IO) {
            wm.getWorkInfosForUniqueWork("reelpilot_dl").get()
        }.any { !it.state.isFinished }

        val stuck = dao.byStatus(Status.DOWNLOADING)
        if (stuck.isNotEmpty() && !dlBusy) {
            val referenced = dao.allLocalUris().toHashSet()
            val gallery = reelPilotVideos(ctx)
            for (item in stuck.sortedBy { it.createdAt }) {
                val known = item.localUri
                if (known != null) {
                    dao.markDownloaded(item.id, known)
                    AppLog.log("REPAIR", "Recovered download (file known): ${item.title.take(60)}")
                    continue
                }
                // The 2.0.0 bug wiped localUri — find the video that landed in
                // Movies/ReelPilot after this link was queued and isn't already
                // claimed by another queue item.
                val match = gallery.firstOrNull { (uri, addedMs) ->
                    uri !in referenced && addedMs >= item.createdAt - 60_000
                }
                if (match != null) {
                    referenced.add(match.first)
                    dao.markDownloaded(item.id, match.first)
                    AppLog.log("REPAIR", "Recovered download (gallery match): ${item.title.take(60)}")
                } else {
                    dao.requeue(item.id)
                    wm.enqueueUniqueWork(
                        "reelpilot_dl",
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<DownloadWorker>()
                            .setInputData(workDataOf("itemId" to item.id))
                            .build()
                    )
                    AppLog.log("REPAIR", "Re-queued orphaned download: ${item.title.take(60)}")
                }
            }
        }

        val capBusy = withContext(Dispatchers.IO) {
            wm.getWorkInfosForUniqueWork("reelpilot_caption").get()
        }.any { !it.state.isFinished }
        if (!capBusy) {
            for (item in dao.pendingCaptions()) {
                CaptionWorker.enqueue(ctx, item.id)
                AppLog.log("REPAIR", "Re-enqueued caption: ${item.title.take(60)}")
            }
        }

        // Always run a posting check at startup: it recovers items left stuck
        // in POSTING by a killed attempt and fires any slot that came due
        // while the app was dead.
        QueueScheduler.reschedule(ctx)
        PostWorker.scheduleOneTime(ctx)
    }

    /** (content uri, dateAdded ms) for every video in Movies/ReelPilot, oldest first. */
    private fun reelPilotVideos(ctx: Context): List<Pair<String, Long>> {
        val out = mutableListOf<Pair<String, Long>>()
        try {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Video.Media.IS_PENDING} = 0"
                args = arrayOf("Movies/ReelPilot%")
            } else {
                selection = "${MediaStore.Video.Media.DATA} LIKE ?"
                args = arrayOf("%/Movies/ReelPilot/%")
            }
            ctx.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DATE_ADDED),
                selection, args,
                "${MediaStore.Video.Media.DATE_ADDED} ASC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    out.add(uri.toString() to c.getLong(dateCol) * 1000L)
                }
            }
        } catch (e: Exception) {
            AppLog.log("REPAIR", "Gallery scan failed: ${e.message}")
        }
        return out
    }
}