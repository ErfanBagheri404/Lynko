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

    // State is minimal on purpose: the stroke chain itself is the only
    // state. No busy-gating — pipelined continuations either dispatch
    // (return true) or the chain is dropped and self-heals on the next
    // move. A boolean gate that fails to reset is how "nothing works
    // anymore" happens.

    private const val SEG_MS = 300L      // live stroke window (VPN-jitter tolerant)
    private const val MIN_PATH_MS = 120L // replayed gesture: never faster than a real tap-hold
    private const val MAX_PATH_MS = 900L // ...never slower than a long-press trigger
    private const val MAX_POINTS = 64

    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragLastPx: Pair<Float, Float>? = null
    private var dragLive = false
    private var dragT0 = 0L
    private val dragPts = ArrayList<Pair<Float, Float>>(MAX_POINTS)

    fun dragStart(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        val sz = ScreenSize.size(ctx)
        val px = x01.coerceIn(0f, 1f) * sz.x
        val py = y01.coerceIn(0f, 1f) * sz.y
        dragStroke = null
        dragLive = false
        dragPts.clear()
        dragPts.add(px to py)
        dragLastPx = px to py
        dragT0 = android.os.SystemClock.uptimeMillis()
        // A zero-length stroke is illegal; nudge 1px so the press registers.
        val path = Path().apply { moveTo(px, py); lineTo(px + 1f, py) }
        val down = GestureDescription.StrokeDescription(path, 0L, SEG_MS, true)
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(down).build(), null, null)
        } catch (t: Throwable) {
            Log.w("lynko", "dragStart threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (ok) { dragStroke = down; dragLive = true }
        // Returns true regardless: the drag resolves on release either way
        // (live chain, or the recorded replay below).
        Log.i("lynko", "dragStart ($x01,$y01) live=$dragLive")
        return true
    }

    fun dragMove(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        val sz = ScreenSize.size(ctx)
        val px = x01.coerceIn(0f, 1f) * sz.x
        val py = y01.coerceIn(0f, 1f) * sz.y
        if (dragPts.size < MAX_POINTS) dragPts.add(px to py)
        val prev = dragStroke
        val last = dragLastPx
        if (prev == null || last == null || !dragLive) return false // recorded: buffered only
        // Degenerate (<1px) segment: illegal, so advance the anchor but skip
        // the dispatch — the stroke timeline keeps tracking the pointer.
        if (kotlin.math.abs(px - last.first) < 1f && kotlin.math.abs(py - last.second) < 1f) {
            dragLastPx = px to py
            return true
        }
        val path = Path().apply { moveTo(last.first, last.second); lineTo(px, py) }
        val cont = try { prev.continueStroke(path, 0L, SEG_MS, true) } catch (t: Throwable) { null }
        if (cont == null) {
            dragLive = false; dragStroke = null
            Log.w("lynko", "dragMove chain expired -> recorded mode")
            return false
        }
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(cont).build(), null, null)
        } catch (t: Throwable) { false }
        if (ok) {
            dragStroke = cont
            dragLastPx = px to py
        } else {
            dragLive = false; dragStroke = null
            Log.w("lynko", "dragMove dispatch refused -> recorded mode")
        }
        return ok
    }

    fun dragEnd(ctx: Context, x01: Float, y01: Float): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        val sz = ScreenSize.size(ctx)
        val px = x01.coerceIn(0f, 1f) * sz.x
        val py = y01.coerceIn(0f, 1f) * sz.y
        if (dragPts.size < MAX_POINTS) dragPts.add(px to py)
        val prev = dragStroke
        val last = dragLastPx
        val live = dragLive && prev != null && last != null
        dragStroke = null
        dragLastPx = null
        dragLive = false
        val started = dragT0
        dragT0 = 0L
        val pts = ArrayList(dragPts)
        dragPts.clear()

        if (live && prev != null && last != null) {
            val ex = if (kotlin.math.abs(px - last.first) < 1f) last.first + 1f else px
            val ey = if (kotlin.math.abs(py - last.second) < 1f) last.second + 1f else py
            val path = Path().apply { moveTo(last.first, last.second); lineTo(ex, ey) }
            val up = try { prev.continueStroke(path, 0L, 15L, false) } catch (t: Throwable) { null }
            if (up != null) {
                val ok = try {
                    svc.dispatchGesture(GestureDescription.Builder().addStroke(up).build(), null, null)
                } catch (t: Throwable) { false }
                if (ok) {
                    Log.i("lynko", "dragEnd ($x01,$y01) live lift")
                    return true
                }
            }
            Log.w("lynko", "dragEnd live lift failed -> replaying full path")
        }

        // Recorded replay (also the live-mode fallback): the ENTIRE stroke as
        // one self-contained gesture — every buffered point, ending in a real
        // lift. This is what makes a swipe impossible to lose or strand: no
        // continuation windows to expire, nothing can hold the finger down.
        if (pts.isEmpty()) return true
        val first = pts.first()
        val lastPt = if (pts.last() == first && pts.size == 1) (first.first + 1f) to first.second else pts.last()
        val path = Path().apply {
            moveTo(first.first, first.second)
            for ((qx, qy) in pts) lineTo(qx, qy)
            lineTo(lastPt.first + 0.5f, lastPt.second)
        }
        // Replay over the time the user ACTUALLY took (clamped): a fast flick
        // keeps its velocity so the phone adds scroll momentum; a slow pull
        // stays a drag (drag-to-reorder, sliders).
        val elapsed = (android.os.SystemClock.uptimeMillis() - started)
            .coerceIn(MIN_PATH_MS, MAX_PATH_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, elapsed)
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) { false }
        Log.i("lynko", "dragEnd ($x01,$y01) replay ${pts.size}pts/${elapsed}ms ok=$ok")
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
