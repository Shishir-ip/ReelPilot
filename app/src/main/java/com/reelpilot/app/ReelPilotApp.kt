package com.reelpilot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.reelpilot.app.worker.PostWorker

class ReelPilotApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        PostWorker.schedulePeriodic(this)
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
