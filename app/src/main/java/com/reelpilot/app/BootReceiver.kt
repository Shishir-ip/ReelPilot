package com.reelpilot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.WatchWorker
import kotlinx.coroutines.launch

/** After reboot, restore the periodic posting check (downloads resume from the DB). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PostWorker.schedulePeriodic(context)
            WatchWorker.schedule(context)
            // Re-arm the exact wake-up for the next waiting slot.
            com.reelpilot.app.worker.PostAlarm.schedule(context)
            // Restore the Telegram poller + notification outbox delivery.
            val pending = goAsync()
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    if (com.reelpilot.app.data.SettingsRepo.get(context).current().botToken.isNotBlank()) {
                        com.reelpilot.app.telegram.TgPollService.start(context)
                    }
                } catch (e: Exception) {
                    com.reelpilot.app.util.AppLog.log("TG", "Boot poller start failed: ${e.message}")
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
