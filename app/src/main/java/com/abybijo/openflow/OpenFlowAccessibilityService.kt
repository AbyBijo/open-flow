package com.abybijo.openflow

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Inserts dictated text into the focused text field of another app.
 *
 * Wave 1 is synchronous: up to 3 attempts, ~200 ms apart, because focus
 * can lag behind the overlay tap. Each attempt:
 *  1. Clipboard + ACTION_PASTE first — paste preserves the editor's undo
 *     history, which a full-text splice destroys. The user's previous
 *     clipboard content is restored a second later.
 *  2. Splice at the cursor as a fallback — or append at the end when the
 *     field reports no selection (common in WebView fields). The clipboard
 *     is never touched here. A field whose whole content is just its
 *     placeholder hint (e.g. WhatsApp's "Message") is treated as empty,
 *     so the hint is never pasted in front of a dictation.
 *  3. Caller falls back to clipboard-only.
 *
 * Wave 2 is event-driven: if wave 1 fails, the text is staged as pending
 * and retried when the host app regains focus (focus / window-state /
 * content events) and on a short timed schedule (100/250/500/900/1400 ms).
 * It gives up only after the last retry + 300 ms — later still if the
 * keyboard is open, which means a field exists even when no editable
 * node was found. The pending insert only ever fires into the app the
 * dictation started in; if the user switches to another app it is
 * dropped, and if the bubble is dismissed it is dropped too.
 *
 * The insert resolves the host app's window directly from the window
 * list when the active window is our own overlay: right after the
 * user taps the bubble, the system can still report our window as
 * active, and wave 1 used to burn its attempts on it and then wait
 * out wave 2's schedule — the 2–5 seconds the user measured outside
 * the app. A successful insert reports at once; the retry schedule
 * only runs while every attempt is still failing.
 *
 * Never inserts into password fields, and never into banking or
 * password-manager apps — those always fall back to the clipboard.
 * The service looks at the field that has input focus, falling back to
 * the first editable field in the window when no field reports focus,
 * and only when a dictation has just finished.
 */
class OpenFlowAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile private var instance: OpenFlowAccessibilityService? = null

        fun isEnabled(): Boolean = instance != null

        /** The app that was in front the last time a window changed — the dictation's home. */
        fun currentHostPackage(): String? = instance?.lastHostPackage

        private val SENSITIVE_PACKAGES = listOf(
            "bank", "banking", "1password", "lastpass", "bitwarden",
            "keepass", "dashlane", "password", "paytm", "phonepe", "gpay"
        )

        /** Wave 1 only: synchronous attempts. Returns true if the text landed. */
        suspend fun insertText(context: Context, text: String): Boolean {
            val service = instance ?: return false
            return service.insertWithRetry(text, service.lastHostPackage)
        }

        /**
         * Full insert: wave 1, then the event-driven pending path if needed.
         * [onResult] is invoked exactly once, on the main thread: true when
         * the text landed, false when it gave up (caller: clipboard fallback).
         * [hostPackage] is the app the dictation started in; the pending
         * insert never fires into any other app.
         */
        fun requestInsert(
            context: Context,
            text: String,
            hostPackage: String?,
            onResult: (Boolean) -> Unit
        ) {
            val service = instance
            if (service == null) {
                onResult(false)
                return
            }
            service.serviceScope.launch {
                if (service.insertWithRetry(text, hostPackage)) {
                    onResult(true)
                    return@launch
                }
                service.stagePending(text, hostPackage, onResult)
            }
        }

        /** Drop any staged insert without reporting — the bubble went away. */
        fun clearPending() {
            instance?.clearPendingState()
        }

        fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
        }
    }

    private enum class InsertOutcome { INSERTED, WRONG_APP, RETRY }

    /** Timed retries for a staged insert, milliseconds after staging.
     * Tight on purpose: the whole point of wave 2 is to land fast
     * once the host window is ready, not to wait the schedule out.
     * A successful insert reports at once — the schedule only runs
     * while every attempt is still failing. */
    private val retryDelays = longArrayOf(100, 250, 500, 900, 1400)
    /** Hard cap for the whole pending phase, including IME-open extensions. */
    private val pendingHardCapMs = 10_000L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Last non-system package seen in front of a window-state event. */
    @Volatile private var lastHostPackage: String? = null
    private var cachedImePackage: String? = null

    /** Staged insert (wave 2). All of it is main-thread confined. */
    private var pendingText: String? = null
    private var pendingHostPackage: String? = null
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var pendingStagedAt = 0L
    private var pendingId = 0
    private var lastAttemptTime = 0L
    /** Monotonic paste counter: only the latest paste may restore the clipboard. */
    private var pasteSeq = 0

    /**
     * Fallback when no field reports input focus: walk the window tree
     * breadth-first for the first editable, visible, non-password field.
     * Used only when findFocus(FOCUS_INPUT) finds nothing — some apps
     * (WebView-based fields, custom editors) never report focus.
     * Nodes traversed along the way are recycled; the returned node, if
     * any, belongs to the caller.
     */
    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && !node.isPassword && node.isVisibleToUser) {
                return node
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) queue.add(child)
            }
            if (node !== root) {
                try { node.recycle() } catch (_: Exception) { }
            }
        }
        return null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // A pending insert must never hang its caller: report failure so
        // it falls back to the clipboard.
        val result = pendingResult
        clearPendingState()
        result?.invoke(false)
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = e.packageName?.toString()
                if (pkg != null && isTrackablePackage(pkg)) {
                    lastHostPackage = pkg
                }
                tryPendingInsert()
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> tryPendingInsert()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changes fire constantly; throttle attempts.
                val now = SystemClock.uptimeMillis()
                if (now - lastAttemptTime >= 300) tryPendingInsert()
            }
        }
    }

    override fun onInterrupt() {}

    /** Retry the whole insert: the field may not be focused yet right after the overlay tap. */
    private suspend fun insertWithRetry(text: String, hostPackage: String?): Boolean {
        repeat(3) { attempt ->
            when (attemptInsert(text, hostPackage)) {
                InsertOutcome.INSERTED -> return true
                InsertOutcome.WRONG_APP -> return false
                InsertOutcome.RETRY -> { /* keep trying */ }
            }
            if (attempt < 2) delay(100)
        }
        return false
    }

    /**
     * Root of the window to insert into. The active window is usually
     * the host app — but right after the user taps our overlay, the
     * system can still report OUR window (the bubble, or the review
     * screen closing) as active. Wave 1 used to burn all three of its
     * attempts on that window and then wait out wave 2's schedule
     * before the host window became active again — the 2–5 seconds
     * the user measured outside the app. So when the active window
     * is ours (or missing), resolve the host app's own window
     * directly from the window list instead of waiting for the
     * active window to settle. The returned node belongs to the
     * caller; the active-window node is recycled here when unused.
     */
    private fun hostRoot(hostPackage: String?): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null) {
            val pkg = active.packageName?.toString()
            if (pkg != null && pkg != packageName) return active
            try { active.recycle() } catch (_: Exception) { }
        }
        try {
            val wins = windows ?: return null
            for (w in wins) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = root.packageName?.toString()
                if (pkg == packageName) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }
                if (hostPackage != null && pkg != hostPackage) {
                    try { root.recycle() } catch (_: Exception) { }
                    continue
                }
                return root
            }
        } catch (_: Exception) { }
        return null
    }

    private fun attemptInsert(text: String, hostPackage: String?): InsertOutcome {
        val root = hostRoot(hostPackage) ?: return InsertOutcome.RETRY
        val rootPkg = root.packageName?.toString()
        // Never fire into the wrong app: the window must be the app
        // the dictation started in.
        if (rootPkg != null && hostPackage != null && rootPkg != hostPackage) {
            try { root.recycle() } catch (_: Exception) { }
            return InsertOutcome.WRONG_APP
        }
        val node = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        } ?: findEditableNode(root)
        if (node == null) {
            try { root.recycle() } catch (_: Exception) { }
            return InsertOutcome.RETRY
        }
        try {
            if (node.isPassword) return InsertOutcome.RETRY
            val pkg = node.packageName?.toString().orEmpty().lowercase()
            if (SENSITIVE_PACKAGES.any { pkg.contains(it) }) return InsertOutcome.RETRY
            if (!node.isEditable) return InsertOutcome.RETRY

            // Focus the field first: without focus the splice and the paste
            // both silently do nothing.
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            // 1. Clipboard + paste. Paste preserves the editor's undo
            // history; a full-text splice destroys it.
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val previousClip = try { clipboard?.primaryClip } catch (_: Exception) { null }
            copyToClipboard(this, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                // Verify the text actually landed before reporting success:
                // some fields accept the action and silently drop it.
                try { node.refresh() } catch (_: Exception) { }
                val after = try { node.text?.toString().orEmpty() } catch (_: Exception) { "" }
                if (after.contains(text)) {
                    restoreClipboardAfterPaste(clipboard, previousClip, text)
                    return InsertOutcome.INSERTED
                }
                // Not there — fall through to the splice.
            }

            // 2. Splice at the cursor. When the field reports no selection
            // (selection -1), append at the end instead of giving up.
            // Some fields (WhatsApp's "Message", Chrome's address bar) report
            // their placeholder hint as the field text when empty. If the
            // whole content is just the hint, the field is really empty —
            // otherwise the hint gets pasted in front of every dictation.
            val rawExisting = node.text?.toString().orEmpty()
            val hint = try { node.hintText?.toString().orEmpty() } catch (_: Exception) { "" }
            val existing = if (rawExisting.isNotEmpty() && rawExisting == hint) "" else rawExisting
            val start: Int
            val end: Int
            if (node.textSelectionStart >= 0) {
                start = node.textSelectionStart.coerceIn(0, existing.length)
                end = node.textSelectionEnd.coerceIn(start, existing.length)
            } else {
                start = existing.length
                end = existing.length
            }
            val merged = existing.substring(0, start) + text + existing.substring(end)
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                merged
            )
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                try { node.refresh() } catch (_: Exception) { }
                val after = try { node.text?.toString().orEmpty() } catch (_: Exception) { "" }
                if (after.contains(text)) return InsertOutcome.INSERTED
            }
            return InsertOutcome.RETRY
        } catch (_: Exception) {
            return InsertOutcome.RETRY
        } finally {
            try { node.recycle() } catch (_: Exception) { }
            try { root.recycle() } catch (_: Exception) { }
        }
    }

    /**
     * Give the user's clipboard back after a successful paste: the paste
     * already happened, so the dictation text no longer needs to sit
     * there. Only restores if the clipboard still holds our text — the
     * user may have copied something else in the meantime.
     */
    private fun restoreClipboardAfterPaste(
        clipboard: ClipboardManager?,
        previousClip: ClipData?,
        text: String
    ) {
        if (previousClip == null) return
        pasteSeq++
        val seq = pasteSeq
        mainHandler.postDelayed({
            // Only the latest paste restores: an older restore must never
            // clobber a newer dictation's clipboard.
            if (seq != pasteSeq) return@postDelayed
            try {
                val current = clipboard?.primaryClip
                val currentText = if (current != null && current.itemCount > 0) {
                    current.getItemAt(0)?.text?.toString()
                } else {
                    null
                }
                if (currentText == text) {
                    clipboard?.setPrimaryClip(previousClip)
                }
            } catch (_: Exception) { }
        }, 1000)
    }

    /** True when the keyboard is open: a field exists even if no editable node was found. */
    private fun isImeOpen(): Boolean {
        return try {
            windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        } catch (_: Exception) {
            false
        }
    }

    /** Stage a failed insert for the event-driven retries (wave 2). */
    private fun stagePending(text: String, hostPackage: String?, onResult: (Boolean) -> Unit) {
        // A new dictation replaces an older pending one; the older caller
        // is dropped silently — its text is superseded.
        clearPendingState()
        pendingId++
        val id = pendingId
        pendingText = text
        pendingHostPackage = hostPackage
        pendingResult = onResult
        pendingStagedAt = SystemClock.uptimeMillis()
        for (delayMs in retryDelays) {
            mainHandler.postDelayed({ if (id == pendingId) tryPendingInsert() }, delayMs)
        }
        mainHandler.postDelayed({ if (id == pendingId) checkGiveUp(id) }, retryDelays.last() + 300)
    }

    /** One attempt at a staged insert, from an event or a timed retry. */
    private fun tryPendingInsert() {
        val text = pendingText ?: return
        if (pendingResult == null) return
        lastAttemptTime = SystemClock.uptimeMillis()
        when (attemptInsert(text, pendingHostPackage)) {
            InsertOutcome.INSERTED -> {
                val result = pendingResult
                clearPendingState()
                result?.invoke(true)
            }
            InsertOutcome.WRONG_APP -> {
                val result = pendingResult
                clearPendingState()
                result?.invoke(false)
            }
            InsertOutcome.RETRY -> { /* keep waiting; timed retries continue */ }
        }
    }

    /** Give up a staged insert — unless the keyboard is open, which means a field exists. */
    private fun checkGiveUp(id: Int) {
        if (id != pendingId) return
        val stagedAt = pendingStagedAt
        if (pendingText == null || stagedAt == 0L) return
        val elapsed = SystemClock.uptimeMillis() - stagedAt
        if (isImeOpen() && elapsed < pendingHardCapMs) {
            mainHandler.postDelayed({ checkGiveUp(id) }, 500)
            return
        }
        val result = pendingResult
        clearPendingState()
        result?.invoke(false)
    }

    private fun clearPendingState() {
        pendingText = null
        pendingHostPackage = null
        pendingResult = null
        pendingStagedAt = 0L
    }

    /** Packages that can be a dictation's home app. Ours, the system UI, launchers and keyboards are not. */
    private fun isTrackablePackage(pkg: String): Boolean {
        if (pkg == packageName) return false
        if (pkg == "com.android.systemui" || pkg == "android") return false
        if (pkg.contains("launcher", ignoreCase = true)) return false
        if (pkg == defaultImePackage()) return false
        return true
    }

    private fun defaultImePackage(): String? {
        if (cachedImePackage == null) {
            cachedImePackage = try {
                Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                    ?.substringBefore('/')
            } catch (_: Exception) {
                null
            }
        }
        return cachedImePackage
    }
}
