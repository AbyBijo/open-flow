package com.abybijo.openflow

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Inserts dictated text into the focused text field of another app.
 *
 * Order of attempts (retried up to 3 times, ~200 ms apart, because focus
 * can lag behind the overlay tap):
 *  1. Focus the field, then splice at the cursor — or append at the end
 *     when the field reports no selection (common in WebView fields).
 *     The clipboard is never touched. A field whose whole content is just
 *     its placeholder hint (e.g. WhatsApp's "Message") is treated as
 *     empty, so the hint is never pasted in front of a dictation.
 *  2. Copy to clipboard + ACTION_PASTE.
 *  3. Caller falls back to clipboard-only.
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

        private val SENSITIVE_PACKAGES = listOf(
            "bank", "banking", "1password", "lastpass", "bitwarden",
            "keepass", "dashlane", "password", "paytm", "phonepe", "gpay"
        )

        /** Returns true if the text landed in a field. False = caller should use clipboard. */
        suspend fun insertText(context: Context, text: String): Boolean {
            val service = instance ?: return false
            return service.insertWithRetry(text)
        }

        fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
        }
    }

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No passive monitoring: we only act when insert() is called.
    }

    override fun onInterrupt() {}

    /** Retry the whole insert: the field may not be focused yet right after the overlay tap. */
    private suspend fun insertWithRetry(text: String): Boolean {
        repeat(3) { attempt ->
            if (attemptInsert(text)) return true
            if (attempt < 2) delay(200)
        }
        return false
    }

    private fun attemptInsert(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        } ?: findEditableNode(root)
        if (node == null) {
            try { root.recycle() } catch (_: Exception) { }
            return false
        }
        try {
            if (node.isPassword) return false
            val pkg = node.packageName?.toString().orEmpty().lowercase()
            if (SENSITIVE_PACKAGES.any { pkg.contains(it) }) return false
            if (!node.isEditable) return false

            // Focus the field first: without focus the splice and the paste
            // both silently do nothing.
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            // 1. Splice at the cursor. When the field reports no selection
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
                // Verify the text actually landed before reporting success:
                // some fields accept the action and silently drop it.
                try { node.refresh() } catch (_: Exception) { }
                val after = try { node.text?.toString().orEmpty() } catch (_: Exception) { "" }
                if (after.contains(text)) return true
                // Not there — fall through to clipboard + paste.
            }
            // 2. Clipboard + paste.
            copyToClipboard(this, text)
            if (node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            return false
        } catch (_: Exception) {
            return false
        } finally {
            try { node.recycle() } catch (_: Exception) { }
            try { root.recycle() } catch (_: Exception) { }
        }
    }
}
