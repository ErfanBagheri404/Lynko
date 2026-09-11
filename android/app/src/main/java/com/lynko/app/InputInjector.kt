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
        val sz = ScreenSize.size(ctx)
        val x = x01 * sz.x
        val y = y01 * sz.y
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
        val sz = ScreenSize.size(ctx)
        val path = Path().apply {
            moveTo(x1 * sz.x, y1 * sz.y)
            lineTo(x2 * sz.x, y2 * sz.y)
        }
        // Final stroke (no willContinue): the pointer must LIFT at the end.
        // A willContinue stroke holds the finger down awaiting a continuation
        // gesture that never comes — apps see a pointer frozen mid-motion.
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.i("lynko", "swipe")
        return true
    }

    // ---- live drag (stroke continuation) --------------------------------
    // One physical drag on the desktop = one continuing stroke on the phone,
    // replayed segment by segment as the pointer moves.
    //
    // continueStroke semantics: the continuation is appended to the PREVIOUS
    // stroke's timeline and MUST be dispatched while that stroke is still
    // running. The first implementation used 16ms segment windows — fine on
    // LAN, fatal over VPN where packets land 50-300ms apart: the window
    // expires, dispatchGesture rejects the orphaned continuation, the finger
    // sticks down and every later gesture is refused. Hence:
    //  - long segment windows (tolerant of jittered arrival);
    //  - continuations are dispatched IMMEDIATELY, without waiting for the
    //    previous segment's onCompleted — Android pipelines them on the
    //    input channel. Waiting serialized 500ms per segment (the
    //    "gesture only fires after I release" bug).
    //  - self-heal: if the chain does break (window expired), close the old
    //    stroke and restart at the newest point — the drag survives.
    //
    // Sub-pixel segments are coalesced: injecting a 0.2px "move" is not just
    // wasteful, a zero-length segment is ILLEGAL and gets the whole gesture
    // cancelled. dragEnd nudges 1px when the segment is degenerate.

    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragLast: Pair<Float, Float>? = null
    @Volatile private var gestureBusy = false
    private var pendingMove: Pair<Float, Float>? = null
    private var dragCtx: Context? = null

    fun dragStart(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        dragCtx = ctx.applicationContext
        val sz = ScreenSize.size(ctx)
        val x = x01 * sz.x
        val y = y01 * sz.y
        val path = Path().apply { moveTo(x, y) }
        // A zero-length stroke is illegal; nudge 1px so the down registers.
        path.lineTo(x + 1f, y)
        dragStroke = GestureDescription.StrokeDescription(path, 0, 60, true)
        dragLast = Pair(x01, y01)
        pendingMove = null
        gestureBusy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { gestureBusy = false; flushPending() }
            override fun onCancelled(g: GestureDescription?) { gestureBusy = false; flushPending() }
        }
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(dragStroke!!).build(), cb, null)
        if (!ok) gestureBusy = false
        Log.i("lynko", "dragStart ($x01,$y01) ok=$ok")
        return ok
    }

    fun dragMove(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        if (dragStroke == null) {
            // Chain broken mid-drag (jitter/stale stroke): re-open at the
            // latest point so the drag continues instead of dying.
            return dragStart(ctx, x01, y01)
        }
        if (gestureBusy) {
            // A segment is still being delivered — keep only the newest point
            // and continue from it when the callback fires. Stale queued
            // points are skipped: the stroke chases the pointer, not history.
            pendingMove = Pair(x01, y01)
            return true
        }
        continueFrom(ctx, svc, x01, y01)
        return true
    }

    private fun continueFrom(ctx: Context, svc: LynkoAccessibilityService, x01: Float, y01: Float) {
        val prev = dragStroke
        val last = dragLast
        if (prev == null || last == null) return
        val sz = ScreenSize.size(ctx)
        val nx = x01 * sz.x
        val ny = y01 * sz.y
        val lx = last.first * sz.x
        val ly = last.second * sz.y
        if (kotlin.math.abs(nx - lx) < 1f && kotlin.math.abs(ny - ly) < 1f) {
            // Degenerate segment: skip dispatch but advance the anchor so the
            // stroke timeline keeps tracking the pointer.
            dragLast = Pair(x01, y01)
            return
        }
        val path = Path().apply {
            moveTo(lx, ly)
            lineTo(nx, ny)
        }
        val stroke = prev.continueStroke(path, 0, 500, true)
        dragStroke = stroke
        dragLast = Pair(x01, y01)
        gestureBusy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { gestureBusy = false; flushPending() }
            override fun onCancelled(g: GestureDescription?) { gestureBusy = false; flushPending() }
        }
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), cb, null)
        if (!ok) {
            // Window already expired: drop the dead chain; the next dragMove
            // re-opens at the newest point via the dragStroke==null path.
            gestureBusy = false
            dragStroke = null
            dragLast = Pair(x01, y01)
        }
    }

    private fun flushPending() {
        val ctx = dragCtx ?: return
        val svc = LynkoAccessibilityService.instance ?: return
        val p = pendingMove ?: return
        pendingMove = null
        if (dragStroke == null) { dragStart(ctx, p.first, p.second); return }
        continueFrom(ctx, svc, p.first, p.second)
    }

    fun dragEnd(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        pendingMove = null
        val prev = dragStroke
        val last = dragLast
        dragStroke = null
        dragLast = null
        dragCtx = null
        if (prev == null || last == null) return true // nothing down: no-op
        val sz = ScreenSize.size(ctx)
        val nx = x01 * sz.x
        val ny = y01 * sz.y
        val lx = last.first * sz.x
        val ly = last.second * sz.y
        // Degenerate final segment (tap-release, tiny drag): nudge 1px so the
        // continuation is legal — a zero-length line aborts the WHOLE gesture
        // and the finger stays down forever.
        val ex = if (kotlin.math.abs(nx - lx) < 1f) lx + 1f else nx
        val ey = if (kotlin.math.abs(ny - ly) < 1f) ly + 1f else ny
        val path = Path().apply {
            moveTo(lx, ly)
            lineTo(ex, ey)
        }
        val stroke = prev.continueStroke(path, 0, 100, false) // lifts the finger
        gestureBusy = true
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { gestureBusy = false }
            override fun onCancelled(g: GestureDescription?) { gestureBusy = false }
        }
        val ok = svc.dispatchGesture(
            GestureDescription.Builder().addStroke(stroke).build(), cb, null)
        if (!ok) {
            gestureBusy = false
            // Last resort: a fresh tap-like stroke guarantees nothing stays
            // pressed on the phone.
            val lift = Path().apply {
                moveTo(ex, ey)
                lineTo(ex + 1f, ey)
            }
            svc.dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(lift, 0, 40))
                    .build(), null, null)
        }
        Log.i("lynko", "dragEnd ($x01,$y01) ok=$ok")
        return ok
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
