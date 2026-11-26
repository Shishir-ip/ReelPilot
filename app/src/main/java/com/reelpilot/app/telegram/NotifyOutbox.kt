package com.reelpilot.app.telegram

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

private val Context.outboxStore by preferencesDataStore("reelpilot_outbox")

/**
 * Persistent Telegram notification outbox.
 *
 * Post-result messages used to be sent once, inline from the posting worker.
 * If the process was killed right after the upload (OEM battery cleanup once
 * ReelPilot goes to background) or the HTTP call failed, the message was lost
 * forever — the user never learned which apps succeeded or failed.
 *
 * Now every notification is persisted FIRST, then sent. The always-on
 * [TgPollService] flushes whatever is still pending on every poll cycle, so
 * delivery survives process death, Doze and transient network errors.
 */
object NotifyOutbox {
    private val KEY = stringPreferencesKey("pending")
    private val mutex = Mutex()
    private const val MAX_TRIES = 40

    /** Queue a message (falling back to the last active chat) and try to send now. */
    suspend fun enqueue(ctx: Context, chatId: Long, text: String) {
        val s = SettingsRepo.get(ctx).current()
        val chat = if (chatId != 0L) chatId else s.lastChatId
        if (chat == 0L || s.botToken.isBlank()) {
            AppLog.log("TG", "Notification not queued — no chat/bot configured: ${text.take(60)}")
            return
        }
        mutex.withLock {
            val arr = read(ctx)
            arr.put(JSONObject().put("chat", chat).put("text", text).put("tries", 0))
            ctx.outboxStore.edit { it[KEY] = arr.toString() }
        }
        flush(ctx)
    }

    /** Send everything pending; keep failures for the next cycle. */
    suspend fun flush(ctx: Context) {
        val s = SettingsRepo.get(ctx).current()
        if (s.botToken.isBlank()) return
        val client = TelegramClient(s.botToken)
        mutex.withLock {
            val arr = read(ctx)
            if (arr.length() == 0) return
            val keep = JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val ok = client.sendMessage(m.getLong("chat"), m.getString("text"))
                if (!ok) {
                    val tries = m.optInt("tries") + 1
                    if (tries < MAX_TRIES) {
                        m.put("tries", tries)
                        keep.put(m)
                    } else {
                        AppLog.log("TG", "Notification gave up after $MAX_TRIES tries: ${m.getString("text").take(60)}")
                    }
                }
            }
            ctx.outboxStore.edit { it[KEY] = keep.toString() }
            if (keep.length() > 0) {
                AppLog.log("TG", "${keep.length()} notification(s) still pending — will retry")
            }
        }
    }

    private suspend fun read(ctx: Context): JSONArray {
        val p = ctx.outboxStore.data.first()
        return try { JSONArray(p[KEY] ?: "[]") } catch (e: Exception) { JSONArray() }
    }
}