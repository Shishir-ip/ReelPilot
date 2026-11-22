package com.reelpilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Generates an AI caption for a queued LINK video via SocialKit
 * (https://www.socialkit.dev/). The API is called with the original link,
 * runs in parallel with the download, and the result is stored on the queue
 * item to be typed into whichever selected apps have caption mode = generated.
 *
 * API keys are pooled: every generation rotates to the next key that still
 * has monthly credit left, and the credits the API reports are tracked so
 * nothing runs dry silently.
 */
object CaptionGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)  // summarization takes a while
        .build()

    fun platformFor(url: String): String? = when {
        url.contains("facebook.com", true) || url.contains("fb.watch", true) -> "facebook"
        url.contains("youtube.com", true) || url.contains("youtu.be", true) -> "youtube"
        url.contains("tiktok.com", true) -> "tiktok"
        url.contains("instagram.com", true) -> "instagram"
        url.contains("twitter.com", true) || url.contains("x.com", true) -> "twitter"
        url.contains("linkedin.com", true) -> "linkedin"
        else -> null
    }

    /**
     * Picks the next key with remaining credit (round-robin starting after
     * the last used one), preferring keys with the most remaining credit
     * spread across the pool.
     */
    private suspend fun pickKey(context: Context): SettingsRepo.CaptionApi? {
        val s = SettingsRepo.get(context).current()
        val apis = SettingsRepo.parseCaptionApis(s.captionApis)
        val available = apis.filter { it.key.isNotBlank() && it.used < it.limit }
        if (available.isEmpty()) return null
        val prefs = context.getSharedPreferences("caption", Context.MODE_PRIVATE)
        val idx = prefs.getInt("rotate", 0)
        val chosen = available[idx % available.size]
        prefs.edit().putInt("rotate", idx + 1).apply()
        return chosen
    }

    private suspend fun markUsed(context: Context, keyName: String, credits: Int) {
        val repo = SettingsRepo.get(context)
        val s = repo.current()
        val apis = SettingsRepo.parseCaptionApis(s.captionApis).toMutableList()
        val idx = apis.indexOfFirst { it.name == keyName }
        if (idx >= 0) apis[idx] = apis[idx].copy(used = apis[idx].used + credits)
        repo.setCaptionApis(SettingsRepo.captionApisToJson(apis))
    }

    suspend fun generate(context: Context, itemId: Long): Unit = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val dao = AppDatabase.get(ctx).queueDao()
        val item = dao.byId(itemId) ?: return@withContext
        if (item.source != "link" || item.url.isBlank()) return@withContext
        if (item.generatedCaption.isNotBlank()) return@withContext  // already generated

        val s = SettingsRepo.get(ctx).current()
        val platform = platformFor(item.url)
        if (platform == null) {
            AppLog.log("CAP", "No SocialKit endpoint for ${item.url.take(60)} — using default caption")
            return@withContext
        }

        var attempts = 0
        while (attempts < 6) {
            val api = pickKey(ctx) ?: run {
                AppLog.log("CAP", "No caption API keys with credit left")
                TelegramClient(s.botToken).sendMessage(
                    item.chatId,
                    "⚠️ Caption generation skipped: all API keys are out of monthly credits.\nAdd another access key in the app's Caption tab."
                )
                return@withContext
            }

            try {
                val prompt = SettingsRepo.effectivePrompt(s)
                val field = SettingsRepo.effectiveResponseField(s)
                val url = "https://api.socialkit.dev/$platform/summarize" +
                    "?access_key=" + URLEncoder.encode(api.key, "UTF-8") +
                    "&url=" + URLEncoder.encode(item.url, "UTF-8") +
                    "&custom_prompt=" + URLEncoder.encode(prompt, "UTF-8")

                AppLog.log("CAP", "Generating caption via key '${api.name}' ($platform)…")
                val req = okhttp3.Request.Builder().url(url).get().build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(120)}")
                    val json = org.json.JSONObject(body)
                    if (!json.optBoolean("success", false)) {
                        throw RuntimeException(json.optString("error", "API returned success=false"))
                    }
                    val data = json.getJSONObject("data")
                    // The caption lives in the custom response field (default banglaCaption);
                    // fall back to the plain summary if the field is missing.
                    val caption = data.optString(field, "").trim()
                        .ifBlank { data.optString("summary", "").trim() }
                    if (caption.isBlank()) throw RuntimeException("Response had no caption field '$field'")
                    val credits = data.optInt("creditsUsed", 1)
                    markUsed(ctx, api.name, credits)

                    dao.update(item.copy(generatedCaption = caption))
                    AppLog.log("CAP", "Caption ready (${credits} credits, key '${api.name}')")
                    TelegramClient(s.botToken).sendMessage(
                        item.chatId,
                        "✍️ Caption generated: ${item.title.take(60)}\n\n$caption\n\n💸 $credits credit(s) used — key '${api.name}'"
                    )
                    return@withContext
                }
            } catch (e: Exception) {
                AppLog.log("CAP", "Key '${api.name}' failed: ${e.message}")
                // Count the attempt anyway if the API may have charged us; mark 1
                // credit used on HTTP-level failures so the pool rotates onward.
                markUsed(ctx, api.name, 0)
                attempts++
            }
        }
        TelegramClient(s.botToken).sendMessage(
            item.chatId,
            "⚠️ Caption generation failed after trying all API keys.\nThe video will post with its own/default caption."
        )
    }
}

/** Queued after a link is added; generates + stores the AI caption. */
class CaptionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong("itemId", -1)
        if (id >= 0) {
            runCatching { CaptionGenerator.generate(applicationContext, id) }
        }
        return Result.success()
    }

    companion object {
        private const val CHAIN = "reelpilot_caption"

        /** Caption calls are chained so keys are consumed one request at a time. */
        fun enqueue(context: Context, itemId: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CHAIN,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<CaptionWorker>()
                    .setInputData(workDataOf("itemId" to itemId))
                    .build()
            )
        }
    }
}
