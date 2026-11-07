package com.reelpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.QueueItem
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.telegram.TgPollService
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.worker.QueueScheduler
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by SettingsRepo.get(context).flow.collectAsState(initial = AppSettings())

    var token by remember(settings.botToken) { mutableStateOf(settings.botToken) }
    var caption by remember(settings.caption) { mutableStateOf(settings.caption) }
    var interval by remember(settings.intervalHours) { mutableStateOf(settings.intervalHours) }
    var anyone by remember(settings.allowAnyone) { mutableStateOf(settings.allowAnyone) }
    var testLink by remember { mutableStateOf("") }

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Telegram bot token") },
            placeholder = { Text("123456:ABC-DEF…  from @BotFather") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text("Default caption") },
            placeholder = { Text("Used when a queued video has no caption") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )

        // ---- Test upload
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Test upload", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Downloads the link and posts it immediately — bypasses the queue interval. Results arrive in your bot chat. Use a short video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = testLink,
                    onValueChange = { testLink = it },
                    label = { Text("Video link to test") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Button(
                    onClick = {
                        scope.launch {
                            val s = SettingsRepo.get(context).current()
                            if (testLink.isBlank()) { toast("Paste a link first"); return@launch }
                            if (s.botToken.isBlank()) { toast("Save your bot token first"); return@launch }
                            val dao = AppDatabase.get(context).queueDao()
                            val id = dao.insert(
                                QueueItem(
                                    source = "link",
                                    url = testLink.trim(),
                                    title = "[TEST] ${testLink.trim().take(60)}",
                                    caption = "Test post from ReelPilot 🤖",
                                    chatId = s.lastChatId
                                )
                            )
                            WorkManager.getInstance(context).enqueue(
                                OneTimeWorkRequestBuilder<com.reelpilot.app.worker.DownloadWorker>()
                                    .setInputData(workDataOf("itemId" to id))
                                    .build()
                            )
                            testLink = ""
                            toast("Test queued — watch the bot chat and the Queue tab")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = settings.botToken.isNotBlank()
                ) { Text("▶ Test download & post now") }
            }
        }

        // ---- Interval
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Posting interval", style = MaterialTheme.typography.titleMedium)
                Text(
                    "One reel is posted every $interval hour${if (interval > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1, 2, 3, 4, 6).forEach { h ->
                        FilterChip(
                            selected = interval == h,
                            onClick = { interval = h },
                            label = { Text("${h}h") }
                        )
                    }
                }
                Slider(
                    value = interval.toFloat(),
                    onValueChange = { interval = it.toInt() },
                    valueRange = 1f..48f,
                    steps = 46
                )
            }
        }

        // ---- Accept anyone
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Accept links from anyone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Any Telegram account that messages the bot can queue posts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = anyone, onCheckedChange = { anyone = it })
        }

        // ---- Advanced timing
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation timing", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "How long each posting step is allowed to take. Saved automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            SettingsRepo.get(context).resetTiming()
                            toast("Timing reset to defaults")
                        }
                    }) { Text("Reset") }
                }

                TimingSlider(
                    label = "Instagram warm-up pause",
                    value = settings.warmupSec,
                    range = 1f..20f,
                    steps = 18,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setWarmupSec(it) } }
                )
                TimingSlider(
                    label = "Wait for Instagram to open",
                    value = settings.igOpenWaitSec,
                    range = 10f..90f,
                    steps = 79,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setIgOpenWaitSec(it) } }
                )
                TimingSlider(
                    label = "Wait for the Next button",
                    value = settings.nextWaitSec,
                    range = 30f..180f,
                    steps = 149,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setNextWaitSec(it) } }
                )
                TimingSlider(
                    label = "Wait for the Share button",
                    value = settings.shareWaitSec,
                    range = 10f..120f,
                    steps = 109,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setShareWaitSec(it) } }
                )
                TimingSlider(
                    label = "Upload finish wait before closing Instagram",
                    value = settings.uploadGraceSec,
                    range = 30f..600f,
                    steps = 56,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setUploadGraceSec(it) } }
                )
            }
        }

        Button(
            onClick = {
                scope.launch {
                    SettingsRepo.get(context).setBotToken(token)
                    SettingsRepo.get(context).setCaption(caption)
                    SettingsRepo.get(context).setIntervalHours(interval)
                    SettingsRepo.get(context).setAllowAnyone(anyone)
                    if (token.isNotBlank()) TgPollService.start(context)
                    // Interval changed → re-slot every waiting video.
                    QueueScheduler.reschedule(context)
                    com.reelpilot.app.worker.PostWorker.scheduleOneTime(context, expedited = true)
                    snackbar.showSnackbar("Saved ✓")
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save settings") }

        OutlinedButton(
            onClick = {
                scope.launch {
                    try {
                        val username = TelegramClient(token).validate()
                        snackbar.showSnackbar("Bot @$username connected ✓")
                        AppLog.log("TG", "Validated bot @$username")
                    } catch (e: Exception) {
                        snackbar.showSnackbar("Failed: ${e.message?.take(120)}")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = token.isNotBlank()
        ) { Text("Test bot connection") }

        SnackbarHost(snackbar)
    }
}

@Composable
private fun TimingSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    suffix: String,
    onChange: (Int) -> Unit
) {
    Column {
        Text("$label: ${value}$suffix", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range,
            steps = steps
        )
    }
}
