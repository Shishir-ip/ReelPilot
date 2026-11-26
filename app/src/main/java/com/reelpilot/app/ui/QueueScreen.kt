package com.reelpilot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.telegram.NotifyOutbox
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.util.Format
import com.reelpilot.app.worker.DownloadWorker
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.QueueScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Adds a video picked from the gallery straight into the queue at the next
 * serial position. It's already on the phone, so no download step — it goes
 * READY and takes the next upload slot.
 */
private suspend fun addGalleryVideo(context: android.content.Context, uri: android.net.Uri) {
    try {
        val s = SettingsRepo.get(context).current()
        val dao = AppDatabase.get(context).queueDao()

        val name = context.contentResolver.query(
            uri, arrayOf(android.provider.MediaStore.Video.Media.DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "gallery_video"

        val durMs = try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(context, uri)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            r.release()
            ms
        } catch (e: Exception) { 0 }

        val item = QueueItem(
            source = "gallery",
            url = uri.toString(),
            title = name,
            // Local videos post with their file name as the caption —
            // no AI generation (there is no link to fetch one from).
            caption = name.substringBeforeLast('.').ifBlank { name },
            chatId = s.lastChatId,
            status = Status.READY,
            progress = 100,
            localUri = uri.toString()
        )
        dao.insert(item)
        QueueScheduler.reschedule(context)
        PostWorker.scheduleOneTime(context)

        val pos = dao.activeItems().indexOfFirst { it.title == name } + 1
        val dur = if (durMs > 0) " (⏱ ${Format.duration(durMs)})" else ""
        if (s.lastChatId > 0 && s.botToken.isNotBlank()) {
            TelegramClient(s.botToken).sendMessage(
                s.lastChatId,
                "📥 Queued (#$pos): $name$dur"
            )
        }
        AppLog.log("QUEUE", "Gallery video added: $name")
    } catch (e: Exception) {
        AppLog.log("QUEUE", "Gallery add failed: ${e.message}")
    }
}

/**
 * Rebuilds the whole waiting queue from a chosen start date+time:
 *  - oldest video first (bottom of the list), then one per interval;
 *  - FAILED videos are included (download failures re-download first);
 *  - "already posted to X app(s)" progress is wiped, so every video posts
 *    to ALL selected apps again;
 *  - nothing is force-posted — the first upload happens at the chosen time.
 */
private suspend fun reconstructQueue(context: android.content.Context, startAt: Long) {
    val dao = AppDatabase.get(context).queueDao()
    val s = SettingsRepo.get(context).current()
    val intervalMs = s.intervalHours * 60 * 60 * 1000L
    val candidates = dao.activeItems()
        .filter { it.status in listOf(Status.READY, Status.POSTING, Status.FAILED) && !it.title.startsWith("[FLOW]") }
        .sortedBy { it.createdAt }
    if (candidates.isEmpty()) {
        AppLog.log("QUEUE", "Reconstruct: nothing to rebuild")
        return
    }
    var redownloads = 0
    candidates.forEachIndexed { i, item ->
        val slot = startAt + i * intervalMs
        if (item.status == Status.FAILED && item.localUri == null) {
            // Download never finished — fetch the video again, keep its slot.
            dao.requeue(item.id)
            dao.setPostAt(item.id, slot)
            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                "reelpilot_dl",
                androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                androidx.work.OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setInputData(androidx.work.workDataOf("itemId" to item.id))
                    .build()
            )
            redownloads++
        } else {
            dao.reconstructItem(item.id, slot)
        }
    }
    // New downloads after this rebuild flow after the last rebuilt slot.
    SettingsRepo.get(context).setNextPostAt(startAt + candidates.size * intervalMs)
    PostWorker.scheduleOneTime(context)
    val first = SimpleDateFormat("EEE, MMM d HH:mm", Locale.getDefault()).format(Date(startAt))
    AppLog.log("QUEUE", "Reconstructed ${candidates.size} video(s) from $first (every ${s.intervalHours}h)")
    if (s.lastChatId > 0 && s.botToken.isNotBlank()) {
        NotifyOutbox.enqueue(
            context, s.lastChatId,
            "🛠 Queue reconstructed: ${candidates.size} video(s)\n▶️ First: $first\n⏰ Then every ${s.intervalHours}h (oldest video first)\n🔄 App-post progress reset — each video posts to all selected apps again." +
                if (redownloads > 0) "\n⬇️ $redownloads failed download(s) will re-download first." else ""
        )
    }
}

private fun statusColor(status: String): Color = when (status) {
    Status.QUEUED -> Color(0xFF9E9E9E)
    Status.DOWNLOADING -> Color(0xFF2196F3)
    Status.READY -> Color(0xFFFF9800)
    Status.POSTING -> Color(0xFF9C27B0)
    Status.POSTED -> Color(0xFF4CAF50)
    Status.FAILED -> Color(0xFFF44336)
    else -> Color.Gray
}

private fun formatSlot(postAt: Long): String {
    val cal = Calendar.getInstance()
    val cal2 = Calendar.getInstance().apply { timeInMillis = postAt }
    val sameDay = cal.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR) &&
        cal.get(Calendar.YEAR) == cal2.get(Calendar.YEAR)
    return if (sameDay)
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(postAt))
    else
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(postAt))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).queueDao() }
    val items by dao.all().collectAsState(initial = emptyList())

    var editItem by remember { mutableStateOf<QueueItem?>(null) }
    var retryItem by remember { mutableStateOf<QueueItem?>(null) }
    var showReconstruct by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            var pickedUri by remember { mutableStateOf<String?>(null) }
            val galleryPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri ->
                if (uri != null) {
                    pickedUri = uri.toString()
                    scope.launch { addGalleryVideo(context, uri) }
                }
            }
            TextButton(onClick = { galleryPicker.launch("video/*") }) { Text("➕ Add") }
            TextButton(onClick = { showReconstruct = true }) { Text("🛠 Reconstruct") }
            TextButton(onClick = {
                scope.launch {
                    // Only removes posted history — never touches the waiting
                    // schedule and never triggers an upload.
                    dao.clearPosted()
                }
            }) { Text("Clear posted") }
        }

        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Nothing queued yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Send a video link to your bot in Telegram",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 0.dp, 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    QueueCard(
                        item = item,
                        onRetry = { retryItem = item },
                        onDelete = {
                            scope.launch {
                                dao.delete(item.id)
                                // Later items shift up into the freed slot.
                                QueueScheduler.reschedule(context)
                                PostWorker.scheduleOneTime(context)
                            }
                        },
                        onEditTime = { editItem = item },
                        onGenerate = {
                            scope.launch {
                                dao.setCaptionStatus(item.id, "pending")
                                com.reelpilot.app.worker.CaptionWorker.enqueue(context, item.id)
                            }
                        }
                    )
                }
            }
        }
    }

    editItem?.let { item ->
        val cal = Calendar.getInstance().apply {
            if (item.postAt > 0) timeInMillis = item.postAt
        }
        val tp = rememberTimePickerState(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("Upload time") },
            text = { TimePicker(state = tp) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val newTime = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, tp.hour)
                            set(Calendar.MINUTE, tp.minute)
                            set(Calendar.SECOND, 0)
                            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                        }.timeInMillis
                        dao.setPostAt(item.id, newTime)
                        QueueScheduler.reschedule(context)
                        PostWorker.scheduleOneTime(context)
                    }
                    editItem = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            dao.setPostAt(item.id, 0)   // back to automatic slot
                            QueueScheduler.reschedule(context)
                            PostWorker.scheduleOneTime(context)
                        }
                        editItem = null
                    }) { Text("Auto") }
                    TextButton(onClick = { editItem = null }) { Text("Cancel") }
                }
            }
        )
    }

    // Retry: pick date+time (default now). Only the failed apps are retried
    // (doneTargets is kept) and no other video's schedule is touched.
    retryItem?.let { item ->
        DateTimeDialog(
            title = "Retry upload",
            confirmLabel = "Retry",
            onDismiss = { retryItem = null },
            onConfirm = { at ->
                scope.launch {
                    dao.retry(item.id)
                    dao.setPostAt(item.id, at)
                    PostWorker.scheduleOneTime(context)
                    AppLog.log("QUEUE", "Retry scheduled: ${item.title.take(50)} at ${SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(at))}")
                }
                retryItem = null
            }
        )
    }

    // Reconstruct: rebuild the whole waiting queue from a chosen date+time.
    if (showReconstruct) {
        DateTimeDialog(
            title = "Reconstruct queue",
            confirmLabel = "Rebuild",
            onDismiss = { showReconstruct = false },
            onConfirm = { at ->
                scope.launch { reconstructQueue(context, at) }
                showReconstruct = false
            }
        )
    }
}

/**
 * Date + time picker in one dialog. The date opens the calendar picker;
 * the clock below sets the time. Defaults to the current date and time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeDialog(
    title: String,
    confirmLabel: String,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val localNow = remember { Calendar.getInstance() }
    // DatePicker works in UTC millis — start from UTC midnight of today's
    // local date so the calendar opens on the right day in any timezone.
    val initialUtcMidnight = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(localNow.get(Calendar.YEAR), localNow.get(Calendar.MONTH), localNow.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    var dateMillis by remember { mutableStateOf(initialUtcMidnight) }
    var showCalendar by remember { mutableStateOf(false) }
    val timeState = rememberTimePickerState(
        initialHour = localNow.get(Calendar.HOUR_OF_DAY),
        initialMinute = localNow.get(Calendar.MINUTE),
        is24Hour = true
    )
    val dateLabel = remember(dateMillis) {
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMillis }
        SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(
            Calendar.getInstance().apply {
                set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), 12, 0, 0)
            }.time
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                TextButton(onClick = { showCalendar = true }) { Text("📅 $dateLabel") }
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateMillis }
                val cal = Calendar.getInstance().apply {
                    set(utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH), timeState.hour, timeState.minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onConfirm(cal.timeInMillis)
            }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showCalendar) {
        val dpState = rememberDatePickerState(initialSelectedDateMillis = dateMillis)
        DatePickerDialog(
            onDismissRequest = { showCalendar = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { dateMillis = it }
                    showCalendar = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showCalendar = false }) { Text("Cancel") } }
        ) { DatePicker(state = dpState) }
    }
}

@Composable
private fun QueueCard(
    item: QueueItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onEditTime: () -> Unit,
    onGenerate: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(item.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    item.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor(item.status)
                )
            }

            // Caption that will post with this video (tap to expand).
            if (item.captionStatus == "pending") {
                Text(
                    "✍️ Generating caption…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val shownCaption = item.generatedCaption.ifBlank { item.caption }
            if (shownCaption.isNotBlank()) {
                var expanded by remember(item.id) { mutableStateOf(false) }
                Text(
                    "✍️ $shownCaption",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                )
            }

            // Multi-app progress
            val doneApps = item.doneTargets.split(',').count { it.isNotBlank() }
            if (doneApps > 0 && item.status != Status.POSTED) {
                Text(
                    "🌍 $doneApps app(s) already posted — remaining apps post next",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Scheduled upload time
            when {
                (item.status == Status.READY || item.status == Status.POSTING) && item.postAt > 0 -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "📅 ${formatSlot(item.postAt)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (item.status == Status.READY) {
                            IconButton(onClick = onEditTime) {
                                Icon(
                                    Icons.Rounded.EditCalendar,
                                    contentDescription = "Change upload time",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
                item.status == Status.QUEUED || item.status == Status.DOWNLOADING -> {
                    Text(
                        "⏳ upload time: after download",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.status == Status.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { item.progress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // Actions: generate caption (link videos), retry, remove — always available.
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (item.source == "link") {
                    TextButton(
                        onClick = onGenerate,
                        enabled = item.captionStatus != "pending"
                    ) {
                        Text(if (item.generatedCaption.isBlank()) "✍️ Generate" else "✍️ Regen")
                    }
                }
                if (item.status == Status.FAILED) {
                    TextButton(onClick = onRetry) { Text("↻ Retry") }
                }
                TextButton(onClick = onDelete) { Text("🗑 Remove") }
            }
        }
    }
}
