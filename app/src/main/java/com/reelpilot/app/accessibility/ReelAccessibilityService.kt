package com.reelpilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.reelpilot.app.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Drives the real Instagram app with accessibility actions: opens the reel
 * composer (Instagram is launched via a share intent with the video already
 * attached), fills the caption, and taps Share.
 */
class ReelAccessibilityService : AccessibilityService() {

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"

        /** Grace period after Share before force-closing Instagram (background upload). */
        const val UPLOAD_GRACE_MS = 120_000L

        @Volatile
        var instance: ReelAccessibilityService? = null
            private set

        val connected: Boolean get() = instance != null

        fun isServiceEnabled(ctx: android.content.Context): Boolean {
            val setting = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val wanted = ComponentName(ctx, ReelAccessibilityService::class.java)
            return setting.split(':').any {
                it.equals(wanted.flattenToString(), true) ||
                    it.endsWith("/${ReelAccessibilityService::class.java.name}")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppLog.log("IG", "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Node access is done by polling on demand; no event handling needed.
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- nodes

    private fun root(): AccessibilityNodeInfo? = rootInActiveWindow

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, depth: Int) {
        if (depth > 40 || out.size > 800) return
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectNodes(it, out, depth + 1) }
        }
    }

    private fun textOf(node: AccessibilityNodeInfo): String =
        ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()

    private fun idOf(node: AccessibilityNodeInfo): String =
        (node.viewIdResourceName ?: "").lowercase()

    private fun matches(node: AccessibilityNodeInfo, words: List<String>, idWords: List<String> = emptyList()): Boolean {
        val t = textOf(node)
        if (words.any { t.contains(it) }) return true
        val v = idOf(node)
        if (idWords.any { v.contains(it) }) return true
        return false
    }

    /** Node's text or content-desc is exactly one of the words (e.g. "Share"). */
    private fun exactWords(node: AccessibilityNodeInfo, words: List<String>): Boolean {
        val t = node.text?.toString()?.trim()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        return words.any { t == it || d == it }
    }

    /**
     * Tiered node search. Order matters: an exact, clickable match (the real
     * "Share" button) must beat a static label that merely *contains* the word
     * (e.g. "People you share this content with…").
     */
    private fun findIn(root: AccessibilityNodeInfo, words: List<String>, idWords: List<String> = emptyList()): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all, 0)
        // Tier 1: clickable node whose text/desc is exactly one of the words
        all.firstOrNull { it.isClickable && exactWords(it, words) }?.let { return it }
        // Tier 2: any node with exact text/desc
        all.firstOrNull { exactWords(it, words) }?.let { return it }
        // Tier 3: clickable node with a matching view id
        if (idWords.isNotEmpty()) {
            all.firstOrNull { it.isClickable && idWords.any { w -> idOf(it).contains(w) } }?.let { return it }
        }
        // Tier 4: clickable contains, then any contains/id match
        all.firstOrNull { it.isClickable && matches(it, words, idWords) }?.let { return it }
        return all.firstOrNull { matches(it, words, idWords) }
    }

    private fun findText(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? =
        findIn(root, words)

    private suspend fun waitFor(
        words: List<String>,
        idWords: List<String> = emptyList(),
        timeoutMs: Long,
        step: String
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            root()?.let { r -> findIn(r, words, idWords)?.let { return it } }
            delay(400)
        }
        AppLog.log("IG", "Step '$step': NOT FOUND (timeout)")
        return null
    }

    // ------------------------------------------------------------- actions

    suspend fun tap(node: AccessibilityNodeInfo): Boolean = withContext(Dispatchers.Default) {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 10) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withContext true
            n = n.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        tapAt(r.exactCenterX(), r.exactCenterY())
    }

    private suspend fun tapAt(x: Float, y: Float): Boolean {
        val latch = CompletableDeferred<Boolean>()
        val path = android.graphics.Path().apply {
            moveTo(x, y)
            lineTo(x, y + 0.1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                latch.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.complete(false)
            }
        }, null)
        return latch.await()
    }

    private suspend fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        repeat(2) { attempt ->
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
            if (attempt == 0) {
                tap(node)
                delay(700)
            }
        }
        return false
    }

    private fun findEditable(): AccessibilityNodeInfo? {
        val r = root() ?: return null
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(r, all, 0)
        return all.firstOrNull {
            it.isEditable && (it.className == "android.widget.EditText" || it.className == "android.widget.AutoCompleteTextView")
        } ?: all.firstOrNull { it.isEditable }
    }

    private suspend fun waitForPackage(pkg: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            delay(400)
        }
        return false
    }

    private suspend fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    private fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * After a post, force-close Instagram completely (background process kill)
     * so no leftover screen, dialog or draft can interfere with the next
     * automated posting session.
     */
    private fun killInstagram() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(INSTAGRAM_PACKAGE)
            AppLog.log("IG", "Instagram force-closed")
        } catch (e: Exception) {
            AppLog.log("IG", "Could not kill Instagram: ${e.message}")
        }
    }

    /** One upward swipe (reveals bottom-of-screen content like the Share bar). */
    private suspend fun swipeUp() {
        val latch = CompletableDeferred<Boolean>()
        val m = resources.displayMetrics
        val path = android.graphics.Path().apply {
            moveTo(m.widthPixels / 2f, m.heightPixels * 0.75f)
            lineTo(m.widthPixels / 2f, m.heightPixels * 0.35f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                latch.complete(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.complete(false)
            }
        }, null)
        latch.await()
        delay(900)
    }

    /**
     * After tapping Share, decide what happened:
     *  "confirmed" → "Your reel has been shared" style message seen
     *  "gone"      → the composer closed (Share button / "New reel" title vanished)
     *  "still"     → still sitting on the share screen → tap needs a retry
     */
    private suspend fun waitShareResult(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            root()?.let { r ->
                if (findText(
                        r,
                        listOf("your reel", "your post", "reel shared", "shared to your", "has been shared", "sharing to")
                    ) != null
                ) return "confirmed"
                if (findIn(r, listOf("share"), idWords = listOf("share")) == null &&
                    findText(r, listOf("new reel")) == null
                ) return "gone"
            }
            delay(2000)
        }
        return "still"
    }

    // -------------------------------------------------------------- flow

    /**
     * Instagram has been started via a share intent that already contains the
     * video. Walk the remaining composer screens and share it as a reel.
     * Returns (success, message).
     */
    suspend fun postReel(caption: String): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        try {
            step("Waiting for Instagram to open…")
            if (!waitForPackage(INSTAGRAM_PACKAGE, 20_000)) {
                return@withContext Pair(false, "Instagram did not open. Is it installed and updated?")
            }
            delay(2000)

            // 1) Share sheet → tap "Reels" (some builds open the editor directly)
            val reelOption = waitFor(
                words = listOf("reel", "reels"),
                timeoutMs = 12_000,
                step = "share sheet Reels option"
            )
            if (reelOption != null) {
                step("Tapping Reels")
                tap(reelOption)
                delay(2500)
            } else {
                step("No share sheet — assuming editor opened directly")
            }

            // 2) Clip preview screen → Next
            val next = waitFor(words = listOf("next"), idWords = listOf("next"), timeoutMs = 30_000, step = "Next button")
                ?: return@withContext Pair(false, "Could not find the Next button — Instagram UI may have changed.")
            step("Tapping Next")
            tap(next)
            delay(3000)

            // 3) Final share screen: caption + Share button
            if (caption.isNotBlank()) {
                var edit = findEditable()
                if (edit == null) {
                    // Caption might be behind an "Add a caption" button
                    root()?.let { r ->
                        findText(r, listOf("add a caption", "write a caption", "caption"))?.let {
                            tap(it)
                            delay(1200)
                        }
                    }
                    edit = findEditable()
                }
                if (edit != null) {
                    step("Typing caption")
                    setText(edit, caption)
                    delay(800)
                    // Close caption sheet if one opened (Done / ✓ button)
                    root()?.let { r ->
                        findIn(r, listOf("done"), idWords = listOf("done", "primary_button"))?.let {
                            tap(it)
                            delay(800)
                        }
                    }
                } else {
                    step("No caption field found — posting without caption")
                }
            }

            // 3.5) Make sure the bottom bar (Share button) is on screen
            swipeUp()

            // 4) Tap Share, verify it actually went through, retry up to 3 times.
            var shareResult = "still"
            var attempts = 0
            while (attempts < 3) {
                val share = waitFor(
                    words = listOf("share"),
                    idWords = listOf("share"),
                    timeoutMs = 15_000,
                    step = "Share button"
                )
                if (share == null) {
                    if (attempts > 0) {
                        // Share button vanished after we already tapped — upload in progress
                        shareResult = "gone"
                        break
                    }
                    return@withContext Pair(false, "Could not find the Share button — Instagram UI may have changed.")
                }
                step("Tapping Share (attempt ${attempts + 1})")
                tap(share)
                delay(3000)
                shareResult = waitShareResult(60_000)
                if (shareResult != "still") break
                attempts++
                swipeUp()
            }
            AppLog.log("IG", "postReel finished, shareResult=$shareResult")
            // Leave Instagram's UI immediately…
            home()
            // …but do NOT kill the process yet: the upload keeps running in
            // Instagram's background after the composer closes. Killing it
            // now would abort the upload (the v1.5.2 regression).
            step("Waiting for Instagram to finish uploading…")
            delay(UPLOAD_GRACE_MS)
            killInstagram()
            return@withContext when (shareResult) {
                "confirmed", "gone" -> Pair(true, "Posted successfully")
                else -> Pair(false, "Share button did not complete — check the Logs tab and retry from the Queue")
            }
        } catch (e: Exception) {
            home()
            AppLog.log("IG", "postReel error: ${e.message}")
            Pair(false, "Automation error: ${e.message?.take(200)}")
        }
    }

    private fun step(msg: String) = AppLog.log("IG", msg)
}
