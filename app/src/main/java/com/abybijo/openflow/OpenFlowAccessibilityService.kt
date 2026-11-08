package com.abybijo.openflow

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inserts dictated text into the focused text field of another app.
 *
 * Order of attempts:
 *  1. Splice at the cursor (reads the field text + selection, writes it back
 *     with the dictation inserted). The clipboard is never touched.
 *  2. Copy to clipboard + ACTION_PASTE.
 *  3. Caller falls back to clipboard-only.
 *
 * Never inserts into password fields, and never into banking or
 * password-manager apps — those always fall back to the clipboard.
 * The service only looks at the field that has input focus, and only
 * when a dictation has just finished.
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
        fun insertText(context: Context, text: String): Boolean {
            val service = instance ?: return false
            return service.insert(text)
        }

        fun copyToClipboard(context: Context, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("OpenFlow", text))
        }
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

    private fun insert(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Exception) {
            null
        } ?: return false
        try {
            if (node.isPassword) return false
            val pkg = node.packageName?.toString().orEmpty().lowercase()
            if (SENSITIVE_PACKAGES.any { pkg.contains(it) }) return false
            if (!node.isEditable) return false

            // 1. Splice at the cursor.
            val existing = node.text?.toString().orEmpty()
            val start = node.textSelectionStart.coerceIn(0, existing.length)
            val end = node.textSelectionEnd.coerceIn(start, existing.length)
            if (node.textSelectionStart >= 0) {
                val merged = existing.substring(0, start) + text + existing.substring(end)
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    merged
                )
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    return true
                }
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
