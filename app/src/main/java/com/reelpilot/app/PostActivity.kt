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
 * Wakes the screen, pre-warms the target app and hands the video over via a
 * share intent. Shows a visible status so slow cold starts never look frozen.
 */
class PostActivity : Activity() {

    private lateinit var statusText: TextView
    private var reported = false

    override fun onDestroy() {
        super.onDestroy()
        // Closed early (back press / system kill) without a result: tell the
        // worker so it doesn't wait out its full timeout. Skip test/flow runs
        // (they don't use the worker deferred) and already-reported runs.
        if (!reported &&
            !intent.getBooleanExtra(EXTRA_TEST, false) &&
            !intent.getBooleanExtra(EXTRA_FLOW_SETUP, false)
        ) {
            PostWorker.completeResult(false, "Posting screen was closed before finishing")
        }
    }

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
        val target = intent.getStringExtra(EXTRA_TARGET)
            ?: ReelAccessibilityService.INSTAGRAM_PACKAGE

        if (uri == null) {
            AppLog.log("POST", "PostActivity started without a video uri")
            finish()
            return
        }

        setStatus("Starting ${appLabel(target)}…")
        AppLog.log("POST", "Waking screen and pre-warming $target…" + if (flowSetup) " (flow recording)" else "")

        // Drive everything: pre-warm the target app, then share the video into it.
        CoroutineScope(Dispatchers.Default).launch {
            try {
            // 1) Open the app normally first so its process is warm —
            //    sharing into a warm app opens the composer quickly.
            val warmOk = try {
                val launch = packageManager.getLaunchIntentForPackage(target)
                    ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (launch != null) {
                    startActivity(launch)
                    val svc = ReelAccessibilityService.instance
                    svc?.awaitPackage(target, 25_000) ?: false
                } else false
            } catch (e: Exception) {
                AppLog.log("POST", "Pre-warm failed: ${e.message}")
                false
            }
            val warmupSec = com.reelpilot.app.data.SettingsRepo.get(applicationContext).current().warmupSec
            delay(warmupSec * 1000L)  // let the feed settle

            // 2) Now share the video into the warm app.
            setStatus("Opening upload screen…")
            try {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                    putExtra(Intent.EXTRA_TEXT, caption)
                    setPackage(target)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(send)
            } catch (e: Exception) {
                AppLog.log("POST", "Could not launch share into $target: ${e.message}")
                finishWith(false, "Could not launch ${appLabel(target)}: ${e.message?.take(200)}")
                finish()
                return@launch
            }

            // 3) Either record a manual flow, or drive the composer.
            if (flowSetup) {
                setStatus("Position your tap steps,\nthen tap Save on the panel")
                val svc = ReelAccessibilityService.instance
                if (svc == null) {
                    finishWith(false, "Accessibility service is not enabled")
                    finish()
                    return@launch
                }
                val saved = withTimeoutOrNull(10 * 60_000L) {
                    val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
                    svc.startFlowRecording(target) { done.complete(it) }
                    done.await()
                } ?: false
                AppLog.log("FLOW", if (saved) "Flow for $target recorded and saved" else "Flow recording cancelled/timed out")
                delay(1500)
                svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                delay(1000)
                svc.killApp(target)
                finish()
                return@launch
            }

            setStatus("Uploading reel…")
            val result = withTimeoutOrNull(9 * 60_000L) {
                val svc = ReelAccessibilityService.instance
                if (svc == null) {
                    Pair(false, "Accessibility service is not enabled")
                } else if (target == ReelAccessibilityService.INSTAGRAM_PACKAGE) {
                    svc.postReel(caption, target)
                } else {
                    svc.postFlow(caption, target)
                }
            } ?: Pair(false, "Posting timed out")
            finishWith(result.first, result.second)
            finish()
            } catch (e: Exception) {
                // Never die silently: an unreported crash would leave the
                // queue worker waiting forever and block all later posts.
                AppLog.log("POST", "Posting error: ${e.message}")
                finishWith(false, "Posting error: ${e.message?.take(200)}")
                finish()
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        pkg.substringAfterLast('.').ifBlank { pkg }
    }

    /** Test runs (Settings → Test flow) report here instead of the queue worker. */
    private fun finishWith(ok: Boolean, msg: String) {
        reported = true
        val label = appLabel(
            intent.getStringExtra(EXTRA_TARGET) ?: ReelAccessibilityService.INSTAGRAM_PACKAGE
        )
        if (intent.getBooleanExtra(EXTRA_TEST, false)) {
            testResults.value = TestResult(label, ok, msg)
            runOnUiThread {
                android.widget.Toast.makeText(
                    this, (if (ok) "✅ " else "❌ ") + "Flow test ($label): " + msg,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } else {
            PostWorker.completeResult(ok, msg)
        }
        finish()
    }

    companion object {
        const val EXTRA_TEST = "test_mode"
        data class TestResult(val app: String, val ok: Boolean, val message: String)
        val testResults = kotlinx.coroutines.flow.MutableStateFlow<TestResult?>(null)

        const val EXTRA_URI = "uri"
        const val EXTRA_CAPTION = "caption"
        const val EXTRA_FLOW_SETUP = "flow_setup"
        const val EXTRA_TARGET = "target_package"
    }
}
