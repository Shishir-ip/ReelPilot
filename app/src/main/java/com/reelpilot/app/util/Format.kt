package com.reelpilot.app.util

import java.util.Locale

object Format {
    /** 125000 ms -> "2:05" */
    fun duration(ms: Long): String {
        if (ms <= 0) return ""
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%d:%02d", m, s)
    }

    /** bytes -> "3.4 GB" */
    fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
}
