package com.reelpilot.app.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.Status
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exact-time wake-up for posting slots. WorkManager jobs get batched into
 * late maintenance windows when the phone is in Doze / battery saver, which
 * made scheduled uploads fire late or not at all. An RTC_WAKEUP alarm fires
 * at the slot time even in Doze; the receiver then enqueues an expedited
 * posting check.
 *
 * Android 12+: exact alarms need the "Alarms & reminders" special permission.
 * Without it we fall back to setAndAllowWhileIdle (still Doze-aware, just less
 * precise) — the 15-minute WorkManager sweep remains the final safety net.
 */
object PostAlarm {

    const val ACTION = "com.reelpilot.app.POST_SLOT"

    /** Arm (or cancel) the wake-up for the earliest waiting slot. */
    fun schedule(context: Context) {
        val ctx = context.applicationContext
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, PostAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val earliest = runBlocking(Dispatchers.IO) {
            try {
                AppDatabase.get(ctx).queueDao().activeItems()
                    .filter { (it.status == Status.READY || it.status == Status.POSTING) && it.postAt > 0 }
                    .minOfOrNull { it.postAt }
            } catch (e: Exception) {
                null
            }
        }
        val now = System.currentTimeMillis()
        if (earliest == null || earliest <= now) {
            // Nothing waiting, or already due — the expedited check / periodic
            // sweep handles due items; an alarm here would just loop.
            am.cancel(pi)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pi)
                AppLog.log("POST", "Wake-up (approximate — allow 'Alarms & reminders' in Settings for exact time): ${fmt(earliest)}")
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pi)
                AppLog.log("POST", "Exact wake-up armed: ${fmt(earliest)}")
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, earliest, pi)
            AppLog.log("POST", "Exact alarm denied — approximate wake-up: ${fmt(earliest)}")
        }
    }

    private fun fmt(at: Long): String =
        SimpleDateFormat("MMM d HH:mm", Locale.getDefault()).format(Date(at))
}

/** Fires at the slot time and kicks off an expedited posting check. */
class PostAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PostAlarm.ACTION) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppLog.log("POST", "Slot alarm fired — starting posting check")
                PostWorker.scheduleOneTime(context)
            } catch (e: Exception) {
                AppLog.log("POST", "Alarm handling failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}