package com.reelpilot.app.telegram

import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Minimal Telegram Bot API client (OkHttp + org.json, no extra deps). */
class TelegramClient(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()

    private fun api(method: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/$method")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) {
                throw IOException(json.optString("description", "Telegram error"))
            }
            json.getJSONObject("result")
        }
    }

    /** Long-poll for new updates. Returns raw update objects. */
    suspend fun getUpdates(offset: Long): JSONArray = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("offset", offset)
            .put("timeout", 25)
            .put("allowed_updates", JSONArray().put("message"))
        val req = Request.Builder()
            .url("https://api.telegram.org/bot$token/getUpdates")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw IOException("Empty response")
            val json = JSONObject(text)
            if (!json.optBoolean("ok", false)) throw IOException(json.optString("description", "Telegram error"))
            json.getJSONArray("result")
        }
    }

    suspend fun sendMessage(chatId: Long, text: String) = try {
        api("sendMessage", JSONObject().put("chat_id", chatId).put("text", text))
        Unit
    } catch (e: Exception) {
        AppLog.log("TG", "sendMessage failed: ${e.message}")
    }

    suspend fun validate(): String {
        val me = api("getMe", JSONObject())
        return me.optString("username", "?")
    }

    /** Resolve a file to a direct download URL (files up to 20 MB). */
    suspend fun fileUrl(fileId: String): String {
        val f = api("getFile", JSONObject().put("file_id", fileId))
        val path = f.optString("file_path") ?: throw IOException("file_path missing")
        return "https://api.telegram.org/file/bot$token/$path"
    }

    suspend fun downloadFile(url: String, dest: java.io.File): java.io.File = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            resp.body!!.byteStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        dest
    }
}
