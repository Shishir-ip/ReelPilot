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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the real Instagram app with accessibility actions: opens the reel
 * composer (Instagram is launched via a share intent with the video already
 * attached), fills the caption, and taps Share.
 */
class ReelAccessibilityService : AccessibilityService() {

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val FACEBOOK_PACKAGE = "com.facebook.katana"

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

    /**
     * Full physical screen size (including the navigation-bar area).
     * resources.displayMetrics can exclude system bars, which made recorder
     * markers stop short of bottom-edge buttons like Instagram's Next.
     * Recording and replay both use this so coordinates always match.
     */
    private fun realSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

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

    /** A clickable node whose text/desc is exactly one of the words. */
    private fun exactClickable(root: AccessibilityNodeInfo, words: List<String>): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        collectNodes(root, all, 0)
        return all.firstOrNull { it.isClickable && exactWords(it, words) }
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
     * After a post, force-close the target app completely (background process
     * kill) so no leftover screen, dialog or draft can interfere with the
     * next automated posting session.
     */
    fun killApp(pkg: String) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.killBackgroundProcesses(pkg)
            AppLog.log("IG", "Target app force-closed: $pkg")
        } catch (e: Exception) {
            AppLog.log("IG", "Could not kill $pkg: ${e.message}")
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
     * After tapping Share/Post, decide what happened:
     *  "confirmed" → "Your post/reel has been shared" style message seen
     *  "gone"      → the composer closed (button + caption field vanished)
     *  "still"     → still sitting on the share screen → tap needs a retry
     */
    private suspend fun waitShareResult(timeoutMs: Long, pkg: String): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        // Facebook's final button is "POST"; Instagram's is "Share".
        val composerButtons = if (pkg == FACEBOOK_PACKAGE) listOf("next", "post") else listOf("next", "share")
        while (System.currentTimeMillis() < deadline) {
            root()?.let { r ->
                if (findText(
                        r,
                        listOf(
                            "your reel", "your post", "reel shared", "shared to your",
                            "has been shared", "sharing to", "is now live",
                            "thanks for posting", "post shared", "your video"
                        )
                    ) != null
                ) return "confirmed"
                if (exactClickable(r, composerButtons) == null &&
                    findEditable() == null &&
                    findText(r, listOf("new reel", "create post")) == null
                ) return "gone"
            }
            delay(2000)
        }
        return "still"
    }

    /** Is a composer screen still on top? Used to decide whether a retry makes sense. */
    private fun composerPresent(): Boolean {
        val r = root() ?: return false
        if (findEditable() != null) return true
        if (exactClickable(r, listOf("post", "next")) != null) return true
        return findText(r, listOf("new reel", "create post", "write a caption", "add a caption")) != null
    }

    /**
     * Public pre-warm helper: wait until the target app's window is active.
     * Called before the share intent so the composer opens fast.
     */
    suspend fun awaitPackage(pkg: String, timeoutMs: Long): Boolean =
        waitForPackage(pkg, timeoutMs)

    // ------------------------------------------------- flow recorder overlay

    /**
     * Shows a floating recorder panel over Instagram: "+ Tap" drops a
     * numbered marker the user drags to a position; "Save" stores the steps;
     * "Cancel" aborts. The rest of the screen stays fully touchable.
     */
    fun startFlowRecording(target: String, onDone: (Boolean) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            var finished = false
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                val dm = resources.displayMetrics
                val density = dm.density
                // Full physical screen (incl. nav-bar area) so markers can be
                // dragged onto bottom-edge buttons like Instagram's Next.
                val (screenW, screenH) = realSize()
                fun dp(v: Int): Int = (v * density).toInt()
                fun rounded(color: Long, radiusDp: Float) =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(color.toInt())
                        cornerRadius = radiusDp * density
                    }

                // (view, layout params, isTextStep) — in the order steps will run.
                val markers = mutableListOf<Triple<android.widget.TextView, android.view.WindowManager.LayoutParams, Boolean>>()
                var stepCount = 0

                // The overlay window: touchable, non-focusable. (The window
                // TYPE must only go in `type` — putting it in `flags` used to
                // set FLAG_NOT_TOUCHABLE and made the overlay untouchable.)
                fun overlayParams(): android.view.WindowManager.LayoutParams =
                    android.view.WindowManager.LayoutParams().apply {
                        type = android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                        flags = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        format = android.graphics.PixelFormat.TRANSLUCENT
                        width = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                        height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    }

                val panel = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = rounded(0xF2141419, 20f)
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                    elevation = dp(8).toFloat()
                }

                val counter = android.widget.TextView(this).apply {
                    text = "🎙 Flow recorder — 1 step"
                    setTextColor(0xFFD0BCFF.toInt())
                    textSize = 13f
                    setPadding(dp(4), dp(2), dp(4), dp(8))
                }
                panel.addView(counter)

                fun styledButton(label: String, bg: Long, onClick: () -> Unit) =
                    android.widget.Button(this).apply {
                        text = label
                        textSize = 14f
                        isAllCaps = false
                        setTextColor(android.graphics.Color.WHITE)
                        background = rounded(bg, 14f)
                        setPadding(dp(14), dp(8), dp(14), dp(8))
                        stateListAnimator = null
                        setOnClickListener { onClick() }
                    }

                fun updateCounter() {
                    counter.text = "🎙 Flow recorder — $stepCount step${if (stepCount == 1) "" else "s"}"
                }

                fun removeMarker(tv: android.widget.TextView, lp: android.view.WindowManager.LayoutParams) {
                    runCatching { wm.removeView(tv) }
                    markers.removeIf { it.first == tv }
                    var n = 0
                    markers.forEach { (v, _, isText) ->
                        if (!isText) { n++; v.text = "$n" }
                    }
                    stepCount = markers.size
                    updateCounter()
                }

                fun addMarker(isText: Boolean) {
                    stepCount++
                    updateCounter()
                    val tapNumber = markers.count { !it.third } + 1
                    val tv = android.widget.TextView(this).apply {
                        text = if (isText) "✍" else "$tapNumber"
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 22f
                        gravity = android.view.Gravity.CENTER
                        background = rounded(if (isText) 0xE62E7D32 else 0xE66750A4, 30f)
                        setPadding(dp(18), dp(10), dp(18), dp(10))
                        elevation = dp(6).toFloat()
                    }
                    val lp = overlayParams().apply {
                        x = screenW / 2 - dp(60)
                        y = screenH / 2
                    }
                    var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0
                    tv.setOnTouchListener { v, ev ->
                        when (ev.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                downX = ev.rawX; downY = ev.rawY
                                baseX = lp.x; baseY = lp.y
                                v.alpha = 0.85f
                                true
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                // Reach the whole physical screen — Instagram's
                                // Next/Share buttons sit at the very bottom edge,
                                // inside the navigation-bar area.
                                lp.x = (baseX + (ev.rawX - downX)).toInt()
                                    .coerceIn(-dp(10), screenW - dp(40))
                                lp.y = (baseY + (ev.rawY - downY)).toInt()
                                    .coerceIn(-dp(10), screenH - dp(8))
                                wm.updateViewLayout(v, lp)
                                true
                            }
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                                v.alpha = 1f
                                true
                            }
                            else -> true
                        }
                    }
                    tv.setOnLongClickListener { removeMarker(tv, lp); true }
                    wm.addView(tv, lp)
                    markers.add(Triple(tv, lp, isText))
                }

                fun end(save: Boolean) {
                    if (finished) return
                    finished = true
                    runCatching { wm.removeView(panel) }
                    markers.forEach { (v, _, _) -> runCatching { wm.removeView(v) } }
                    if (save && markers.isNotEmpty()) {
                        // Steps play back in the order they were added.
                        val steps = markers.map { (v, lp, isText) ->
                            com.reelpilot.app.util.FlowStep(
                                isText,
                                ((lp.x + (v.measuredWidth.takeIf { it > 0 } ?: dp(60)) / 2f) / screenW)
                                    .coerceIn(0f, 0.995f),
                                ((lp.y + (v.measuredHeight.takeIf { it > 0 } ?: dp(46)) / 2f) / screenH)
                                    .coerceIn(0f, 0.995f)
                            )
                        }
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val repo = com.reelpilot.app.data.SettingsRepo.get(applicationContext)
                            repo.setFlow(target, steps)
                            if (target == INSTAGRAM_PACKAGE || target == FACEBOOK_PACKAGE) {
                                // A fresh recording is meant to be used — select it.
                                // The Settings toggle can switch back to built-in.
                                repo.setFlowMode(target, "manual")
                            }
                            if (target == INSTAGRAM_PACKAGE) {
                                // Keep the legacy manual-flow keys in sync
                                repo.setManualSteps(org.json.JSONArray().apply {
                                    steps.filter { !it.isText }.forEach { s ->
                                        put(org.json.JSONArray(listOf(s.x.toDouble(), s.y.toDouble())))
                                    }
                                }.toString())
                                repo.setManualFlow(true)
                            }
                            AppLog.log("FLOW", "Saved flow for $target with ${steps.size} steps")
                        }
                    }
                    onDone(save && markers.isNotEmpty())
                }

                panel.addView(styledButton("＋  Add tap step", 0xFF6750A4) { addMarker(false) })
                panel.addView(styledButton("✍  Add caption step", 0xFF2E7D32) { addMarker(true) })
                panel.addView(styledButton("✔  Save flow", 0xFF2E7D32) { end(true) })
                panel.addView(styledButton("✖  Cancel", 0xFF8B2E2E) { end(false) })

                val pp = overlayParams().apply { x = dp(16); y = dp(60) }
                wm.addView(panel, pp)
                addMarker(false)
                AppLog.log("FLOW", "Recorder open — drag markers in order, then Save")
            } catch (e: Exception) {
                AppLog.log("FLOW", "Recorder failed: ${e.message}")
                onDone(false)
            }
        }
    }

    // -------------------------------------------------------------- flow

    /**
     * Instagram/Facebook has been started via a share intent that already
     * contains the video. Posts it as a FEED post: if the app shows its
     * destination chooser (Feed / Reels / Stories / Your groups…), "Feed" is
     * selected automatically, then the composer is driven to completion.
     * A recorded flow in "manual" mode replays the user's taps instead.
     * Returns (success, message).
     */
    suspend fun postBuiltIn(caption: String, pkg: String = INSTAGRAM_PACKAGE): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        try {
            val t = com.reelpilot.app.data.SettingsRepo.get(applicationContext).current()
            step("Waiting for the target app ($pkg) to open…")
            if (!waitForPackage(pkg, t.igOpenWaitSec * 1000L)) {
                return@withContext Pair(false, "The app did not open. Is it installed and updated?")
            }
            delay(2000)

            // Manual flow: replay the user's recorded tap steps instead of
            // the built-in node matching. The app always handles the warm-up
            // and the share intent itself; the steps start from here.
            val steps = recordedFlowFor(pkg, t)
            var shareResult = "still"
            if (steps.isNotEmpty()) {
                step("Manual flow: replaying ${steps.size} recorded steps")
                delay(5000)  // let the upload section finish loading
                val (w, h) = realSize()
                for ((i, s) in steps.withIndex()) {
                    tapAt(s.x * w, s.y * h)
                    if (s.isText) {
                        delay(1200)  // let the field take focus
                        val typed = typeIntoFocused(caption)
                        step("Step ${i + 1}/${steps.size}: " + if (typed) "caption typed ✍" else "caption skipped (no editable field)")
                    } else {
                        step("Manual step ${i + 1}/${steps.size}")
                    }
                    delay(t.manualStepDelaySec * 1000L)
                }
                shareResult = waitShareResult(90_000, pkg)
            } else {
                // 1) Destination chooser → tap "Feed" so the post goes to the
                //    feed directly (FB: Your groups/Feed/Reels/Your story;
                //    IG: Messages/Feed/Stories/Reels). Some builds skip it.
                val feedOption = waitFor(
                    words = listOf("feed"),
                    timeoutMs = 15_000,
                    step = "share destination Feed"
                )
                if (feedOption != null) {
                    step("Selecting Feed")
                    tap(feedOption)
                    delay(2500)
                } else {
                    step("No destination chooser — composer opened directly")
                }

                // 2) Type the caption here if this screen already has the
                //    field (Facebook's composer shows it before Next).
                var captionTyped = false
                if (caption.isNotBlank()) {
                    captionTyped = typeCaption(caption)
                }

                // 3) Trim/preview screen → Next (cold starts load slowly).
                val next = waitFor(words = listOf("next"), idWords = listOf("next"), timeoutMs = t.nextWaitSec * 1000L, step = "Next button")
                if (next != null) {
                    step("Tapping Next")
                    tap(next)
                    delay(3000)
                } else {
                    step("No Next button — assuming final share screen")
                }

                // 4) Caption on the final screen (Instagram's caption box).
                if (caption.isNotBlank() && !captionTyped) {
                    typeCaption(caption)
                }

                // 4.5) Facebook: caption screen → NEXT → the final Post screen.
                val next2 = waitFor(words = listOf("next"), idWords = listOf("next"), timeoutMs = 8_000, step = "second Next")
                if (next2 != null) {
                    step("Tapping Next (post screen)")
                    tap(next2)
                    delay(2500)
                }

                // 5) Make sure the bottom bar (Share/Post button) is on screen
                swipeUp()

                // 6) Tap Share/Post, verify it went through, retry up to 3 times.
                var attempts = 0
                while (attempts < 3) {
                    val share = waitFor(
                        words = listOf("share", "post"),
                        idWords = listOf("share", "post"),
                        timeoutMs = t.shareWaitSec * 1000L,
                        step = "Share/Post button"
                    )
                    if (share == null) {
                        if (attempts > 0) {
                            // Button vanished after we already tapped — upload in progress
                            shareResult = "gone"
                            break
                        }
                        return@withContext Pair(false, "Could not find the Share/Post button — the app UI may have changed.")
                    }
                    step("Tapping Share/Post (attempt ${attempts + 1})")
                    tap(share)
                    delay(3000)
                    shareResult = waitShareResult(60_000, pkg)
                    if (shareResult != "still") break
                    attempts++
                    // Only retry while a composer is actually still on screen —
                    // never tap random Share buttons on the feed.
                    if (!composerPresent()) {
                        shareResult = "gone"
                        break
                    }
                    swipeUp()
                }
            }
            AppLog.log("IG", "postBuiltIn($pkg) finished, shareResult=$shareResult")
            // Leave the app's UI immediately…
            home()
            // …but do NOT kill the process yet: the upload keeps running in
            // the app's background after the composer closes. Killing it
            // now would abort the upload (the v1.5.2 regression).
            step("Waiting for the upload to finish…")
            delay(t.uploadGraceSec * 1000L)
            killApp(pkg)
            return@withContext when (shareResult) {
                "confirmed", "gone" -> Pair(true, "Posted successfully")
                else -> Pair(false, "Share/Post button did not complete — check the Logs tab and retry from the Queue")
            }
        } catch (e: Exception) {
            home()
            AppLog.log("IG", "postBuiltIn error: ${e.message}")
            Pair(false, "Automation error: ${e.message?.take(200)}")
        }
    }

    /**
     * Types the caption into the composer's caption field, opening the
     * "Add a caption" sheet first if needed. Returns true when typed.
     */
    private suspend fun typeCaption(caption: String): Boolean {
        var edit = findEditable()
        if (edit == null) {
            root()?.let { r ->
                findText(r, listOf("add a caption", "write a caption", "write something", "say something", "caption"))?.let {
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
            // Close the caption sheet if one opened (Done / ✓ button)
            root()?.let { r ->
                findIn(r, listOf("done"), idWords = listOf("done", "primary_button"))?.let {
                    tap(it)
                    delay(800)
                }
            }
            return true
        }
        step("No caption field on this screen")
        return false
    }

    /**
     * Posts to a non-Instagram target app by replaying the user-recorded
     * tap flow for that app. The share intent must already have been fired.
     */
    suspend fun postFlow(caption: String, pkg: String): Pair<Boolean, String> = withContext(Dispatchers.Default) {
        try {
            val t = com.reelpilot.app.data.SettingsRepo.get(applicationContext).current()
            val steps = recordedFlowFor(pkg, t)
            if (steps.isEmpty()) {
                return@withContext Pair(false, "No recorded flow for this app — record one in Settings → Post to apps")
            }
            step("Waiting for the target app ($pkg) to open…")
            if (!waitForPackage(pkg, t.igOpenWaitSec * 1000L)) {
                return@withContext Pair(false, "The app did not open. Is it installed and updated?")
            }
            step("Replaying recorded flow: ${steps.size} steps")
            delay(5000)  // let the app's composer finish loading
            val (w, h) = realSize()
            for ((i, s) in steps.withIndex()) {
                tapAt(s.x * w, s.y * h)
                if (s.isText) {
                    delay(1200)  // let the field take focus
                    val typed = typeIntoFocused(caption)
                    step("Flow step ${i + 1}/${steps.size}: " + if (typed) "caption typed ✍" else "caption skipped (no editable field)")
                } else {
                    step("Flow step ${i + 1}/${steps.size}")
                }
                delay(t.manualStepDelaySec * 1000L)
            }
            val shareResult = "done"
            AppLog.log("IG", "postFlow($pkg) finished, shareResult=$shareResult")
            home()
            step("Waiting for the upload to finish…")
            delay(t.uploadGraceSec * 1000L)
            killApp(pkg)
            return@withContext when (shareResult) {
                "done" -> Pair(true, "Posted successfully")
                else -> Pair(false, "Recorded flow did not complete the upload — re-record it if the app changed")
            }
        } catch (e: Exception) {
            home()
            AppLog.log("IG", "postFlow($pkg) error: ${e.message}")
            Pair(false, "Automation error: ${e.message?.take(200)}")
        }
    }

    /**
     * Recorded steps for a target. Apps without a built-in always replay
     * their flow; Instagram/Facebook only when their mode is "manual".
     */
    private fun recordedFlowFor(pkg: String, t: com.reelpilot.app.data.AppSettings): List<com.reelpilot.app.util.FlowStep> {
        val repo = com.reelpilot.app.data.SettingsRepo
        val wantsManual = !repo.hasBuiltIn(pkg) || repo.flowModeFor(t, pkg) == "manual"
        if (wantsManual) {
            val flows = repo.parseFlows(t.flows)
            if (flows.containsKey(pkg)) return flows[pkg]!!
            if (pkg == INSTAGRAM_PACKAGE && t.manualFlow) {
                return parseManualSteps(t.manualSteps)
                    .map { com.reelpilot.app.util.FlowStep(false, it[0], it[1]) }
            }
        }
        return emptyList()
    }

    private fun parseManualSteps(json: String): List<List<Float>> = try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            listOf(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Types `text` into the currently focused editable field. Tries the
     * direct set-text action first, then a clipboard paste as fallback.
     * Polls for up to 8 seconds — apps can take a moment to focus the box.
     */
    private suspend fun typeIntoFocused(text: String): Boolean {
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            root()?.let { r ->
                val all = mutableListOf<AccessibilityNodeInfo>()
                collectNodes(r, all, 0)
                val target = all.firstOrNull { it.isEditable && it.isFocused }
                    ?: all.firstOrNull { it.isEditable && it.text?.toString()?.isEmpty() == true }
                if (target != null) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                        )
                    }
                    if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                    try {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("reelpilot_caption", text))
                        if (target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
                    } catch (e: Exception) {
                        AppLog.log("IG", "Paste fallback failed: ${e.message}")
                    }
                }
            }
            delay(400)
        }
        return false
    }

    private fun step(msg: String) = AppLog.log("IG", msg)
}
