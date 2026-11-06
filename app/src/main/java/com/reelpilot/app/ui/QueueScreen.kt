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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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

@Composable
fun QueueScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.get(context).queueDao() }
    val items by dao.all().collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Queue", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                scope.launch {
                    dao.clearPosted()
                    // Reset the interval clock so the next downloaded item
                    // posts immediately, and the one after follows the interval.
                    SettingsRepo.get(context).setNextPostAt(0)
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
                                PostWorker.scheduleOneTime(context, forceNow = true)
                            }
                        },
                        onDelete = { scope.launch { dao.delete(item.id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueCard(item: QueueItem, onRetry: () -> Unit, onDelete: () -> Unit) {
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
