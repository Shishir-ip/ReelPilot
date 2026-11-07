package com.reelpilot.app.worker

import android.content.Context
import com.reelpilot.app.data.AppDatabase
import com.reelpilot.app.data.SettingsRepo
import com.reelpilot.app.data.Status
import com.reelpilot.app.util.AppLog

/**
 * Assigns an upload slot to every downloaded (READY) video.
 *
 * Slots follow the interval: 9:00 → 11:00 → 13:00 … Videos with a manually
 * set time keep it, and automatic slots flow around them. Deleting an item
 * and calling [reschedule] again shifts the later ones up automatically.
 */
object QueueScheduler {

    suspend fun reschedule(context: Context) {
        val ctx = context.applicationContext
        val dao = AppDatabase.get(ctx).queueDao()
        val settings = SettingsRepo.get(ctx).current()
        val intervalMs = settings.intervalHours * 60 * 60 * 1000L

        // [FLOW] items are recording material only — they never get a slot.
        val ready = dao.byStatus(Status.READY)
            .filter { !it.title.startsWith("[FLOW]") }
            .sortedBy { it.createdAt }
        if (ready.isEmpty()) return

        var slot = maxOf(settings.nextPostAt, System.currentTimeMillis())
        for (item in ready) {
            if (item.postAt > 0) {
                // Manually pinned time: keep it, next automatic slot flows after it.
                slot = maxOf(slot, item.postAt + intervalMs)
            } else {
                dao.setPostAt(item.id, slot)
                AppLog.log("QUEUE", "Slot for \"${item.title}\": ${java.text.SimpleDateFormat("MMM d HH:mm", java.util.Locale.getDefault()).format(java.util.Date(slot))}")
                slot += intervalMs
            }
        }
    }
}
