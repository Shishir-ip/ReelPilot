package com.reelpilot.app.telegram

import android.content.Context
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.QueueScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Remote control for ReelPilot, typed straight into the bot chat. */
object TelegramCommands {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    private fun fmt(postAt: Long): String =
        if (postAt <= System.currentTimeMillis() + 60_000) "now"
        else dayFmt.format(Date(postAt))

    /** Returns true if the text was a bot command (and has been handled). */
    suspend fun handle(context: Context, client: TelegramClient, chat: Long, text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("/")) return false
        val parts = trimmed.split(Regex("\\s+"))
        val dao = AppDatabase.get(context).queueDao()
        val settings = SettingsRepo.get(context).current()

        when (parts[0].lowercase().substringBefore("@")) {
            "/start", "/help" -> client.sendMessage(chat, HELP)

            "/list" -> {
                val active = dao.activeItems()
                if (active.isEmpty()) {
                    client.sendMessage(chat, "📭 Queue is empty.\nSend a video link to add one.")
                    return true
                }
                val sb = StringBuilder("📋 Queue (${active.size}):\n\n")
                active.forEachIndexed { i, item ->
                    val slot = when {
                        item.status == Status.READY || item.status == Status.POSTING ->
                            "📅 ${fmt(item.postAt)}"
                        item.status == Status.FAILED -> "❌ failed: ${item.error?.take(60)}"
                        else -> "⏳ downloading… ${item.progress}%"
                    }
                    val link = if (item.source == "link") "\n   ${item.url.take(80)}" else "\n   (video file)"
                    sb.append("${i + 1}. ${item.title.take(70)}\n   $slot$link\n\n")
                    if (sb.length > 3300) {
                        sb.append("…and ${active.size - i - 1} more")
                        return@forEachIndexed
                    }
                }
                client.sendMessage(chat, sb.toString())
            }

            "/status" -> {
                val active = dao.activeItems()
                val ready = active.filter { it.status == Status.READY }
                val next = ready.minOfOrNull { it.postAt.takeIf { p -> p > 0 } ?: Long.MAX_VALUE }
                val lastPosted = dao.byStatus(Status.POSTED).maxByOrNull { it.createdAt }
                val sb = StringBuilder()
                sb.append("📊 ReelPilot status\n\n")
                sb.append("▶️ Auto-post: ${if (settings.autoPost) "ON" else "OFF"}\n")
                sb.append("⏰ Interval: every ${settings.intervalHours}h\n")
                sb.append("📥 Queue: ${active.size} waiting (${ready.size} ready to post)\n")
                sb.append("📅 Next upload: ${next?.let { fmt(it) } ?: "—"}\n")
                lastPosted?.let {
                    sb.append("✅ Last posted: ${it.title.take(60)}\n")
                }
                sb.append("🌐 ${if (hasNetwork(context)) "Online" else "Offline — waiting for connection"}")
                client.sendMessage(chat, sb.toString())
            }

            "/remove" -> {
                val n = parts.getOrNull(1)?.toIntOrNull()
                if (n == null || n < 1) {
                    client.sendMessage(chat, "Usage: /remove 2  (number from /list)")
                    return true
                }
                val active = dao.activeItems()
                if (n > active.size) {
                    client.sendMessage(chat, "No item #$n — only ${active.size} in queue. See /list")
                    return true
                }
                val item = active[n - 1]
                dao.delete(item.id)
                QueueScheduler.reschedule(context)
                client.sendMessage(chat, "🗑️ Removed: ${item.title}\nLater videos shifted up.")
            }

            "/clear" -> {
                val n = dao.activeItems().size
                dao.clearActive()
                client.sendMessage(chat, "🧹 Cleared $n waiting videos.")
            }

            "/postnow" -> {
                PostWorker.scheduleOneTime(context, forceNow = true, expedited = true)
                client.sendMessage(chat, "⚡ Posting the next ready video right now…")
            }

            "/interval" -> {
                val h = parts.getOrNull(1)?.toIntOrNull()
                if (h == null || h < 1 || h > 72) {
                    client.sendMessage(chat, "Usage: /interval 3   (1–72 hours)")
                    return true
                }
                SettingsRepo.get(context).setIntervalHours(h)
                QueueScheduler.reschedule(context)
                PostWorker.scheduleOneTime(context, expedited = true)
                client.sendMessage(chat, "⏰ Interval set: every $h hour(s). Queue re-slotted.")
            }

            "/time" -> {
                val n = parts.getOrNull(1)?.toIntOrNull()
                val hm = parts.getOrNull(2) ?: ""
                if (n == null || !Regex("^\\d{1,2}[:.]\\d{2}$").matches(hm)) {
                    client.sendMessage(chat, "Usage: /time 2 17:30   (item # from /list)")
                    return true
                }
                val active = dao.activeItems()
                if (n > active.size) {
                    client.sendMessage(chat, "No item #$n — only ${active.size} in queue. See /list")
                    return true
                }
                val p = hm.split(":", ".")
                val item = active[n - 1]
                val newTime = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, p[0].toInt())
                    set(Calendar.MINUTE, p[1].toInt())
                    set(Calendar.SECOND, 0)
                    if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                }.timeInMillis
                dao.setPostAt(item.id, newTime)
                QueueScheduler.reschedule(context)
                PostWorker.scheduleOneTime(context, expedited = true)
                client.sendMessage(chat, "📅 \"${item.title.take(50)}\" will post at ${timeFmt.format(Date(newTime))}")
            }

            "/caption" -> {
                val caption = parts.drop(1).joinToString(" ").take(500)
                SettingsRepo.get(context).setCaption(caption)
                client.sendMessage(
                    chat,
                    if (caption.isBlank()) "✏️ Default caption cleared."
                    else "✏️ Default caption set:\n\n$caption"
                )
            }

            "/done" -> {
                val posted = dao.byStatus(Status.POSTED)
                if (posted.isEmpty()) {
                    client.sendMessage(chat, "Nothing posted yet.")
                    return true
                }
                val sb = StringBuilder("✅ Recently posted:\n\n")
                posted.takeLast(10).reversed().forEach { sb.append("• ${it.title.take(70)}\n") }
                client.sendMessage(chat, sb.toString())
            }

            "/logs" -> {
                val lines = AppLog.lines.value.take(15).reversed()
                client.sendMessage(chat, if (lines.isEmpty()) "Log is empty." else "🧾 Last events:\n\n${lines.joinToString("\n")}")
            }

            else -> client.sendMessage(chat, "Unknown command. Send /help")

        }
        return true
    }

    private fun hasNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val HELP = """
        🤖 ReelPilot commands

        /list — queue with upload times + links
        /status — next upload, counts, interval
        /remove 2 — remove video #2 from queue
        /clear — remove all waiting videos
        /time 2 17:30 — change upload time of #2
        /postnow — post the next video immediately
        /interval 3 — set posting interval (1–72 h)
        /caption text — set default caption
        /done — recently posted videos
        /logs — last events

        📥 Just send any video link to queue it.
    """.trimIndent()
}
