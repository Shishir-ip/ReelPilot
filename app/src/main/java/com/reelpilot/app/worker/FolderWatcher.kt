package com.reelpilot.app.worker

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.util.Format
import android.media.MediaMetadataRetriever

/**
 * Watches a user-picked storage folder: any new video file dropped in
 * (e.g. copied over USB from a PC) is auto-queued. Files are remembered
 * by name+size so the same video is never queued twice.
 */
object FolderWatcher {

    private val VIDEO_EXT = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v")

    suspend fun scan(context: Context) {
        val ctx = context.applicationContext
        val settings = SettingsRepo.get(ctx).current()
        if (settings.watchFolderUri.isBlank()) return
        try {
            val treeUri = Uri.parse(settings.watchFolderUri)
            val dir = DocumentFile.fromTreeUri(ctx, treeUri) ?: return
            if (!dir.exists() || !dir.canRead()) {
                AppLog.log("WATCH", "Watched folder unreadable — re-select it in Settings")
                return
            }

            val prefs = ctx.getSharedPreferences("watched", Context.MODE_PRIVATE)
            val seen = (prefs.getStringSet("seen", emptySet()) ?: emptySet()).toMutableSet()

            val dao = AppDatabase.get(ctx).queueDao()
            var added = 0
            val names = mutableListOf<String>()

            for (f in dir.listFiles()) {
                if (!f.isFile || !f.canRead()) continue
                val name = f.name ?: continue
                val lower = name.lowercase()
                if (VIDEO_EXT.none { lower.endsWith(it) }) continue
                val key = "$name:${f.length()}"
                if (key in seen) continue
                seen.add(key)

                try {
                    // Copy the file into Movies/ReelPilot so Instagram can read it.
                    val localUri = ctx.contentResolver.openInputStream(f.uri)?.use { input ->
                        YtDlpBridge.saveStreamToGallery(ctx, input, name, "video/mp4")
                    } ?: continue

                    val durMs = try {
                        val r = MediaMetadataRetriever()
                        r.setDataSource(ctx, Uri.parse(localUri))
                        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                        r.release()
                        ms
                    } catch (e: Exception) { 0 }

                    dao.insert(QueueItem(
                        source = "gallery",
                        url = localUri,
                        title = name,
                        caption = "",
                        chatId = settings.lastChatId,
                        status = Status.READY,
                        progress = 100,
                        localUri = localUri
                    ))
                    names.add("$name${if (durMs > 0) " (${Format.duration(durMs)})" else ""}")
                    added++
                } catch (e: Exception) {
                    AppLog.log("WATCH", "Could not add $name: ${e.message}")
                }
            }

            prefs.edit().putStringSet("seen", seen).apply()

            if (added > 0) {
                QueueScheduler.reschedule(ctx)
                PostWorker.scheduleOneTime(ctx)
                val list = names.take(5).joinToString("\n") { "• $it" }
                TelegramClient(settings.botToken).sendMessage(
                    settings.lastChatId,
                    "📂 $added new video${if (added > 1) "s" else ""} added from watched folder:\n$list"
                )
                AppLog.log("WATCH", "Auto-queued $added video(s) from watched folder")
            }
        } catch (e: Exception) {
            AppLog.log("WATCH", "Folder scan failed: ${e.message}")
        }
    }
}
