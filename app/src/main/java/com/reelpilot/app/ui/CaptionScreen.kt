package com.reelpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.CaptionApi
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.worker.PostWorker
import kotlinx.coroutines.launch

/**
 * Caption management: SocialKit API key pool (with monthly credit limits and
 * usage tracking), the generation prompt, and per-app caption rules
 * (generated / manual text / none).
 */
@Composable
fun CaptionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by SettingsRepo.get(context).flow.collectAsState(initial = AppSettings())
    val apis = SettingsRepo.parseCaptionApis(settings.captionApis)
    val modes = SettingsRepo.parseStringMap(settings.captionModes)
    val manual = SettingsRepo.parseStringMap(settings.captionManual)
    val targets = SettingsRepo.parseTargets(settings.targets)

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    var showAddApi by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Captions", style = MaterialTheme.typography.headlineMedium)

        // ---- API pool
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("SocialKit API keys", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Each free key gets ~20 credits/month. The app rotates to the next key with credit left and tracks usage.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { showAddApi = true }) { Text("➕ Add") }
                }

                if (apis.isEmpty()) {
                    Text(
                        "No keys yet — captions will fall back to the video's own text or the global default caption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                apis.forEach { api ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(api.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${api.used}/${api.limit} credits used — ${api.limit - api.used} left",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    val rest = apis.filter { it.name != api.name }
                                    SettingsRepo.get(context).setCaptionApis(SettingsRepo.captionApisToJson(rest))
                                }
                            }) { Text("Remove") }
                        }
                        LinearProgressIndicator(
                            progress = {
                                if (api.limit > 0) (api.used.toFloat() / api.limit).coerceIn(0f, 1f) else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // ---- Prompt
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Generation prompt", style = MaterialTheme.typography.titleMedium)
                var prompt by remember(settings.captionPrompt) { mutableStateOf(settings.captionPrompt.ifBlank { SettingsRepo.DEFAULT_PROMPT }) }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    placeholder = { Text("e.g. write 1 short catchy bangla caption…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            SettingsRepo.get(context).setCaptionPrompt(prompt)
                            toast("Prompt saved ✓")
                        }
                    }) { Text("Save prompt") }
                    TextButton(onClick = {
                        scope.launch {
                            SettingsRepo.get(context).setCaptionPrompt("")
                            prompt = SettingsRepo.DEFAULT_PROMPT
                            toast("Prompt reset to default")
                        }
                    }) { Text("Reset to default") }
                }

                var field by remember(settings.captionResponseField) { mutableStateOf(settings.captionResponseField) }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("Response field name (SocialKit custom field)") },
                    placeholder = { Text("banglaCaption") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                TextButton(onClick = {
                    scope.launch {
                        SettingsRepo.get(context).setCaptionResponseField(field)
                        toast("Response field saved ✓")
                    }
                }) { Text("Save response field") }
            }
        }

        // ---- Per-app caption rules
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Where captions are applied", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose how each posting app gets its caption: the generated AI caption, a fixed manual text, or nothing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                targets.forEach { pkg ->
                    val label = PostWorker.appLabel(context, pkg)
                    val mode = modes[pkg] ?: "generated"
                    Column(Modifier.fillMaxWidth()) {
                        Text(label, style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("generated", "manual", "none").forEach { m ->
                                FilterChip(
                                    selected = mode == m,
                                    onClick = {
                                        scope.launch {
                                            val map = modes.toMutableMap()
                                            map[pkg] = m
                                            val root = org.json.JSONObject()
                                            map.forEach { (k, v) -> root.put(k, v) }
                                            SettingsRepo.get(context).setCaptionModes(root.toString())
                                        }
                                    },
                                    label = {
                                        Text(
                                            when (m) {
                                                "generated" -> "🤖 Generated"
                                                "manual" -> "✏️ Manual"
                                                else -> "🚫 None"
                                            }
                                        )
                                    }
                                )
                            }
                        }
                        if (mode == "manual") {
                            var text by remember(settings.captionManual) { mutableStateOf(manual[pkg] ?: "") }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text("Manual caption for $label") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                            TextButton(onClick = {
                                scope.launch {
                                    val m2 = manual.toMutableMap()
                                    m2[pkg] = text
                                    val root = org.json.JSONObject()
                                    m2.forEach { (k, v) -> root.put(k, v) }
                                    SettingsRepo.get(context).setCaptionManual(root.toString())
                                    toast("Manual caption saved for $label ✓")
                                }
                            }) { Text("Save manual caption") }
                        }
                    }
                }
                if (targets.isEmpty()) {
                    Text("No target apps selected — set them in Settings → Post to apps.")
                }
            }
        }

        SnackbarHost(snackbar)
    }

    // ---- Add API dialog
    if (showAddApi) {
        var name by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var limit by remember { mutableStateOf("20") }
        AlertDialog(
            onDismissRequest = { showAddApi = false },
            title = { Text("Add SocialKit access key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name (e.g. Key 1)") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text("Access key") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = limit, onValueChange = { limit = it.filter { c -> c.isDigit() } },
                        label = { Text("Monthly credit limit") }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (key.isBlank()) { toast("Access key is required"); return@TextButton }
                    scope.launch {
                        val list = SettingsRepo.parseCaptionApis(settings.captionApis).toMutableList()
                        list.add(
                            CaptionApi(
                                name = name.ifBlank { "Key ${list.size + 1}" },
                                key = key.trim(),
                                limit = limit.toIntOrNull()?.coerceIn(1, 100000) ?: 20,
                                used = 0
                            )
                        )
                        SettingsRepo.get(context).setCaptionApis(SettingsRepo.captionApisToJson(list))
                        showAddApi = false
                        toast("Key added ✓")
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddApi = false }) { Text("Cancel") }
            }
        )
    }
}
