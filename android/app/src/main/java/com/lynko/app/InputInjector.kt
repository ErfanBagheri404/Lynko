package com.lynko.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AccessibilityService for desktop-injected taps and swipes.
 * Requires the user to enable "Lynko" in Settings → Accessibility once.
 * No root needed.
 */
class LynkoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: LynkoAccessibilityService? = null
            private set

        fun enabled(ctx: Context): Boolean {
            val expected = ctx.packageName + "/" + LynkoAccessibilityService::class.java.canonicalName
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
        Log.i("lynko", "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

object InputInjector {

    fun tap(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: run {
            Log.w("lynko", "tap ignored — accessibility service not enabled")
            return false
        }
        val metrics = ctx.resources.displayMetrics
        val x = x01 * metrics.widthPixels
        val y = y01 * metrics.heightPixels
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.i("lynko", "tap ($x01,$y01)")
        return true
    }

    fun swipe(ctx: Context, x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: run {
            Log.w("lynko", "swipe ignored — accessibility service not enabled")
            return false
        }
        val metrics = ctx.resources.displayMetrics
        val path = Path().apply {
            moveTo(x1 * metrics.widthPixels, y1 * metrics.heightPixels)
            lineTo(x2 * metrics.widthPixels, y2 * metrics.heightPixels)
        }
        val stroke = if (Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(path, 0, 250, true)
        } else {
            GestureDescription.StrokeDescription(path, 0, 250)
        }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.i("lynko", "swipe")
        return true
    }

    /** Navigation keys that need a global action, not a gesture. */
    fun navKey(name: String): Boolean {
        val svc = LynkoAccessibilityService.instance ?: run {
            Log.w("lynko", "nav key ignored — accessibility service not enabled")
            return false
        }
        val action = when (name) {
            "BACK" -> AccessibilityService.GLOBAL_ACTION_BACK
            "HOME" -> AccessibilityService.GLOBAL_ACTION_HOME
            "RECENTS" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "NOTIFICATIONS" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return svc.performGlobalAction(action)
    }

    /** Type `text` into whatever field is currently focused — no Lynko IME.
     * Reads the node's existing text and appends (covers the common empty or
     * tap-at-end case; there is no reliable cursor index via accessibility). */
    fun typeText(ctx: Context, text: String): Boolean {
        val node = focusedEditable() ?: run {
            Log.w("lynko", "type ignored — no focused input field")
            return false
        }
        val cur = node.text?.toString() ?: ""
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo
                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + text
            )
        }
        val ok = node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
        )
        node.recycle()
        Log.i("lynko", "typeText set=$ok (${text.length} chars)")
        return ok
    }

    /** Backspace: drop the last character of the focused field's text. */
    fun backspace(): Boolean {
        val node = focusedEditable() ?: return false
        val cur = node.text?.toString() ?: ""
        if (cur.isEmpty()) { node.recycle(); return true }
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo
                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur.dropLast(1)
            )
        }
        val ok = node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
        )
        node.recycle()
        return ok
    }

    /** Enter: commit via the IME action the editor exposes (send/search/go).
     * Falls back to appending a newline for true multiline fields. */
    fun enter(): Boolean {
        val node = focusedEditable() ?: return false
        var ok = false
        // ACTION_IME_ENTER exists at runtime on API 33+; read by reflection so
        // older compile SDKs don't break the build.
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val id = android.view.accessibility.AccessibilityNodeInfo::class.java
                    .getField("ACTION_IME_ENTER").getInt(null)
                ok = node.performAction(id)
            } catch (e: Exception) { ok = false }
        }
        if (!ok) ok = typeTextToNode(node, "\n")
        node.recycle()
        return ok
    }

    private fun typeTextToNode(node: android.view.accessibility.AccessibilityNodeInfo, add: String): Boolean {
        val cur = node.text?.toString() ?: ""
        val args = android.os.Bundle().apply {
            putCharSequence(
                android.view.accessibility.AccessibilityNodeInfo
                    .ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + add
            )
        }
        return node.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args
        )
    }

    private fun focusedEditable(): android.view.accessibility.AccessibilityNodeInfo? {
        val svc = LynkoAccessibilityService.instance ?: return null
        val root = svc.rootInActiveWindow ?: return null
        var focused = root.findFocus(android.view.accessibility
            .AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused == null) {
            focused = findEditable(root)
        }
        root.recycle()
        return focused
    }

    private fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
            child.recycle()
        }
        return null
    }
}
