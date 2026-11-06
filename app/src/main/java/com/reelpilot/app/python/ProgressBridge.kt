package com.reelpilot.app.python

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Bridge called from Python (yt-dlp progress hook) into Kotlin. */
object ProgressBridge {

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress

    private val _speed = MutableStateFlow(0L)
    val speed: StateFlow<Long> = _speed

    @JvmStatic
    fun onProgress(status: String, pct: Int, speedBps: Int) {
        _progress.value = pct.coerceIn(0, 100)
        _speed.value = speedBps.toLong()
        if (status == "finished") _progress.value = 100
    }

    fun reset() {
        _progress.value = 0
        _speed.value = 0
    }
}
