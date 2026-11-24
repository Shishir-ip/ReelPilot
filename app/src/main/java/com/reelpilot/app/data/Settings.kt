package com.reelpilot.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "reelpilot_settings")

data class AppSettings(
    val botToken: String = "",
    val allowedUsers: Set<String> = emptySet(),
    val allowAnyone: Boolean = true,
    val intervalHours: Int = 2,
    val caption: String = "",
    val autoPost: Boolean = true,
    val nextPostAt: Long = 0L,
    val lastChatId: Long = 0L,
    // Automation timing (seconds) — user-tunable in Settings
    val warmupSec: Int = 4,
    val igOpenWaitSec: Int = 30,
    val nextWaitSec: Int = 75,
    val shareWaitSec: Int = 30,
    val uploadGraceSec: Int = 120,
    // Manual flow (recorded tap steps) — fallback if Instagram redesigns
    val manualFlow: Boolean = false,
    val manualSteps: String = "",        // JSON [[x,y],...] normalized 0..1
    val manualStepDelaySec: Int = 3,
    // Battery / storage / housekeeping
    val batteryAlertPct: Int = 20,       // 0 = alerts off
    val autoDeleteDays: Int = 7,         // keep posted videos N days; 0 = never delete
    val watchFolderUri: String = "",     // SAF tree uri; empty = folder watching off
    // Multi-app posting
    val targets: String = "com.instagram.android",  // CSV of target packages
    val flows: String = "",              // JSON {"pkg": [steps...]} recorded tap flows
    // Captions (SocialKit generation + per-app rules)
    val captionApis: String = "",        // JSON [{"name","key","limit","used"}]
    val captionPrompt: String = "",      // blank = built-in default prompt
    val captionResponseField: String = "",  // blank = "banglaCaption"
    val captionModes: String = "",       // JSON {"pkg": "generated|manual|none"}
    val captionManual: String = "",      // JSON {"pkg": "manual text"}
    // CaptAPI + Gemini caption methods
    val captApis: String = "",           // JSON pool of CaptAPI keys (token limits)
    val geminiApis: String = "",         // JSON pool of Gemini API keys (request limits)
    val geminiPrompt: String = "",       // blank = built-in default Gemini prompt
    val scrapGeminiPrompt: String = "",  // blank = built-in default scrap-rewrite prompt
    val captionMethods: String = ""      // JSON {"details":"false",...}; blank = all on
)

class SettingsRepo(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("bot_token")
        val USERS = stringPreferencesKey("allowed_users")
        val ALLOW_ANYONE = booleanPreferencesKey("allow_anyone")
        val INTERVAL = intPreferencesKey("interval_hours")
        val CAPTION = stringPreferencesKey("caption")
        val AUTO_POST = booleanPreferencesKey("auto_post")
        val NEXT_POST_AT = longPreferencesKey("next_post_at")
        val LAST_CHAT_ID = longPreferencesKey("last_chat_id")
        val WARMUP = intPreferencesKey("warmup_sec")
        val IG_OPEN_WAIT = intPreferencesKey("ig_open_wait_sec")
        val NEXT_WAIT = intPreferencesKey("next_wait_sec")
        val SHARE_WAIT = intPreferencesKey("share_wait_sec")
        val UPLOAD_GRACE = intPreferencesKey("upload_grace_sec")
        val MANUAL_FLOW = booleanPreferencesKey("manual_flow")
        val MANUAL_STEPS = stringPreferencesKey("manual_steps")
        val MANUAL_STEP_DELAY = intPreferencesKey("manual_step_delay_sec")
        val BATTERY_ALERT = intPreferencesKey("battery_alert_pct")
        val AUTO_DELETE_DAYS = intPreferencesKey("auto_delete_days")
        val WATCH_FOLDER = stringPreferencesKey("watch_folder_uri")
        val TARGETS = stringPreferencesKey("targets")
        val FLOWS = stringPreferencesKey("flows")
        val CAPTION_APIS = stringPreferencesKey("caption_apis")
        val CAPTION_PROMPT = stringPreferencesKey("caption_prompt")
        val CAPTION_RESPONSE_FIELD = stringPreferencesKey("caption_response_field")
        val CAPTION_MODES = stringPreferencesKey("caption_modes")
        val CAPTION_MANUAL = stringPreferencesKey("caption_manual")
        val CAPT_APIS = stringPreferencesKey("capt_apis")
        val GEMINI_APIS = stringPreferencesKey("gemini_apis")
        val GEMINI_PROMPT = stringPreferencesKey("gemini_prompt")
        val SCRAP_GEMINI_PROMPT = stringPreferencesKey("scrap_gemini_prompt")
        val CAPTION_METHODS = stringPreferencesKey("caption_methods")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            botToken = p[Keys.TOKEN] ?: "",
            allowedUsers = (p[Keys.USERS] ?: "").split(",").mapNotNull {
                it.trim().takeIf { s -> s.isNotEmpty() }
            }.toSet(),
            allowAnyone = p[Keys.ALLOW_ANYONE] ?: true,
            intervalHours = p[Keys.INTERVAL] ?: 2,
            caption = p[Keys.CAPTION] ?: "",
            autoPost = p[Keys.AUTO_POST] ?: true,
            nextPostAt = p[Keys.NEXT_POST_AT] ?: 0L,
            lastChatId = p[Keys.LAST_CHAT_ID] ?: 0L,
            warmupSec = p[Keys.WARMUP] ?: 4,
            igOpenWaitSec = p[Keys.IG_OPEN_WAIT] ?: 30,
            nextWaitSec = p[Keys.NEXT_WAIT] ?: 75,
            shareWaitSec = p[Keys.SHARE_WAIT] ?: 30,
            uploadGraceSec = p[Keys.UPLOAD_GRACE] ?: 120,
            manualFlow = p[Keys.MANUAL_FLOW] ?: false,
            manualSteps = p[Keys.MANUAL_STEPS] ?: "",
            manualStepDelaySec = p[Keys.MANUAL_STEP_DELAY] ?: 3,
            batteryAlertPct = p[Keys.BATTERY_ALERT] ?: 20,
            autoDeleteDays = p[Keys.AUTO_DELETE_DAYS] ?: 7,
            watchFolderUri = p[Keys.WATCH_FOLDER] ?: "",
            targets = p[Keys.TARGETS] ?: "com.instagram.android",
            flows = p[Keys.FLOWS] ?: "",
            captionApis = p[Keys.CAPTION_APIS] ?: "",
            captionPrompt = p[Keys.CAPTION_PROMPT] ?: "",
            captionResponseField = p[Keys.CAPTION_RESPONSE_FIELD] ?: "",
            captionModes = p[Keys.CAPTION_MODES] ?: "",
            captionManual = p[Keys.CAPTION_MANUAL] ?: "",
            captApis = p[Keys.CAPT_APIS] ?: "",
            geminiApis = p[Keys.GEMINI_APIS] ?: "",
            geminiPrompt = p[Keys.GEMINI_PROMPT] ?: "",
            scrapGeminiPrompt = p[Keys.SCRAP_GEMINI_PROMPT] ?: "",
            captionMethods = p[Keys.CAPTION_METHODS] ?: ""
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setBotToken(v: String) = context.dataStore.edit { it[Keys.TOKEN] = v.trim() }
    suspend fun setAllowAnyone(v: Boolean) = context.dataStore.edit { it[Keys.ALLOW_ANYONE] = v }
    suspend fun setAllowedUsers(v: Set<String>) =
        context.dataStore.edit { it[Keys.USERS] = v.joinToString(",") }
    suspend fun addAllowedUser(id: String) {
        context.dataStore.edit { p ->
            val cur = (p[Keys.USERS] ?: "").split(",").mapNotNull { s -> s.trim().takeIf { it.isNotEmpty() } }
            if (id !in cur) p[Keys.USERS] = (cur + id).joinToString(",")
        }
    }
    suspend fun setIntervalHours(v: Int) = context.dataStore.edit { it[Keys.INTERVAL] = v.coerceIn(1, 72) }
    suspend fun setCaption(v: String) = context.dataStore.edit { it[Keys.CAPTION] = v }
    suspend fun setAutoPost(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_POST] = v }
    suspend fun setNextPostAt(v: Long) = context.dataStore.edit { it[Keys.NEXT_POST_AT] = v }
    suspend fun setLastChatId(v: Long) = context.dataStore.edit { it[Keys.LAST_CHAT_ID] = v }
    suspend fun setWarmupSec(v: Int) = context.dataStore.edit { it[Keys.WARMUP] = v.coerceIn(1, 20) }
    suspend fun setIgOpenWaitSec(v: Int) = context.dataStore.edit { it[Keys.IG_OPEN_WAIT] = v.coerceIn(10, 90) }
    suspend fun setNextWaitSec(v: Int) = context.dataStore.edit { it[Keys.NEXT_WAIT] = v.coerceIn(30, 180) }
    suspend fun setShareWaitSec(v: Int) = context.dataStore.edit { it[Keys.SHARE_WAIT] = v.coerceIn(10, 120) }
    suspend fun setUploadGraceSec(v: Int) = context.dataStore.edit { it[Keys.UPLOAD_GRACE] = v.coerceIn(30, 600) }

    /** Put every automation timing back to its default. */
    suspend fun resetTiming() = context.dataStore.edit {
        it[Keys.WARMUP] = 4
        it[Keys.IG_OPEN_WAIT] = 30
        it[Keys.NEXT_WAIT] = 75
        it[Keys.SHARE_WAIT] = 30
        it[Keys.UPLOAD_GRACE] = 120
    }

    suspend fun setManualFlow(v: Boolean) = context.dataStore.edit { it[Keys.MANUAL_FLOW] = v }
    suspend fun setManualSteps(json: String) = context.dataStore.edit { it[Keys.MANUAL_STEPS] = json }
    suspend fun setManualStepDelaySec(v: Int) =
        context.dataStore.edit { it[Keys.MANUAL_STEP_DELAY] = v.coerceIn(1, 20) }
    suspend fun setBatteryAlertPct(v: Int) = context.dataStore.edit { it[Keys.BATTERY_ALERT] = v.coerceIn(0, 50) }
    suspend fun setAutoDeleteDays(v: Int) = context.dataStore.edit { it[Keys.AUTO_DELETE_DAYS] = v.coerceIn(0, 30) }
    suspend fun setWatchFolderUri(v: String) = context.dataStore.edit { it[Keys.WATCH_FOLDER] = v }
    suspend fun setTargets(csv: String) = context.dataStore.edit { it[Keys.TARGETS] = csv }
    suspend fun setFlows(json: String) = context.dataStore.edit { it[Keys.FLOWS] = json }
    suspend fun setCaptionApis(json: String) = context.dataStore.edit { it[Keys.CAPTION_APIS] = json }
    suspend fun setCaptionPrompt(v: String) = context.dataStore.edit { it[Keys.CAPTION_PROMPT] = v }
    suspend fun setCaptionResponseField(v: String) = context.dataStore.edit { it[Keys.CAPTION_RESPONSE_FIELD] = v }
    suspend fun setCaptionModes(json: String) = context.dataStore.edit { it[Keys.CAPTION_MODES] = json }
    suspend fun setCaptionManual(json: String) = context.dataStore.edit { it[Keys.CAPTION_MANUAL] = json }
    suspend fun setCaptApis(json: String) = context.dataStore.edit { it[Keys.CAPT_APIS] = json }
    suspend fun setGeminiApis(json: String) = context.dataStore.edit { it[Keys.GEMINI_APIS] = json }
    suspend fun setGeminiPrompt(v: String) = context.dataStore.edit { it[Keys.GEMINI_PROMPT] = v }
    suspend fun setScrapGeminiPrompt(v: String) = context.dataStore.edit { it[Keys.SCRAP_GEMINI_PROMPT] = v }
    suspend fun setCaptionMethods(json: String) = context.dataStore.edit { it[Keys.CAPTION_METHODS] = json }

    /** Save (or overwrite) the recorded flow for one target app. */
    suspend fun setFlow(pkg: String, steps: List<com.reelpilot.app.util.FlowStep>) {
        val map = parseFlows(current().flows).toMutableMap()
        map[pkg] = steps
        setFlows(flowsToJson(map))
    }

    suspend fun clearFlow(pkg: String) {
        val map = parseFlows(current().flows).toMutableMap()
        map.remove(pkg)
        setFlows(flowsToJson(map))
    }

    private fun flowsToJson(map: Map<String, List<com.reelpilot.app.util.FlowStep>>): String {
        val root = org.json.JSONObject()
        map.forEach { (pkg, steps) ->
            val arr = org.json.JSONArray()
            steps.forEach { s ->
                if (s.isText) {
                    arr.put(org.json.JSONObject().apply {
                        put("t", 1); put("x", s.x.toDouble()); put("y", s.y.toDouble())
                    })
                } else {
                    arr.put(org.json.JSONArray(listOf(s.x.toDouble(), s.y.toDouble())))
                }
            }
            root.put(pkg, arr)
        }
        return root.toString()
    }

    companion object {
        const val INSTAGRAM = "com.instagram.android"
        val DEFAULT_PROMPT = "Based on the video transcript, write 1 short, catchy bangla caption in natural, conversational Bangla (not robotic or overly formal) that works across Reels, TikTok, and Shorts. Keep it 2–3 lines max with a strong hook, 1–2 emojis and 3 relevant hashtags."

        val DEFAULT_GEMINI_PROMPT = "Generate a catchy, scroll-stopping Bangla caption based on the video summary. Make it emotional, impactful and relatable. Keep it to 1–2 short sentences with exactly 2 relevant emojis and 3 relevant hashtags. Start with a strong hook. Return only the ready-to-post caption."

        val DEFAULT_SCRAP_GEMINI_PROMPT = "Below is the raw scraped caption of a video. Ignore page names, @handles, links, hashtags, credits and filler text. Rewrite it as ONE short, catchy, natural Bangla caption (1–2 sentences, under 200 characters) that captures the video's essence with a strong emotional hook. Do NOT copy the original word-for-word — write it fresh. End with exactly 2 relevant emojis and 3 relevant hashtags. Return only the ready-to-post caption."
        const val GEMINI_MODEL = "gemini-3.5-flash-lite"

        /** Caption methods in fallback order: scrap+Gemini first, verbatim next. */
        val METHOD_ORDER = listOf("scrapgemini", "builtin", "summary", "socialkit")

        /** Enabled methods, in fallback order. Blank setting = all enabled. */
        fun enabledMethods(s: AppSettings): List<String> {
            val map = parseStringMap(s.captionMethods)
            return METHOD_ORDER.filter { map[it] != "false" }
        }

        fun effectiveGeminiPrompt(s: AppSettings): String =
            s.geminiPrompt.ifBlank { DEFAULT_GEMINI_PROMPT }

        fun effectiveScrapGeminiPrompt(s: AppSettings): String =
            s.scrapGeminiPrompt.ifBlank { DEFAULT_SCRAP_GEMINI_PROMPT }

        fun parseCaptionApis(json: String): List<CaptionApi> = CaptionApi.parseList(json)

        fun captionApisToJson(apis: List<CaptionApi>): String = CaptionApi.toJson(apis)

        fun parseTargets(csv: String): List<String> =
            csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { listOf(INSTAGRAM) }.distinct()

        /** Effective prompt: user's custom one, or the sensible Bangla default. */
        fun effectivePrompt(s: AppSettings): String =
            s.captionPrompt.ifBlank {
                DEFAULT_PROMPT
            }

        fun effectiveResponseField(s: AppSettings): String =
            s.captionResponseField.ifBlank { "banglaCaption" }

        /** Caption mode for one app: "generated" (default), "manual" or "none". */
        fun captionModeFor(s: AppSettings, pkg: String): String =
            parseStringMap(s.captionModes)[pkg] ?: "generated"

        fun manualCaptionFor(s: AppSettings, pkg: String): String =
            parseStringMap(s.captionManual)[pkg] ?: ""

        /** Parse a flat JSON object of strings, e.g. {"pkg":"generated"} */
        fun parseStringMap(json: String): Map<String, String> = try {
            val o = org.json.JSONObject(json)
            val out = mutableMapOf<String, String>()
            val iter = o.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                out[k] = o.optString(k, "")
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }

        // Map of "pkg" -> ordered flow steps. Each step is a legacy (x, y) tap
        // array or a text step object that types the caption when replayed.
        fun parseFlows(json: String): Map<String, List<com.reelpilot.app.util.FlowStep>> = try {
            val o = org.json.JSONObject(json)
            val out = mutableMapOf<String, List<com.reelpilot.app.util.FlowStep>>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val a = o.getJSONArray(k)
                out[k] = (0 until a.length()).map { i ->
                    val el = a.get(i)
                    when (el) {
                        is org.json.JSONObject -> com.reelpilot.app.util.FlowStep(
                            true,
                            el.getDouble("x").toFloat(),
                            el.getDouble("y").toFloat()
                        )
                        else -> {
                            val s = el as org.json.JSONArray
                            com.reelpilot.app.util.FlowStep(false, s.getDouble(0).toFloat(), s.getDouble(1).toFloat())
                        }
                    }
                }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }

        @Volatile
        private var instance: SettingsRepo? = null

        fun get(context: Context): SettingsRepo =
            instance ?: synchronized(this) {
                instance ?: SettingsRepo(context.applicationContext).also { instance = it }
            }
    }
}
