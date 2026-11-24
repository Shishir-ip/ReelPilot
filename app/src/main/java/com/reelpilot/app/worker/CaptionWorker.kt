package com.reelpilot.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.CaptionApi
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Generates a caption for a queued LINK video. Runs in parallel with the
 * download using the ORIGINAL link; the result is stored on the queue item
 * and typed into every app whose caption mode is "generated".
 *
 * Four methods, tried in fallback order (each can be toggled in the Caption
 * tab). When one runs out of credit or fails, the next enabled one takes over:
 *
 *  1. "scrapgemini" — free yt-dlp scrape of the video's own caption, then
 *     Gemini (gemini-3.5-flash-lite) REWRITES it into a short, fresh Bangla
 *     caption (drops page names, handles, junk hashtags). Uses the Gemini pool.
 *  2. "builtin"  — free, keyless: the bundled yt-dlp reads the video's own
 *     caption/description straight from the link, verbatim (one line).
 *  3. "summary"  — CaptAPI /v1/{platform}/summarize (4 credits on FB/IG/TikTok,
 *     3 on YouTube) → the summary is sent to Gemini with the user's prompt.
 *  4. "socialkit" — the original SocialKit summarize integration.
 *
 * Every method has its own key pool (add unlimited keys, each with a limit).
 * Keys are picked RANDOMLY among those with credit left; usage is tracked per
 * key (CaptAPI charges are read from the X-Captapi-Credits response header).
 * Failed calls are free on both CaptAPI and SocialKit.
 */
object CaptionGenerator {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)  // summarization can take a while
        .build()

    /** Platform slug for a link. Twitter/LinkedIn have no SocialKit endpoint. */
    fun platformFor(url: String): String? = when {
        url.contains("facebook.com", true) || url.contains("fb.watch", true) -> "facebook"
        url.contains("youtube.com", true) || url.contains("youtu.be", true) -> "youtube"
        url.contains("tiktok.com", true) -> "tiktok"
        url.contains("instagram.com", true) -> "instagram"
        url.contains("twitter.com", true) || url.contains("//x.com/", true) ||
            url.contains("//www.x.com/", true) || url.contains("//mobile.x.com/", true) -> "twitter"
        else -> null
    }

    /** Collapses the generated text to one clean line: caption + hashtags only. */
    fun oneLine(raw: String): String =
        raw.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()

    fun methodLabel(method: String): String = when (method) {
        "scrapgemini" -> "Scraped caption + Gemini"
        "builtin" -> "Built-in script (yt-dlp)"
        "summary" -> "CaptAPI summary + Gemini"
        else -> "SocialKit AI"
    }

    /** True when at least one enabled method can run (builtin needs no keys). */
    fun anyMethodHasCredit(s: AppSettings): Boolean =
        SettingsRepo.enabledMethods(s).any { m ->
            when (m) {
                "builtin" -> true
                "scrapgemini" -> CaptionApi.parseList(s.geminiApis)
                    .any { it.key.isNotBlank() && it.remaining > 0 }
                "summary" -> CaptionApi.parseList(s.captApis)
                    .any { it.key.isNotBlank() && it.remaining >= 4 } &&
                    CaptionApi.parseList(s.geminiApis)
                        .any { it.key.isNotBlank() && it.remaining > 0 }
                else -> CaptionApi.parseList(s.captionApis)
                    .any { it.key.isNotBlank() && it.remaining > 0 }
            }
        }

    // ------------------------------------------------------------- key pools

    private enum class Pool { SOCIALKIT, CAPTAPI, GEMINI }

    private fun poolJson(s: AppSettings, pool: Pool): String = when (pool) {
        Pool.SOCIALKIT -> s.captionApis
        Pool.CAPTAPI -> s.captApis
        Pool.GEMINI -> s.geminiApis
    }

    private suspend fun mutatePool(
        ctx: Context,
        pool: Pool,
        api: CaptionApi,
        transform: (CaptionApi) -> CaptionApi
    ) {
        val repo = SettingsRepo.get(ctx)
        val apis = CaptionApi.parseList(poolJson(repo.current(), pool)).toMutableList()
        val idx = apis.indexOfFirst { it.name == api.name && it.key == api.key }
        if (idx >= 0) {
            apis[idx] = transform(apis[idx])
            val json = CaptionApi.toJson(apis)
            when (pool) {
                Pool.SOCIALKIT -> repo.setCaptionApis(json)
                Pool.CAPTAPI -> repo.setCaptApis(json)
                Pool.GEMINI -> repo.setGeminiApis(json)
            }
        }
    }

    private suspend fun markExhausted(ctx: Context, pool: Pool, api: CaptionApi) =
        mutatePool(ctx, pool, api) { it.copy(used = it.limit) }

    private suspend fun addUsed(ctx: Context, pool: Pool, api: CaptionApi, credits: Int) =
        mutatePool(ctx, pool, api) { it.copy(used = it.used + credits) }

    /** Random untried key with enough credit left (user asked for random rotation). */
    private fun pickRandom(apis: List<CaptionApi>, tried: Set<CaptionApi>, minCredit: Int): CaptionApi? =
        apis.filter { it.key.isNotBlank() && it !in tried && it.remaining >= minCredit }.randomOrNull()

    // ------------------------------------------------- CaptAPI endpoint map

    private fun summarizePath(platform: String, url: String): String? = when (platform) {
        "facebook", "instagram", "tiktok" -> "$platform/summarize"
        "youtube" -> if (url.contains("/shorts/", true)) "youtube/shorts/summarize"
        else "youtube/summarize"
        else -> null  // Twitter/X has no summarizer endpoint
    }

    /** Fallback cost when the X-Captapi-Credits header is missing. */
    private fun summarizeCost(platform: String): Int = if (platform == "youtube") 3 else 4

    // -------------------------------------------------------------- pipeline

    /**
     * Generates the caption for one queue item. Safe to call twice — skips
     * if already done, skipped, or not applicable.
     */
    suspend fun generate(context: Context, itemId: Long): Unit = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val dao = AppDatabase.get(ctx).queueDao()
        val item = dao.byId(itemId) ?: return@withContext
        if (item.source != "link" || item.url.isBlank()) return@withContext
        if (item.captionStatus == "done" || item.captionStatus == "skipped") return@withContext

        val s = SettingsRepo.get(ctx).current()
        val methods = SettingsRepo.enabledMethods(s)
        val platform = platformFor(item.url)
        if (methods.isEmpty() || (platform == null && "builtin" !in methods)) {
            dao.setCaptionStatus(itemId, "skipped")
            AppLog.log(
                "CAP",
                if (methods.isEmpty()) "All caption methods turned off — using default caption"
                else "No caption endpoint for ${item.url.take(60)} — using default caption"
            )
            return@withContext
        }

        dao.setCaptionStatus(itemId, "pending")

        for (method in methods) {
            val caption = try {
                when (method) {
                    "scrapgemini" -> tryScrapGemini(ctx, item)
                    "builtin" -> tryBuiltin(ctx, item)
                    "summary" -> trySummary(ctx, item, platform)
                    else -> trySocialKit(ctx, item, platform)
                }
            } catch (e: Exception) {
                AppLog.log("CAP", "Method '${methodLabel(method)}' crashed: ${e.message?.take(120)}")
                null
            }
            if (caption != null) {
                dao.setGeneratedCaption(itemId, caption)
                val label = methodLabel(method)
                AppLog.log("CAP", "Caption ready via $label")
                runCatching {
                    TelegramClient(s.botToken).sendMessage(
                        item.chatId,
                        "✍️ Caption generated ($label): ${item.title.take(60)}\n\n$caption"
                    )
                }
                return@withContext
            }
            AppLog.log("CAP", "Method '${methodLabel(method)}' unavailable — trying next")
        }

        dao.setCaptionStatus(itemId, "failed")
        runCatching {
            TelegramClient(s.botToken).sendMessage(
                item.chatId,
                "⚠️ Caption generation failed on all enabled methods.\nThe video will post with its own/default caption."
            )
        }
        AppLog.log("CAP", "All methods failed — using default caption")
    }

    // ------------------------------------- method 0: built-in yt-dlp script

    /** Free, keyless: read the video's own caption/description from the link. */
    private suspend fun tryBuiltin(ctx: Context, item: QueueItem): String? {
        AppLog.log("CAP", "Built-in caption fetch (yt-dlp) for ${item.url.take(60)}…")
        val raw = YtDlpBridge.fetchCaption(ctx, item.url)
        if (raw == null) {
            AppLog.log("CAP", "Built-in fetch got no caption (login wall or unsupported link)")
            return null
        }
        AppLog.log("CAP", "Built-in caption OK (free, no API)")
        return oneLine(raw)
    }

    // ------------------------------ method 1: scraped caption + Gemini rewrite

    /**
     * Free yt-dlp scrape of the video's own caption, then Gemini rewrites it
     * into a short, fresh Bangla caption (no page names / junk hashtags).
     */
    private suspend fun tryScrapGemini(ctx: Context, item: QueueItem): String? {
        AppLog.log("CAP", "Scraping caption for Gemini rewrite: ${item.url.take(60)}…")
        val raw = YtDlpBridge.fetchCaption(ctx, item.url)
        if (raw == null) {
            AppLog.log("CAP", "Scrape got no caption (login wall or unsupported link)")
            return null
        }
        val s = SettingsRepo.get(ctx).current()
        return geminiCaption(ctx, raw, SettingsRepo.effectiveScrapGeminiPrompt(s), "Scraped caption")
    }

    // ------------------------------------ method 2: CaptAPI summary + Gemini

    private suspend fun trySummary(ctx: Context, item: QueueItem, platform: String?): String? {
        if (platform == null) return null
        val path = summarizePath(platform, item.url)
        if (path == null) {
            AppLog.log("CAP", "No CaptAPI summarizer for $platform")
            return null
        }
        var apis = CaptionApi.parseList(SettingsRepo.get(ctx).current().captApis)
        if (apis.none { it.key.isNotBlank() && it.remaining >= 4 }) {
            AppLog.log("CAP", "No CaptAPI keys with 4+ tokens left for summarize")
            return null
        }
        val tried = mutableSetOf<CaptionApi>()
        var summary: String? = null
        while (summary == null) {
            val api = pickRandom(apis, tried, 4) ?: return null
            tried.add(api)
            try {
                AppLog.log("CAP", "CaptAPI summarize via '${api.name}' ($platform)…")
                val req = okhttp3.Request.Builder()
                    .url("https://api.captapi.com/v1/$path?url=" + URLEncoder.encode(item.url, "UTF-8"))
                    .header("Authorization", "Bearer ${api.key}")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    when {
                        resp.code == 401 || resp.code == 403 -> {
                            AppLog.log("CAP", "CaptAPI key '${api.name}' rejected (${resp.code})")
                            markExhausted(ctx, Pool.CAPTAPI, api)
                            return@use
                        }
                        resp.code == 402 -> {
                            AppLog.log("CAP", "CaptAPI key '${api.name}' out of credits (402)")
                            markExhausted(ctx, Pool.CAPTAPI, api)
                            return@use
                        }
                        resp.code == 400 || resp.code == 404 || resp.code == 422 -> {
                            AppLog.log("CAP", "CaptAPI summarize can't serve this link (HTTP ${resp.code})")
                            return null
                        }
                        !resp.isSuccessful -> throw RuntimeException("HTTP ${resp.code}: ${body.take(100)}")
                    }
                    val json = org.json.JSONObject(body)
                    if (!json.optBoolean("success", false)) {
                        val err = json.optJSONObject("error")?.optString("message")
                            ?: "API returned success=false"
                        if (err.contains("credit", true) || err.contains("limit", true) ||
                            err.contains("exhaust", true)
                        ) {
                            markExhausted(ctx, Pool.CAPTAPI, api)
                            return@use
                        }
                        throw RuntimeException(err)
                    }
                    val data = json.getJSONObject("data")
                    var raw = data.optString("summary", "").trim()
                    if (raw.isBlank()) {
                        val kp = data.optJSONArray("keyPoints")
                        if (kp != null && kp.length() > 0) {
                            raw = (0 until kp.length()).joinToString(". ") { kp.optString(it) }
                        }
                    }
                    if (raw.isBlank()) throw RuntimeException("Summarize response had no summary")
                    val cost = resp.header("X-Captapi-Credits")?.toIntOrNull() ?: summarizeCost(platform)
                    addUsed(ctx, Pool.CAPTAPI, api, cost)
                    AppLog.log("CAP", "CaptAPI summary OK ($cost tokens, key '${api.name}')")
                    summary = raw
                }
            } catch (e: Exception) {
                AppLog.log("CAP", "CaptAPI summarize key '${api.name}' failed: ${e.message?.take(120)}")
            }
            apis = CaptionApi.parseList(SettingsRepo.get(ctx).current().captApis)
        }
        return geminiCaption(ctx, summary!!, SettingsRepo.effectiveGeminiPrompt(SettingsRepo.get(ctx).current()), "Video summary")
    }

    /** Sends source text (summary or scraped caption) to Gemini with the given prompt. */
    private suspend fun geminiCaption(ctx: Context, sourceText: String, prompt: String, sourceLabel: String): String? {
        val s = SettingsRepo.get(ctx).current()
        var apis = CaptionApi.parseList(s.geminiApis)
        if (apis.none { it.key.isNotBlank() && it.remaining > 0 }) {
            AppLog.log("CAP", "No Gemini API keys with credit left")
            return null
        }
        val tried = mutableSetOf<CaptionApi>()
        while (true) {
            val api = pickRandom(apis, tried, 1) ?: return null
            tried.add(api)
            try {
                val payload = org.json.JSONObject().apply {
                    put("contents", org.json.JSONArray().put(org.json.JSONObject().apply {
                        put("parts", org.json.JSONArray().put(org.json.JSONObject().apply {
                            put("text", "$prompt\n\n$sourceLabel:\n$sourceText")
                        }))
                    }))
                }
                AppLog.log("CAP", "Gemini caption via '${api.name}' (${SettingsRepo.GEMINI_MODEL})…")
                val req = okhttp3.Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/${SettingsRepo.GEMINI_MODEL}:generateContent")
                    .header("x-goog-api-key", api.key)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    when {
                        resp.code == 401 || resp.code == 403 -> {
                            AppLog.log("CAP", "Gemini key '${api.name}' rejected (${resp.code})")
                            markExhausted(ctx, Pool.GEMINI, api)
                            return@use
                        }
                        resp.code == 400 -> {
                            if (body.contains("API_KEY_INVALID", true) || body.contains("API key not valid", true)) {
                                AppLog.log("CAP", "Gemini key '${api.name}' invalid (400) — trying next key")
                                markExhausted(ctx, Pool.GEMINI, api)
                                return@use
                            }
                            AppLog.log("CAP", "Gemini rejected the request (400): ${body.take(140)}")
                            return null
                        }
                        resp.code == 429 -> {
                            AppLog.log("CAP", "Gemini key '${api.name}' rate-limited — trying next key")
                            return@use
                        }
                        !resp.isSuccessful -> throw RuntimeException("HTTP ${resp.code}: ${body.take(100)}")
                    }
                    val json = org.json.JSONObject(body)
                    val text = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                        ?.optString("text", "")?.trim() ?: ""
                    if (text.isBlank()) throw RuntimeException("Gemini returned no text")
                    addUsed(ctx, Pool.GEMINI, api, 1)
                    AppLog.log("CAP", "Gemini caption OK (key '${api.name}')")
                    return oneLine(text)
                }
            } catch (e: Exception) {
                AppLog.log("CAP", "Gemini key '${api.name}' failed: ${e.message?.take(120)}")
            }
            apis = CaptionApi.parseList(SettingsRepo.get(ctx).current().geminiApis)
        }
    }

    // ------------------------------------------ method 3: SocialKit (legacy)

    private suspend fun trySocialKit(ctx: Context, item: QueueItem, platform: String?): String? {
        if (platform == null || platform == "twitter") return null  // SocialKit has no Twitter endpoint
        val s = SettingsRepo.get(ctx).current()
        var apis = CaptionApi.parseList(s.captionApis)
        if (apis.none { it.key.isNotBlank() && it.remaining > 0 }) {
            AppLog.log("CAP", "No SocialKit keys with credit left")
            return null
        }
        val tried = mutableSetOf<CaptionApi>()
        while (true) {
            val api = pickRandom(apis, tried, 1) ?: return null
            tried.add(api)
            try {
                val prompt = SettingsRepo.effectivePrompt(s)
                val field = SettingsRepo.effectiveResponseField(s)
                val url = "https://api.socialkit.dev/$platform/summarize" +
                    "?access_key=" + URLEncoder.encode(api.key, "UTF-8") +
                    "&url=" + URLEncoder.encode(item.url, "UTF-8") +
                    "&custom_prompt=" + URLEncoder.encode(prompt, "UTF-8")

                AppLog.log("CAP", "SocialKit caption via key '${api.name}' ($platform)…")
                val req = okhttp3.Request.Builder()
                    .url(url)
                    .header("x-access-key", api.key)
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (resp.code == 403) {
                        AppLog.log("CAP", "SocialKit key '${api.name}' rejected (403)")
                        markExhausted(ctx, Pool.SOCIALKIT, api)
                        return@use
                    }
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${body.take(120)}")
                    val json = org.json.JSONObject(body)
                    if (!json.optBoolean("success", false)) {
                        val err = json.optString("error", "API returned success=false")
                        if (err.contains("credit", true) || err.contains("limit", true) ||
                            err.contains("exhaust", true)
                        ) {
                            markExhausted(ctx, Pool.SOCIALKIT, api)
                            return@use
                        }
                        throw RuntimeException(err)
                    }
                    val data = json.getJSONObject("data")
                    val raw = data.optString(field, "").trim()
                        .ifBlank { data.optString("summary", "").trim() }
                    if (raw.isBlank()) throw RuntimeException("Response had no caption field '$field'")
                    val credits = data.optInt("creditsUsed", 1)
                    addUsed(ctx, Pool.SOCIALKIT, api, credits)
                    AppLog.log("CAP", "SocialKit caption OK ($credits credits, key '${api.name}')")
                    return oneLine(raw)
                }
            } catch (e: Exception) {
                // Failed calls are free per SocialKit — just try the next key.
                AppLog.log("CAP", "SocialKit key '${api.name}' failed: ${e.message?.take(120)}")
            }
            apis = CaptionApi.parseList(SettingsRepo.get(ctx).current().captionApis)
        }
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