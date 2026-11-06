package com.reelpilot.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.reelpilot.app.accessibility.ReelAccessibilityService
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Invisible activity used to wake the screen and hand the video to Instagram.
 * Started by PostWorker at the scheduled posting time.
 */
class PostActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val uri = intent.getStringExtra(EXTRA_URI)
        val caption = intent.getStringExtra(EXTRA_CAPTION) ?: ""

        if (uri == null) {
            AppLog.log("POST", "PostActivity started without a video uri")
            finish()
            return
        }

        AppLog.log("POST", "Waking screen and launching Instagram share…")

        // Hand the video straight to Instagram via the share target.
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                putExtra(Intent.EXTRA_TEXT, caption)
                setPackage(ReelAccessibilityService.INSTAGRAM_PACKAGE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(send)
        } catch (e: Exception) {
            AppLog.log("POST", "Could not launch Instagram: ${e.message}")
            PostWorker.completeResult(false, "Could not launch Instagram: ${e.message?.take(200)}")
            finish()
            return
        }

        // Drive the remaining composer steps through the accessibility service.
        CoroutineScope(Dispatchers.Default).launch {
            val result = withTimeoutOrNull(8 * 60_000L) {
                val svc = ReelAccessibilityService.instance
                if (svc == null) {
                    Pair(false, "Accessibility service is not enabled")
                } else {
                    svc.postReel(caption)
                }
            } ?: Pair(false, "Posting timed out")
            PostWorker.completeResult(result.first, result.second)
            finish()
        }
    }

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_CAPTION = "caption"
    }
}
