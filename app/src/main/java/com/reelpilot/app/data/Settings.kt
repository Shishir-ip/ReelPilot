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
    val manualStepDelaySec: Int = 3
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
            manualStepDelaySec = p[Keys.MANUAL_STEP_DELAY] ?: 3
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

    companion object {
        @Volatile
        private var instance: SettingsRepo? = null

        fun get(context: Context): SettingsRepo =
            instance ?: synchronized(this) {
                instance ?: SettingsRepo(context.applicationContext).also { instance = it }
            }
    }
}
