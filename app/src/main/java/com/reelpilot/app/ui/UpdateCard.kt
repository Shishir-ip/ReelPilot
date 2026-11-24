package com.reelpilot.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * "Check for update" — reads the latest GitHub release, and when it is newer
 * than the installed version, downloads the APK with a live progress bar and
 * hands it to the system installer (install pop-up). No browser needed.
 */
@Composable
fun UpdateCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) { "?" }
    }

    var phase by remember { mutableStateOf("idle") } // idle|checking|downloading|ready|uptodate|error
    var progress by remember { mutableStateOf(0f) }
    var detail by remember { mutableStateOf("") }
    var apkFile by remember { mutableStateOf<File?>(null) }

    fun launchInstall(file: File) {
        // Android 8+: the user must allow "install unknown apps" for ReelPilot
        // once; send them to the toggle, then they tap Install again.
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            detail = "Allow 'install unknown apps' for ReelPilot, then tap Install update."
            phase = "ready"
            runCatching {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                detail = "Could not open installer: ${it.message?.take(120)}"
                phase = "error"
            }
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("App update", style = MaterialTheme.typography.titleMedium)
            Text(
                "Installed: v$currentVersion",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        scope.launch {
                            phase = "checking"
                            detail = "Contacting GitHub…"
                            progress = 0f
                            try {
                                val result = withContext(Dispatchers.IO) { checkAndDownload(context) { p, d ->
                                    progress = p
                                    detail = d
                                } }
                                when {
                                    result == null -> {
                                        phase = "uptodate"
                                        detail = "You're on the latest version (v$currentVersion)."
                                    }
                                    else -> {
                                        apkFile = result
                                        phase = "ready"
                                        detail = "Download complete — installing…"
                                        launchInstall(result)
                                    }
                                }
                            } catch (e: Exception) {
                                phase = "error"
                                detail = "Update failed: ${e.message?.take(140)}"
                                AppLog.log("UPD", "Update failed: ${e.message?.take(140)}")
                            }
                        }
                    },
                    enabled = phase != "checking" && phase != "downloading"
                ) { Text(if (phase == "checking" || phase == "downloading") "Working…" else "Check for update") }
                if (phase == "ready" && apkFile != null) {
                    Button(onClick = { launchInstall(apkFile!!) }) { Text("Install update") }
                }
            }
            if (phase == "downloading") {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (phase == "error") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private val http = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

/**
 * Returns the downloaded APK file when a newer release exists, or null when
 * already up to date. Reports progress (0..1) and a status line via [onProgress].
 */
private suspend fun checkAndDownload(
    context: android.content.Context,
    onProgress: (Float, String) -> Unit
): File? {
    val req = Request.Builder()
        .url("https://api.github.com/repos/Shishir-ip/ReelPilot/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .build()
    val release = http.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) throw RuntimeException("GitHub HTTP ${resp.code}")
        JSONObject(resp.body?.string() ?: "")
    }
    val tag = release.optString("tag_name", "")
    val current = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }
    if (!isNewer(tag, current)) return null

    val assets = release.optJSONArray("assets") ?: throw RuntimeException("Release has no assets")
    var url: String? = null
    var size = 0L
    for (i in 0 until assets.length()) {
        val a = assets.getJSONObject(i)
        if (a.optString("name", "").endsWith(".apk")) {
            url = a.optString("browser_download_url")
            size = a.optLong("size", 0)
            break
        }
    }
    if (url == null) throw RuntimeException("No APK in release $tag")

    onProgress(0f, "Downloading $tag…")
    val outDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    outDir.mkdirs()
    val file = File(outDir, "ReelPilot-$tag.apk")
    val dlReq = Request.Builder().url(url).build()
    http.newCall(dlReq).execute().use { resp ->
        if (!resp.isSuccessful) throw RuntimeException("Download HTTP ${resp.code}")
        val body = resp.body ?: throw RuntimeException("Empty download body")
        val total = if (size > 0) size else body.contentLength()
        var done = 0L
        body.byteStream().use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        onProgress(
                            (done.toFloat() / total).coerceIn(0f, 1f),
                            "Downloading $tag — ${done / 1_048_576} / ${total / 1_048_576} MB"
                        )
                    }
                }
            }
        }
    }
    AppLog.log("UPD", "Downloaded $tag (${file.length()} bytes)")
    return file
}

/** Numeric dot comparison: v2.3.0 > 2.2.0. */
private fun isNewer(remoteTag: String, current: String): Boolean {
    val r = remoteTag.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
    val c = current.split(".").map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(r.size, c.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = c.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}