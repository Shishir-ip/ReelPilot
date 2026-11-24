package com.reelpilot.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.CaptionApi
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.worker.PostWorker
import kotlinx.coroutines.launch

/**
 * Caption management: the three generation methods (CaptAPI original caption,
 * CaptAPI summary + Gemini, SocialKit AI) with on/off switches and fallback
 * order, a key pool per service (unlimited keys, per-key limits, live usage
 * progress), generation prompts, and per-app caption rules.
 */
@Composable
fun CaptionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val settings by SettingsRepo.get(context).flow.collectAsState(initial = AppSettings())
    val socialApis = SettingsRepo.parseCaptionApis(settings.captionApis)
    val captApis = SettingsRepo.parseCaptionApis(settings.captApis)
    val geminiApis = SettingsRepo.parseCaptionApis(settings.geminiApis)
    val methodMap = SettingsRepo.parseStringMap(settings.captionMethods)
    val modes = SettingsRepo.parseStringMap(settings.captionModes)
    val manual = SettingsRepo.parseStringMap(settings.captionManual)
    val targets = SettingsRepo.parseTargets(settings.targets)

    fun toast(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    // Which pool the "Add key" dialog is currently editing (null = closed).
    var addPool by remember { mutableStateOf<String?>(null) }

    fun setMethod(method: String, on: Boolean) {
        scope.launch {
            val map = methodMap.toMutableMap()
            map[method] = if (on) "true" else "false"
            val root = org.json.JSONObject()
            map.forEach { (k, v) -> root.put(k, v) }
            SettingsRepo.get(context).setCaptionMethods(root.toString())
        }
    }

    fun removeKey(pool: String, api: CaptionApi) {
        scope.launch {
            val repo = SettingsRepo.get(context)
            val cur = when (pool) {
                "captapi" -> settings.captApis
                "gemini" -> settings.geminiApis
                else -> settings.captionApis
            }
            val rest = SettingsRepo.parseCaptionApis(cur).filter { it.name != api.name }
            val json = SettingsRepo.captionApisToJson(rest)
            when (pool) {
                "captapi" -> repo.setCaptApis(json)
                "gemini" -> repo.setGeminiApis(json)
                else -> repo.setCaptionApis(json)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Captions", style = MaterialTheme.typography.headlineMedium)

        // ---- Generation methods + fallback order
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Generation methods", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Tried in order 1 → 2 → 3. When a method runs out of credit or fails, the next enabled one takes over. Turn on all, any two, or just one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                MethodSwitch(
                    title = "1 · CaptAPI — original caption",
                    subtitle = "Fetches the video's own caption via CaptAPI and cleans it to one line. Costs 1–2 tokens per video.",
                    checked = methodMap["details"] != "false",
                    onChange = { setMethod("details", it) }
                )
                MethodSwitch(
                    title = "2 · CaptAPI summary + Gemini AI",
                    subtitle = "CaptAPI summarizes the video (4 tokens), then Gemini (${SettingsRepo.GEMINI_MODEL}) writes a fresh caption from your prompt.",
                    checked = methodMap["summary"] != "false",
                    onChange = { setMethod("summary", it) }
                )
                MethodSwitch(
                    title = "3 · SocialKit AI caption",
                    subtitle = "The original SocialKit summarizer with your custom prompt and response field.",
                    checked = methodMap["socialkit"] != "false",
                    onChange = { setMethod("socialkit", it) }
                )
            }
        }

        // ---- CaptAPI key pool
        PoolCard(
            title = "CaptAPI keys",
            subtitle = "One key works across Facebook, Instagram, TikTok, YouTube and X. Add as many as you like — each with its own token limit. Details cost 1–2 tokens, summarize 4 (YouTube 3). Keys are picked randomly among those with credit left.",
            unit = "tokens",
            apis = captApis,
            emptyText = "No CaptAPI keys yet — methods 1 and 2 will be skipped.",
            onAdd = { addPool = "captapi" },
            onRemove = { removeKey("captapi", it) }
        )

        // ---- Gemini key pool
        PoolCard(
            title = "Gemini API keys",
            subtitle = "Used by method 2 to write the caption from the summary. Add as many keys as you like, each with a request limit. 1 request = 1 caption.",
            unit = "requests",
            apis = geminiApis,
            emptyText = "No Gemini keys yet — method 2 will be skipped.",
            onAdd = { addPool = "gemini" },
            onRemove = { removeKey("gemini", it) }
        )

        // ---- Gemini prompt
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gemini prompt (method 2)", style = MaterialTheme.typography.titleMedium)
                var prompt by remember(settings.geminiPrompt) {
                    mutableStateOf(settings.geminiPrompt.ifBlank { SettingsRepo.DEFAULT_GEMINI_PROMPT })
                }
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            SettingsRepo.get(context).setGeminiPrompt(prompt)
                            toast("Gemini prompt saved ✓")
                        }
                    }) { Text("Save prompt") }
                    TextButton(onClick = {
                        scope.launch {
                            SettingsRepo.get(context).setGeminiPrompt("")
                            prompt = SettingsRepo.DEFAULT_GEMINI_PROMPT
                            toast("Prompt reset to default")
                        }
                    }) { Text("Reset to default") }
                }
            }
        }

        // ---- SocialKit key pool
        PoolCard(
            title = "SocialKit API keys",
            subtitle = "Each free key gets ~20 credits/month. The app picks randomly among keys with credit left and tracks usage.",
            unit = "credits",
            apis = socialApis,
            emptyText = "No keys yet — captions will fall back to the video's own text or the global default caption.",
            onAdd = { addPool = "socialkit" },
            onRemove = { removeKey("socialkit", it) }
        )

        // ---- SocialKit prompt
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SocialKit prompt (method 3)", style = MaterialTheme.typography.titleMedium)
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

    // ---- Add key dialog (shared by all three pools)
    addPool?.let { pool ->
        val (title, keyLabel, limitLabel, defaultLimit) = when (pool) {
            "captapi" -> listOf("Add CaptAPI key", "API key (capt_live_…)", "Token limit", "100")
            "gemini" -> listOf("Add Gemini API key", "Gemini API key", "Request limit", "100")
            else -> listOf("Add SocialKit access key", "Access key", "Monthly credit limit", "20")
        }
        var name by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var limit by remember { mutableStateOf(defaultLimit) }
        AlertDialog(
            onDismissRequest = { addPool = null },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name (e.g. Key 1)") }, singleLine = true
                    )
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text(keyLabel) }, singleLine = true
                    )
                    OutlinedTextField(
                        value = limit, onValueChange = { limit = it.filter { c -> c.isDigit() } },
                        label = { Text(limitLabel) }, singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (key.isBlank()) { toast("API key is required"); return@TextButton }
                    scope.launch {
                        val repo = SettingsRepo.get(context)
                        val cur = when (pool) {
                            "captapi" -> settings.captApis
                            "gemini" -> settings.geminiApis
                            else -> settings.captionApis
                        }
                        val list = SettingsRepo.parseCaptionApis(cur).toMutableList()
                        list.add(
                            CaptionApi(
                                name = name.ifBlank { "Key ${list.size + 1}" },
                                key = key.trim(),
                                limit = limit.toIntOrNull()?.coerceIn(1, 1000000) ?: defaultLimit.toInt(),
                                used = 0
                            )
                        )
                        val json = SettingsRepo.captionApisToJson(list)
                        when (pool) {
                            "captapi" -> repo.setCaptApis(json)
                            "gemini" -> repo.setGeminiApis(json)
                            else -> repo.setCaptionApis(json)
                        }
                        addPool = null
                        toast("Key added ✓")
                    }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addPool = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun MethodSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun PoolCard(
    title: String,
    subtitle: String,
    unit: String,
    apis: List<CaptionApi>,
    emptyText: String,
    onAdd: () -> Unit,
    onRemove: (CaptionApi) -> Unit
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = onAdd) { Text("➕ Add") }
            }
            if (apis.isEmpty()) {
                Text(
                    emptyText,
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
                                "${api.used}/${api.limit} $unit used — ${api.remaining} left",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onRemove(api) }) { Text("Remove") }
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
}