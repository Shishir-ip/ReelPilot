package com.reelpilot.app.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple in-memory activity log shown in the Logs tab. */
object AppLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines = _lines

    fun log(tag: String, msg: String) {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val line = "$ts [$tag] $msg"
        _lines.value = (listOf(line) + _lines.value).take(500)
        Log.d("ReelPilot", line)
    }
}
