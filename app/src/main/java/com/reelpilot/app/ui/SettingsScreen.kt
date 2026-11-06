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
import androidx.compose.material3.Text
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
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.telegram.TgPollService
import com.reelpilot.app.util.AppLog
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

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Accept links from anyone", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Any Telegram account that messages the bot can queue posts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.Switch(
                checked = anyone,
                onCheckedChange = { anyone = it }
            )
        }

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

        Button(
            onClick = {
                scope.launch {
                    SettingsRepo.get(context).setBotToken(token)
                    SettingsRepo.get(context).setCaption(caption)
                    SettingsRepo.get(context).setIntervalHours(interval)
                    SettingsRepo.get(context).setAllowAnyone(anyone)
                    if (token.isNotBlank()) TgPollService.start(context)
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
