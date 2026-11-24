package com.reelpilot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.reelpilot.app.worker.PostWorker
import com.reelpilot.app.worker.QueueRepair
import com.reelpilot.app.worker.WatchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ReelPilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        PostWorker.schedulePeriodic(this)
        WatchWorker.schedule(this)
        // Heal any queue rows left stuck by the 2.0.0 download/caption race.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { QueueRepair.run(this@ReelPilotApp) }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                "posts",
                "Post results",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                "monitor",
                "Telegram monitor",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}
