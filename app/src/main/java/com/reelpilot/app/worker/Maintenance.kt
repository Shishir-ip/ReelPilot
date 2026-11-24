package com.reelpilot.app.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.net.Uri
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.telegram.TelegramClient
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.util.Format
import java.io.File

/** Housekeeping: auto-delete expired posted videos + low-battery alerts. */
object Maintenance {

    // ------------------------------------------------------------- battery

    data class Battery(val pct: Int, val charging: Boolean)

    fun readBattery(context: Context): Battery {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(pct, charging)
    }

    fun batteryText(context: Context): String {
        val b = readBattery(context)
        return "🔋 ${b.pct}%${if (b.charging) " (charging ⚡)" else ""}"
    }

    suspend fun checkBattery(context: Context) {
        val ctx = context.applicationContext
        val s = SettingsRepo.get(ctx).current()
        if (s.batteryAlertPct <= 0 || s.lastChatId <= 0 || s.botToken.isBlank()) return
        val b = readBattery(ctx)
        val prefs = ctx.getSharedPreferences("maintenance", Context.MODE_PRIVATE)
        val alerted = prefs.getBoolean("battery_alerted", false)
        if (!b.charging && b.pct in 1..s.batteryAlertPct && !alerted) {
            TelegramClient(s.botToken).sendMessage(
                s.lastChatId,
                "🪫 Battery low: ${b.pct}%\nPlug the phone in to keep scheduled posts running. (Alert threshold: ${s.batteryAlertPct}% — change it in Settings.)"
            )
            prefs.edit().putBoolean("battery_alerted", true).apply()
            AppLog.log("MAINT", "Battery alert sent (${b.pct}%)")
        } else if (b.charging || b.pct > s.batteryAlertPct + 5) {
            if (alerted) prefs.edit().putBoolean("battery_alerted", false).apply()
        }
    }

    // ------------------------------------------------------------- storage

    fun storageText(): String {
        return try {
            val stat = android.os.StatFs(Environment.getDataDirectory().path)
            val free = stat.availableBytes
            val total = stat.totalBytes
            "💾 Storage: ${Format.gb(free)} free of ${Format.gb(total)}"
        } catch (e: Exception) {
            "💾 Storage: unavailable"
        }
    }

    // -------------------------------------------------------- auto-delete

    /**
     * Deletes the gallery files of videos that were posted more than
     * autoDeleteDays ago. ONLY items with status POSTED and a known upload
     * time are touched — queued/downloading/failed videos are never removed.
     */
    suspend fun clean(context: Context) {
        val ctx = context.applicationContext
        val s = SettingsRepo.get(ctx).current()
        if (s.autoDeleteDays <= 0) return
        try {
            val dao = AppDatabase.get(ctx).queueDao()
            val cutoff = System.currentTimeMillis() - s.autoDeleteDays * 86_400_000L
            val stale = dao.expiredPosted(cutoff)
            for (item in stale) {
                val uriStr = item.localUri ?: continue
                try {
                    val uri = Uri.parse(uriStr)
                    if (uri.scheme == "content") {
                        ctx.contentResolver.delete(uri, null, null)
                    } else {
                        File(uri.path ?: "").delete()
                    }
                    dao.clearLocalUri(item.id)
                    AppLog.log("MAINT", "Auto-deleted posted video: ${item.title.take(60)}")
                } catch (e: Exception) {
                    // File already gone or locked — drop the reference so we
                    // don't retry forever.
                    dao.clearLocalUri(item.id)
                    AppLog.log("MAINT", "Delete skipped for ${item.title.take(40)}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AppLog.log("MAINT", "Auto-delete failed: ${e.message}")
        }
    }
}
