package com.reelpilot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.reelpilot.app.worker.PostWorker

/** After reboot, restore the periodic posting check (downloads resume from the DB). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PostWorker.schedulePeriodic(context)
        }
    }
}
