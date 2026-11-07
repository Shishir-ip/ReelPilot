package com.reelpilot.app.telegram

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.QueueDao
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.worker.DownloadWorker
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Reads Telegram updates and turns them into queue items.
 * Shared by the always-on polling service and the periodic PostWorker
 * (so links sent while the service is dead are still picked up).
 */
object TelegramIngest {

    private val URL_REGEX = Regex(
        "(https?://|www\\.)[\\w\\-@:%.+~#=]{1,256}\\.[a-zA-Z0-9()]{1,6}\\b[\\w\\-@:%+.~#?&/=]*",
        RegexOption.IGNORE_CASE
    )

    private const val MAX_BOT_FILE = 20L * 1024 * 1024

    // The service and the posting worker both poll; this lock guarantees only
    // one fetch/process cycle runs at a time so an update can never be
    // processed twice (the cause of duplicate downloads).
    private val pollMutex = kotlinx.coroutines.sync.Mutex()

    /** Fetch and process any pending Telegram updates. Returns count processed. */
    suspend fun poll(context: Context): Int = pollMutex.withLock {
        val settings = SettingsRepo.get(context).current()
        if (settings.botToken.isBlank()) return 0
        var cursor = loadOffset(context)
        var processed = 0
        try {
            val client = TelegramClient(settings.botToken)
            val updates = client.getUpdates(cursor)
            for (i in 0 until updates.length()) {
                val update = updates.getJSONObject(i)
                cursor = update.getLong("update_id") + 1
                if (handleUpdate(context, client, settings, update)) processed++
            }
            saveOffset(context, cursor)
        } catch (e: Exception) {
            AppLog.log("TG", "poll failed: ${e.message}")
        }
        processed
    }

    private suspend fun handleUpdate(
        context: Context,
        client: TelegramClient,
        settings: AppSettings,
        update: JSONObject
    ): Boolean {
        val msg = update.optJSONObject("message") ?: return false
        val from = msg.optJSONObject("from")?.optLong("id") ?: return false
        val chat = msg.optJSONObject("chat")?.optLong("id") ?: return false

        if (settings.allowAnyone) {
            // Open mode: any Telegram account can send links (default).
            return handleAuthorized(context, client, msg, chat)
        }

        // Restricted mode: whitelist only; the first user auto-claims ownership.
        val allowed = settings.allowedUsers
        if (allowed.isEmpty()) {
            SettingsRepo.get(context).addAllowedUser(from.toString())
            client.sendMessage(chat, "👋 ReelPilot setup complete: you are now the only user who can queue posts.\n\nSend me any video link (YouTube, TikTok, Twitter...) and I'll download it and post it to Instagram as a reel on your schedule.\n\nTip: video files up to 20 MB work too — for bigger ones, send a link.")
            AppLog.log("TG", "Owner auto-set: $from")
            return handleAuthorized(context, client, msg, chat)
        }
        if (from.toString() !in allowed) {
            AppLog.log("TG", "Ignored message from unauthorized user $from")
            return false
        }
        return handleAuthorized(context, client, msg, chat)
    }

    private suspend fun handleAuthorized(
        context: Context,
        client: TelegramClient,
        msg: JSONObject,
        chat: Long
    ): Boolean {
        val dao = AppDatabase.get(context).queueDao()

        // Direct video file?
        val videoObj = msg.optJSONObject("video")
            ?: msg.optJSONObject("document")
                ?.takeIf { it.optString("mime_type", "").startsWith("video/") }
        if (videoObj != null) {
            val size = videoObj.optLong("file_size", 0)
            if (size > MAX_BOT_FILE) {
                client.sendMessage(chat, "⚠️ File is larger than 20 MB (Telegram bot limit).\nSend a video *link* instead — links have no size limit.")
                return true
            }
            val fileId = videoObj.optString("file_id")
            val title = videoObj.optString("file_name", "Telegram video").ifBlank { "Telegram video" }
            enqueue(context, client, dao, QueueItem(
                source = "file", url = fileId, title = title,
                caption = msg.optString("caption", ""), chatId = chat
            ))
            return true
        }

        // Link in text?
        val text = msg.optString("text", "")
        if (text.isNotBlank()) {
            // Bot commands first (/list, /status, …)
            if (text.trim().startsWith("/")) {
                return TelegramCommands.handle(context, client, chat, text)
            }
            val url = URL_REGEX.find(text)?.value
            if (url != null) {
                enqueue(context, client, dao, QueueItem(
                    source = "link", url = url,
                    title = "Link: ${url.take(80)}",
                    // Caption = message text with the URL stripped out, so the
                    // reel doesn't get captioned with the raw link.
                    caption = text.replaceFirst(URL_REGEX, "").trim().take(200),
                    chatId = chat
                ))
                return true
            }
            client.sendMessage(chat, "🤖 ReelPilot here!\nSend a video link or a video file (≤20 MB) and I'll handle the rest.")
            return true
        }
        return false
    }

    private suspend fun enqueue(
        context: Context,
        client: TelegramClient,
        dao: QueueDao,
        item: QueueItem
    ) {
        // Dedupe: never queue the same link/file twice while it's still active.
        if (dao.activeCountForUrl(item.url) > 0) {
            client.sendMessage(item.chatId, "ℹ️ Already in the queue: ${item.title}")
            AppLog.log("TG", "Duplicate skipped: ${item.title}")
            return
        }
        val id = dao.insert(item)
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf("itemId" to id))
                .build()
        )
        val position = dao.pendingCount()
        client.sendMessage(item.chatId, "📥 Queued (#$position): ${item.title}")
        AppLog.log("TG", "Queued #$id: ${item.title}")
    }

    private fun saveOffset(context: Context, offset: Long) {
        context.getSharedPreferences("tg", Context.MODE_PRIVATE)
            .edit().putLong("offset", offset).apply()
    }

    private fun loadOffset(context: Context): Long =
        context.getSharedPreferences("tg", Context.MODE_PRIVATE).getLong("offset", 0)
}
