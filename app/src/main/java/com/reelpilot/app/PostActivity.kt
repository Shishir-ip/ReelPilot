package com.reelpilot.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.reelpilot.app.accessibility.ReelAccessibilityService
import com.reelpilot.app.util.AppLog
import com.reelpilot.app.worker.PostWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wakes the screen and hands the video to Instagram. Shows a visible status
 * so a slow Instagram cold start never looks like the app froze.
 */
class PostActivity : Activity() {

    private lateinit var statusText: TextView

    private fun setStatus(msg: String) {
        runOnUiThread { statusText.text = "ReelPilot\n\n$msg" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF101014.toInt()) }
        statusText = TextView(this).apply {
            text = "ReelPilot\n\nPosting…"
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(
            statusText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)

        val uri = intent.getStringExtra(EXTRA_URI)
        val caption = intent.getStringExtra(EXTRA_CAPTION) ?: ""
        val flowSetup = intent.getBooleanExtra(EXTRA_FLOW_SETUP, false)

        if (uri == null) {
            AppLog.log("POST", "PostActivity started without a video uri")
            finish()
            return
        }

        setStatus("Starting Instagram…")
        AppLog.log("POST", "Waking screen and pre-warming Instagram…" + if (flowSetup) " (flow recording)" else "")

        // Drive everything: pre-warm Instagram, then share the video into it.
        CoroutineScope(Dispatchers.Default).launch {
            // 1) Open Instagram normally first so its process is warm —
            //    sharing into a warm app opens the reels section quickly,
            //    sharing into a closed one is slow and flaky.
            val warmOk = try {
                val launch = packageManager.getLaunchIntentForPackage(
                    ReelAccessibilityService.INSTAGRAM_PACKAGE
                )?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (launch != null) {
                    startActivity(launch)
                    val svc = ReelAccessibilityService.instance
                    svc?.awaitInstagram(25_000) ?: false
                } else false
            } catch (e: Exception) {
                AppLog.log("POST", "Pre-warm failed: ${e.message}")
                false
            }
            val warmupSec = com.reelpilot.app.data.SettingsRepo.get(applicationContext).current().warmupSec
            delay(warmupSec * 1000L)  // let the feed settle

            // 2) Now share the video into the warm app.
            setStatus("Opening reels upload…")
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
                AppLog.log("POST", "Could not launch Instagram share: ${e.message}")
                PostWorker.completeResult(false, "Could not launch Instagram: ${e.message?.take(200)}")
                finish()
                return@launch
            }

            // 3) Either record a manual flow, or drive the composer.
            if (flowSetup) {
                setStatus("Position your tap steps,\nthen tap Save on the panel")
                val svc = ReelAccessibilityService.instance
                if (svc == null) {
                    PostWorker.completeResult(false, "Accessibility service is not enabled")
                    finish()
                    return@launch
                }
                val saved = withTimeoutOrNull(10 * 60_000L) {
                    val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    svc.startFlowRecording { done.complete(it) }
                    done.await()
                } ?: false
                AppLog.log("FLOW", if (saved) "Flow recorded and saved" else "Flow recording cancelled/timed out")
                delay(1500)
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                delay(1000)
                svc.killInstagram()
                finish()
                return@launch
            }

            setStatus("Uploading reel…")
            val result = withTimeoutOrNull(9 * 60_000L) {
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
        const val EXTRA_FLOW_SETUP = "flow_setup"
    }
}
