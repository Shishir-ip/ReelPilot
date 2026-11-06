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
    val intervalHours: Int = 2,
    val caption: String = "",
    val autoPost: Boolean = true,
    val nextPostAt: Long = 0L
)

class SettingsRepo(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("bot_token")
        val USERS = stringPreferencesKey("allowed_users")
        val INTERVAL = intPreferencesKey("interval_hours")
        val CAPTION = stringPreferencesKey("caption")
        val AUTO_POST = booleanPreferencesKey("auto_post")
        val NEXT_POST_AT = longPreferencesKey("next_post_at")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            botToken = p[Keys.TOKEN] ?: "",
            allowedUsers = (p[Keys.USERS] ?: "").split(",").mapNotNull {
                it.trim().takeIf { s -> s.isNotEmpty() }
            }.toSet(),
            intervalHours = p[Keys.INTERVAL] ?: 2,
            caption = p[Keys.CAPTION] ?: "",
            autoPost = p[Keys.AUTO_POST] ?: true,
            nextPostAt = p[Keys.NEXT_POST_AT] ?: 0L
        )
    }

    suspend fun current(): AppSettings = flow.first()

    suspend fun setBotToken(v: String) = context.dataStore.edit { it[Keys.TOKEN] = v.trim() }
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

    companion object {
        @Volatile
        private var instance: SettingsRepo? = null

        fun get(context: Context): SettingsRepo =
            instance ?: synchronized(this) {
                instance ?: SettingsRepo(context.applicationContext).also { instance = it }
            }
    }
}
