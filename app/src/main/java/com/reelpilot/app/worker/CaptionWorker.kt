package com.reelpilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.CaptionApi
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
 * (https://www.socialkit.dev/). Runs in parallel with the download using the
 * ORIGINAL link (works even if the download later fails), and the result is
 * stored on the queue item. At posting time the caption is typed into every
 * selected app whose caption mode is "generated".
 *
 * API keys are pooled: each generation rotates to the next key with monthly
 * credit left, marks keys exhausted on credit errors, and tracks the credits
 * every successful request reports. Failed calls are free (per SocialKit).
 */
object CaptionGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)  // summarization can take a while
        .build()

    /** SocialKit summarize endpoints. Twitter/LinkedIn are not supported. */
    fun platformFor(url: String): String? = when {
        url.contains("facebook.com", true) || url.contains("fb.watch", true) -> "facebook"
        url.contains("youtube.com", true) || url.contains("youtu.be", true) -> "youtube"
        url.contains("tiktok.com", true) -> "tiktok"
        url.contains("instagram.com", true) -> "instagram"
        else -> null
    }

    /** Collapses the generated text to one clean line: caption + hashtags only. */
    fun oneLine(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    private suspend fun saveApis(context: Context, apis: List<CaptionApi>) {
        SettingsRepo.get(context).setCaptionApis(CaptionApi.toJson(apis))
    }

    /** Picks an untried key with monthly credit left, most credit first. */
    private fun pickKey(apis: List<CaptionApi>, tried: Set<CaptionApi>): CaptionApi? =
        apis.filter { it.key.isNotBlank() && it !in tried && it.remaining > 0 }
            .maxByOrNull { it.remaining }

    private suspend fun markExhausted(context: Context, api: CaptionApi) {
        val repo = SettingsRepo.get(context)
        val apis = CaptionApi.parseList(repo.current().captionApis).toMutableList()
        val idx = apis.indexOfFirst { it.name == api.name && it.key == api.key }
        if (idx >= 0) apis[idx] = apis[idx].copy(used = apis[idx].limit)
        saveApis(context, apis)
    }

    private suspend fun addUsed(context: Context, api: CaptionApi, credits: Int) {
        val repo = SettingsRepo.get(context)
        val apis = CaptionApi.parseList(repo.current().captionApis).toMutableList()
        val idx = apis.indexOfFirst { it.name == api.name && it.key == api.key }
        if (idx >= 0) apis[idx] = apis[idx].copy(used = apis[idx].used + credits)
        saveApis(context, apis)
    }

    /**
     * Generates the caption for one queue item. Safe to call twice — skips
     * if already done, failed, or not applicable.
     */
    suspend fun generate(context: Context, itemId: Long): Unit = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val dao = AppDatabase.get(ctx).queueDao()
        var item = dao.byId(itemId) ?: return@withContext
        if (item.source != "link" || item.url.isBlank()) return@withContext
        if (item.captionStatus == "done" || item.captionStatus == "skipped") return@withContext

        val s = SettingsRepo.get(ctx).current()
        var apis = CaptionApi.parseList(s.captionApis)
        if (apis.none { it.key.isNotBlank() && it.remaining > 0 }) {
            dao.update(item.copy(captionStatus = "skipped"))
            AppLog.log("CAP", "No caption API keys with credit left — using default caption")
            return@withContext
        }

        val platform = platformFor(item.url)
        if (platform == null) {
            dao.update(item.copy(captionStatus = "skipped"))
            AppLog.log("CAP", "No SocialKit endpoint for ${item.url.take(60)} — using default caption")
            return@withContext
        }

        dao.update(item.copy(captionStatus = "pending"))
        item = dao.byId(itemId) ?: return@withContext

        val tried = mutableSetOf<CaptionApi>()
        var lastError = ""

        while (true) {
            val api = pickKey(apis, tried) ?: break
            tried.add(api)
            try {
                val prompt = SettingsRepo.effectivePrompt(s)
                val field = SettingsRepo.effectiveResponseField(s)
                val url = "https://api.socialkit.dev/$platform/summarize" +
                    "?access_key=" + URLEncoder.encode(api.key, "UTF-8") +
                    "&url=" + URLEncoder.encode(item.url, "UTF-8") +
                    "&custom_prompt=" + URLEncoder.encode(prompt, "UTF-8")

                AppLog.log("CAP", "Generating caption via key '${api.name}' ($platform)…")
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("x-access-key", api.key)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.code == 403) {
                        // Invalid/revoked key — mark it exhausted so the pool moves on.
                        AppLog.log("CAP", "Key '${api.name}' rejected (403)")
                        markExhausted(ctx, api)
                        lastError = "key '${api.name}' was rejected (invalid or revoked)"
                        return@use
                    }
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(120)}")
                    val json = org.json.JSONObject(body)
                    if (!json.optBoolean("success", false)) {
                        val err = json.optString("error", "API returned success=false")
                        if (err.contains("credit", true) || err.contains("limit", true) ||
                            err.contains("exhaust", true)
                        ) {
                            markExhausted(ctx, api)
                            lastError = "key '${api.name}' is out of credits"
                            return@use
                        }
                        throw RuntimeException(err)
                    }
                    val data = json.getJSONObject("data")
                    // The caption lands in the custom response field configured in
                    // the SocialKit dashboard (default "banglaCaption"); fall back
                    // to the plain summary when the field is absent.
                    val raw = data.optString(field, "").trim()
                        .ifBlank { data.optString("summary", "").trim() }
                    if (raw.isBlank()) throw RuntimeException("Response had no caption field '$field'")
                    val credits = data.optInt("creditsUsed", 1)
                    addUsed(ctx, api, credits)

                    val caption = oneLine(raw)
                    dao.update(item.copy(generatedCaption = caption, captionStatus = "done"))
                    AppLog.log("CAP", "Caption ready ($credits credits, key '${api.name}')")
                    TelegramClient(s.botToken).sendMessage(
                        item.chatId,
                        "✍️ Caption generated: ${item.title.take(60)}\n\n$caption\n\n💸 $credits credit(s) used — key '${api.name}'"
                    )
                    return@withContext
                }
            } catch (e: Exception) {
                // Failed calls are free per SocialKit — just try the next key.
                lastError = e.message?.take(150) ?: "unknown error"
                AppLog.log("CAP", "Key '${api.name}' failed: $lastError")
            }
            // Refresh the pool (the monthly reset may have kicked in).
            apis = CaptionApi.parseList(SettingsRepo.get(ctx).current().captionApis)
        }

        dao.update(item.copy(captionStatus = "failed"))
        TelegramClient(s.botToken).sendMessage(
            item.chatId,
            "⚠️ Caption generation failed (${lastError.take(120)}).\nThe video will post with its own/default caption."
        )
        AppLog.log("CAP", "All keys failed — using default caption")
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
                ExistingWorkPolicy.APPEND,
                OneTimeWorkRequestBuilder<CaptionWorker>()
                    .setInputData(workDataOf("itemId" to itemId))
                    .build()
            )
        }
    }
}
