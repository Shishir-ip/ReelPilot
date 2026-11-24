package com.reelpilot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.WatchWorker

/** After reboot, restore the periodic posting check (downloads resume from the DB). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PostWorker.schedulePeriodic(context)
            WatchWorker.schedule(context)
            // Re-arm the exact wake-up for the next waiting slot.
            com.reelpilot.app.worker.PostAlarm.schedule(context)
        }
    }
}
