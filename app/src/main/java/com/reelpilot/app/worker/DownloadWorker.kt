package com.reelpilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.python.ProgressBridge
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Downloads a queued item (yt-dlp for links, bot API for direct files). */
class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong("itemId", -1)
        if (id < 0) return Result.success()
        val dao = AppDatabase.get(applicationContext).queueDao()
        val item = dao.byId(id) ?: return Result.success()
        if (item.status != Status.QUEUED) return Result.success()

        dao.update(item.copy(status = Status.DOWNLOADING))
        AppLog.log("DL", "Starting: ${item.title}")

        try {
            val uri = when (item.source) {
                "file" -> downloadTelegramFile(item)
                else -> downloadLink(item)
            }
            dao.update(item.copy(status = Status.READY, localUri = uri, progress = 100))

            // Assign its slot in the schedule and report the exact time.
            QueueScheduler.reschedule(applicationContext)
            val fresh = dao.byId(id)
            val slot = fresh?.postAt ?: 0
            val whenText = if (slot <= System.currentTimeMillis() + 60_000)
                "as soon as possible"
            else
                SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(slot))
            val durText = fresh?.localUri?.let { uri ->
                try {
                    val r = android.media.MediaMetadataRetriever()
                    r.setDataSource(applicationContext, android.net.Uri.parse(uri))
                    val ms = r.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0
                    r.release()
                    if (ms > 0) "\n⏱ Length: ${ms / 60000}:${String.format(Locale.getDefault(), "%02d", (ms / 1000) % 60)}" else ""
                } catch (e: Exception) ""
            } ?: ""
            val s = SettingsRepo.get(applicationContext).current()
            TelegramClient(s.botToken).sendMessage(
                item.chatId,
                "✅ Downloaded: ${item.title}\n⏰ Will post at: $whenText$durText"
            )
            // Post right away when it's due (first post / elapsed interval);
            // later queued items still wait for the configured interval.
            PostWorker.scheduleOneTime(applicationContext, expedited = true)
        } catch (e: Exception) {
            AppLog.log("DL", "Failed: ${e.message}")
            dao.update(item.copy(status = Status.FAILED, error = e.message?.take(300)))
            TelegramClient(SettingsRepo.get(applicationContext).current().botToken)
                .sendMessage(item.chatId, "❌ Download failed: ${e.message?.take(200)}")
        }
        return Result.success()
    }

    private suspend fun downloadLink(item: QueueItem): String = coroutineScope {
        val dao = AppDatabase.get(applicationContext).queueDao()
        val job = launch(Dispatchers.IO) {
            while (isActive) {
                dao.update(item.copy(status = Status.DOWNLOADING, progress = ProgressBridge.progress.value))
                delay(3000)
            }
        }
        try {
            YtDlpBridge.downloadAndSave(applicationContext, item.url).first
        } finally {
            job.cancel()
        }
    }

    private suspend fun downloadTelegramFile(item: QueueItem): String {
        val s = SettingsRepo.get(applicationContext).current()
        val client = TelegramClient(s.botToken)
        val url = client.fileUrl(item.url)
        val tmp = File(applicationContext.cacheDir, "tg_${item.id}.mp4")
        client.downloadFile(url, tmp)
        val uri = YtDlpBridge.saveDownloadedFile(applicationContext, tmp, item.title)
        tmp.delete()
        return uri
    }
}
