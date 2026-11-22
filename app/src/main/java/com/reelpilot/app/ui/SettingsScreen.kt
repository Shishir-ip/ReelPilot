package com.reelpilot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
fun SettingsScreen(onOpenLogs: () -> Unit = {}) {
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

        // ---- Post to apps (multi-target) + flows
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Post to apps", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The same video is posted to every selected app, one after another. Instagram has built-in automation; other apps need a recorded tap flow (auto-clicker style).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val selected = SettingsRepo.parseTargets(settings.targets).toMutableSet()
                val flows = SettingsRepo.parseFlows(settings.flows)
                val known = listOf(
                    "com.instagram.android" to "Instagram",
                    "com.google.android.youtube" to "YouTube",
                    "com.facebook.katana" to "Facebook",
                    "com.zhiliaoapp.musically" to "TikTok",
                    "com.snapchat.android" to "Snapchat"
                )
                fun rebuildCsv(): String {
                    val customs = selected.filter { pkg -> known.none { it.first == pkg } }
                    return (known.map { it.first }.filter { it in selected } + customs)
                        .joinToString(",").ifBlank { "com.instagram.android" }
                }
                fun isInstalled(pkg: String): Boolean = try {
                    context.packageManager.getLaunchIntentForPackage(pkg) != null
                } catch (e: Exception) {
                    false
                }

                known.forEach { (pkg, label) ->
                    val installed = isInstalled(pkg)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = pkg in selected,
                            onCheckedChange = { on ->
                                if (on) selected.add(pkg) else if (selected.size > 1) selected.remove(pkg)
                                scope.launch { SettingsRepo.get(context).setTargets(rebuildCsv()) }
                            },
                            enabled = installed
                        )
                        Column(Modifier.weight(1f)) {
                            Text(label + if (installed) "" else " (not installed)", style = MaterialTheme.typography.bodyLarge)
                            val hasFlow = flows.containsKey(pkg) ||
                                (pkg == "com.instagram.android" && settings.manualFlow && settings.manualSteps.isNotBlank())
                            Text(
                                when {
                                    pkg == "com.instagram.android" && !hasFlow -> "Built-in automation ✓"
                                    hasFlow -> "Recorded flow (${flows[pkg]?.size ?: settings.manualSteps.count { it == '[' } - 1} steps)"
                                    else -> "Record a flow to enable posting"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (hasFlow || pkg == "com.instagram.android") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // Any other installed app
                var showAppDialog by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { showAppDialog = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("➕  Add any other app from the app list")
                }
                if (showAppDialog) {
                    val pm = context.packageManager
                    val apps = remember {
                        pm.queryIntentActivities(
                            android.content.Intent(android.content.Intent.ACTION_MAIN)
                                .addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0
                        ).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                            .filter { (pkg, _) -> pkg != context.packageName && known.none { it.first == pkg } }
                            .distinctBy { it.first }
                            .sortedBy { it.second.lowercase() }
                    }
                    AlertDialog(
                        onDismissRequest = { showAppDialog = false },
                        title = { Text("Pick an app") },
                        text = {
                            LazyColumn(Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(apps) { (pkg, label) ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (pkg in selected) selected.remove(pkg) else selected.add(pkg)
                                                scope.launch { SettingsRepo.get(context).setTargets(rebuildCsv()) }
                                                showAppDialog = false
                                            }
                                            .padding(8.dp)
                                    ) {
                                        Text(label, style = MaterialTheme.typography.bodyLarge)
                                        Spacer(Modifier.weight(1f))
                                        if (pkg in selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAppDialog = false }) { Text("Close") }
                        }
                    )
                }
                val customSelected = selected.filter { pkg -> known.none { it.first == pkg } }
                if (customSelected.isNotEmpty()) {
                    Text(
                        "Also posting to: " + customSelected.joinToString(", ") {
                            com.reelpilot.app.worker.PostWorker.appLabel(context, it)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))
                Text("Flows", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Each selected app (other than Instagram) needs a recorded flow. Pick a short video from the gallery as material, then record: the app opens that app, shares the video, and you position the tap markers.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                var pickedUri by remember { mutableStateOf<String?>(null) }
                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri -> if (uri != null) pickedUri = uri.toString() }

                var recordTarget by remember { mutableStateOf<String?>(null) }
                Text("Record flow for:", style = MaterialTheme.typography.bodyMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    SettingsRepo.parseTargets(settings.targets).forEach { pkg ->
                        FilterChip(
                            selected = recordTarget == pkg,
                            onClick = { recordTarget = pkg },
                            label = {
                                Text(
                                    com.reelpilot.app.worker.PostWorker.appLabel(context, pkg) +
                                        if (flows.containsKey(pkg)) " ✓" else "",
                                    maxLines = 1
                                )
                            }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { picker.launch("video/*") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (pickedUri == null) "🖼  Pick material video from gallery" else "🖼  Material video selected ✓", maxLines = 1) }

                Button(
                    onClick = {
                        val pkg = recordTarget
                        val uri = pickedUri
                        if (pkg == null) { toast("Select which app to record for first"); return@Button }
                        if (uri == null) { toast("Pick a material video first"); return@Button }
                        context.startActivity(
                            android.content.Intent(context, com.reelpilot.app.PostActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(com.reelpilot.app.PostActivity.EXTRA_URI, uri)
                                .putExtra(com.reelpilot.app.PostActivity.EXTRA_FLOW_SETUP, true)
                                .putExtra(com.reelpilot.app.PostActivity.EXTRA_TARGET, pkg)
                        )
                    },
                    enabled = pickedUri != null && recordTarget != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "🎙 Start recorder" + (recordTarget?.let { " for " + com.reelpilot.app.worker.PostWorker.appLabel(context, it) } ?: ""),
                        maxLines = 1
                    )
                }
                Text(
                    "In the recorder: ＋ adds a numbered marker — drag each one onto the button the app should tap, in order. Long-press removes a marker, ✔ Save. Markers are positions only — nothing is tapped until a real post runs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TimingSlider(
                    label = "Pause between flow steps",
                    value = settings.manualStepDelaySec,
                    range = 1f..15f,
                    steps = 13,
                    suffix = "s",
                    onChange = { scope.launch { SettingsRepo.get(context).setManualStepDelaySec(it) } }
                )
            }
        }

        // ---- Storage, folder watching & battery
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Folder, battery & cleanup", style = MaterialTheme.typography.titleMedium)

                // Watched folder
                Text("Watched folder", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pick a folder on the phone. Any video you copy into it (e.g. over USB) is auto-queued — each video only once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val folderName = remember(settings.watchFolderUri) {
                    try {
                        settings.watchFolderUri.takeIf { it.isNotBlank() }?.let {
                            androidx.documentfile.provider.DocumentFile.fromTreeUri(
                                context, android.net.Uri.parse(it)
                            )?.name
                        } ?: ""
                    } catch (e: Exception) { "" }
                }
                Text(
                    if (folderName.isNullOrBlank()) "No folder selected" else "📁 Watching: $folderName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (folderName.isNullOrBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.primary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    val treePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            scope.launch {
                                runCatching {
                                    context.contentResolver.takePersistableUriPermission(
                                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                                SettingsRepo.get(context).setWatchFolderUri(uri.toString())
                                toast("Folder set — new videos there will be auto-queued")
                            }
                        }
                    }
                    OutlinedButton(onClick = { treePicker.launch(null) }, modifier = Modifier.weight(1f)) {
                        Text("📁 Choose folder", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { scope.launch { SettingsRepo.get(context).setWatchFolderUri("") } },
                        enabled = settings.watchFolderUri.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Stop watching", maxLines = 1) }
                }

                // Battery alert
                TimingSlider(
                    label = "Low-battery bot alert" + if (settings.batteryAlertPct == 0) " (off)" else "",
                    value = settings.batteryAlertPct,
                    range = 0f..50f,
                    steps = 49,
                    suffix = "%",
                    onChange = { scope.launch { SettingsRepo.get(context).setBatteryAlertPct(it) } }
                )

                // Auto-delete
                TimingSlider(
                    label = "Auto-delete posted videos after" + if (settings.autoDeleteDays == 0) " (off)" else "",
                    value = settings.autoDeleteDays,
                    range = 0f..30f,
                    steps = 29,
                    suffix = if (settings.autoDeleteDays == 1) " day" else " days",
                    onChange = { scope.launch { SettingsRepo.get(context).setAutoDeleteDays(it) } }
                )
                Text(
                    "Only videos that were successfully posted are deleted — queued, downloading or failed videos are never touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    com.reelpilot.app.worker.PostWorker.scheduleOneTime(context)
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

        OutlinedButton(
            onClick = onOpenLogs,
            modifier = Modifier.fillMaxWidth()
        ) { Text("🧾 Activity logs") }

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
