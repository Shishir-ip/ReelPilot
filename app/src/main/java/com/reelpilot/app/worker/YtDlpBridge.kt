package com.reelpilot.app.worker

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.chaquo.python.PyObject
import com.chaquo.python.android.AndroidPlatform
import com.reelpilot.app.python.ProgressBridge
import com.reelpilot.app.util.AppLog
import org.json.JSONObject
import java.io.File

/** Runs yt-dlp (via Chaquopy) and saves the video into the public gallery. */
object YtDlpBridge {

    /** Download `url`, save into MediaStore, return content:// uri string. */
    fun downloadAndSave(context: Context, url: String): Triple<String, String, Int> {
        if (!com.chaquo.python.Python.isStarted()) {
            com.chaquo.python.Python.start(AndroidPlatform(context))
        }
        val py = com.chaquo.python.Python.getInstance()
        val module: PyObject = py.getModule("reelpilot_dl")

        val tmpDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
            .apply { mkdirs() }

        ProgressBridge.reset()
        val resultJson = module.callAttr("download", url, tmpDir.absolutePath).toString()
        val result = JSONObject(resultJson)
        if (!result.optBoolean("ok", false)) {
            throw RuntimeException(result.optString("error", "Download failed"))
        }

        val filePath = result.getString("filepath")
        val title = result.optString("title", "video")
        val size = result.optInt("size", 0)
        val uri = saveToGallery(context, File(filePath), sanitize(title))
        File(filePath).delete()
        AppLog.log("YT", "Saved to gallery: $title")
        return Triple(uri, title, size)
    }

    /**
     * Fetches the video's own caption/description straight from the link via
     * yt-dlp metadata extraction — free, no external API, no download.
     * Returns null when the platform blocks anonymous access (login wall).
     */
    fun fetchCaption(context: Context, url: String): String? {
        if (!com.chaquo.python.Python.isStarted()) {
            com.chaquo.python.Python.start(AndroidPlatform(context))
        }
        val py = com.chaquo.python.Python.getInstance()
        val module: PyObject = py.getModule("reelpilot_dl")
        val resultJson = module.callAttr("fetch_caption", url).toString()
        val result = JSONObject(resultJson)
        if (!result.optBoolean("ok", false)) {
            AppLog.log("YT", "Built-in caption fetch failed: ${result.optString("error").take(140)}")
            return null
        }
        return result.optString("caption", "").trim().ifBlank { null }
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { "reelpilot_video" }

    /** Copies a local video file into the shared MediaStore so Instagram's picker sees it. */
    fun saveToGallery(context: Context, file: File, displayName: String): String {
        val resolver = context.contentResolver

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$displayName.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReelPilot")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw RuntimeException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw RuntimeException("Could not open MediaStore stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } else {
            // Legacy path (API 26-28): write into public Movies dir, then scan.
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ReelPilot"
            ).apply { mkdirs() }
            val dest = File(dir, "$displayName.mp4")
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
            return Uri.fromFile(dest).toString()
        }
    }

    /** Saves a Telegram-hosted file into the gallery. */
    fun saveDownloadedFile(context: Context, localFile: File, displayName: String): String =
        saveToGallery(context, localFile, sanitize(displayName))

    /** Copies an arbitrary stream into the shared MediaStore (watched-folder adds). */
    fun saveStreamToGallery(context: Context, input: java.io.InputStream, displayName: String, mime: String): String {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReelPilot")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: throw RuntimeException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out -> input.use { it.copyTo(out) } }
                ?: throw RuntimeException("Could not open MediaStore stream")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri.toString()
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "ReelPilot"
            ).apply { mkdirs() }
            val dest = File(dir, displayName)
            input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            return Uri.fromFile(dest).toString()
        }
    }
}
