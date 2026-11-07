package com.reelpilot.app.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.QueueScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    dao.clearPosted()
                    // Reset the clock so the next downloaded item posts immediately.
                    SettingsRepo.get(context).setNextPostAt(0)
                    QueueScheduler.reschedule(context)
                    PostWorker.scheduleOneTime(context, forceNow = true)
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
                        onRetry = {
                            scope.launch {
                                dao.retry(item.id)
                                QueueScheduler.reschedule(context)
                                PostWorker.scheduleOneTime(context, forceNow = true)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                dao.delete(item.id)
                                // Later items shift up into the freed slot.
                                QueueScheduler.reschedule(context)
                                PostWorker.scheduleOneTime(context)
                            }
                        },
                        onEditTime = { editItem = item }
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
}

@Composable
private fun QueueCard(
    item: QueueItem,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onEditTime: () -> Unit
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

            if (item.status == Status.FAILED || item.status == Status.POSTED) {
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (item.status == Status.FAILED) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Retry")
                        }
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    }
                }
            }
        }
    }
}
