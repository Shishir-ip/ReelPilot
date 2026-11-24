package com.reelpilot.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One SocialKit access key in the caption-generation pool. */
data class CaptionApi(
    val name: String,
    val key: String,
    val limit: Int,
    val used: Int,
    val month: String = ""      // "yyyy-MM" the used-count applies to
) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)

    companion object {
        private fun currentMonth(): String =
            SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

        /** Returns the list with usage counters reset for a new month. */
        fun resetMonthly(apis: List<CaptionApi>): List<CaptionApi> {
            val now = currentMonth()
            return apis.map { if (it.month != now) it.copy(used = 0, month = now) else it }
        }

        fun parseList(json: String): List<CaptionApi> = try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                CaptionApi(
                    o.optString("name", "key"),
                    o.optString("key", ""),
                    o.optInt("limit", 20),
                    o.optInt("used", 0),
                    o.optString("month", "")
                ).let { resetMonthly(listOf(it)).first() }
            }
        } catch (e: Exception) {
            emptyList()
        }

        fun toJson(apis: List<CaptionApi>): String {
            val arr = JSONArray()
            resetMonthly(apis).forEach { a ->
                arr.put(JSONObject().apply {
                    put("name", a.name)
                    put("key", a.key)
                    put("limit", a.limit)
                    put("used", a.used)
                    put("month", if (a.month.isBlank()) currentMonth() else a.month)
                })
            }
            return arr.toString()
        }
    }
}
