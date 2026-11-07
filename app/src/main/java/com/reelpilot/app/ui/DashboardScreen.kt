package com.reelpilot.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reelpilot.app.accessibility.ReelAccessibilityService
import com.reelpilot.app.data.AppSettings
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TgPollService
import com.reelpilot.app.worker.PostWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by SettingsRepo.get(context).flow.collectAsState(initial = AppSettings())
    val dao = remember { AppDatabase.get(context).queueDao() }
    val ready by dao.readyCountFlow().collectAsState(initial = 0)
    val items by dao.all().collectAsState(initial = emptyList())

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val pending = items.count { it.status in com.reelpilot.app.data.Status.active }
    val failed = items.count { it.status == com.reelpilot.app.data.Status.FAILED }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ReelPilot", style = MaterialTheme.typography.headlineMedium)

        // ---- Status card
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic posting", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (settings.nextPostAt > now)
                                "Next reel at " + SimpleDateFormat("HH:mm", Locale.getDefault())
                                    .format(Date(settings.nextPostAt))
                            else if (ready > 0)
                                "Ready — posts on the next check"
                            else
                                "Waiting for downloads",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.autoPost,
                        onCheckedChange = { on ->
                            scope.launch {
                                SettingsRepo.get(context).setAutoPost(on)
                                if (on && settings.botToken.isNotBlank()) TgPollService.start(context)
                            }
                        }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("📥 $ready ready", style = MaterialTheme.typography.bodyMedium)
                    Text("⏳ $pending pending", style = MaterialTheme.typography.bodyMedium)
                    if (failed > 0) Text("❌ $failed failed", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { PostWorker.scheduleOneTime(context, forceNow = true) },
                    enabled = ready > 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Post next reel now") }
            }
        }

        // ---- Setup checklist
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Setup", style = MaterialTheme.typography.titleMedium)
                CheckRow(
                    ok = settings.botToken.isNotBlank(),
                    icon = Icons.Rounded.Key,
                    title = "Telegram bot token",
                    hint = if (settings.botToken.isBlank()) "Add it in Settings" else "Configured"
                ) { onOpenSettings() }

                CheckRow(
                    ok = ReelAccessibilityService.isServiceEnabled(context),
                    icon = Icons.Rounded.Accessibility,
                    title = "Accessibility permission",
                    hint = "Lets ReelPilot tap Instagram for you"
                ) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }

                CheckRow(
                    ok = Settings.canDrawOverlays(context),
                    icon = Icons.Rounded.Layers,
                    title = "Display over other apps",
                    hint = "Needed to post while the screen is off"
                ) {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:com.reelpilot.app")
                        )
                    )
                }

                CheckRow(
                    ok = (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                        .isIgnoringBatteryOptimizations(context.packageName),
                    icon = Icons.Rounded.BatteryAlert,
                    title = "Battery optimization off",
                    hint = "Makes the bot reply instantly with the screen off"
                ) {
                    runCatching {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:com.reelpilot.app"))
                        )
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    ok: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.height(0.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (ok) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = "done", tint = Color(0xFF4CAF50))
        } else {
            OutlinedButton(onClick = onClick) { Text("Fix") }
        }
    }
}
