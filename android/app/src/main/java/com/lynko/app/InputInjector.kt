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

        /** Fully operational: listed in Settings AND the service is actually
         *  bound (instance != null). MIUI leaves the Settings string intact
         *  after a force-stop while the service itself is dead — the exact
         *  state where taps are silently swallowed. */
        fun operational(ctx: Context): Boolean = instance != null && enabled(ctx)
    }

    override fun onServiceConnected() {
        instance = this
        Log.i("lynko", "accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i("lynko", "accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // MUST touch the event. MIUI samples whether the handler actually
        // processes anything and marks a service with a no-op body as
        // "not working" ("tap for info"), then drops the binding on the next
        // app launch — the user had to re-enable it every single time. We only
        // need dispatchGesture, but reading the event proves the handler is
        // live. Cheap: one field read, no allocation.
        lastEventAt = System.currentTimeMillis()
        lastEventType = event?.eventType ?: 0
    }

    /** Wall clock of the last event the system handed us. Not used for logic —
     *  the field read inside onAccessibilityEvent is what keeps MIUI from
     *  flagging the service. Kept for logcat/debug visibility. */
    @Volatile private var lastEventAt = 0L
    @Volatile private var lastEventType = 0

    override fun onInterrupt() {
        Log.w("lynko", "accessibility service interrupted")
    }
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
        val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80)
        // The RESULT matters: MIUI parks the service in "Crashed" after a
        // swipe-kill and then refuses every dispatch. Returning true anyway
        // hid that from the desktop (no input_error toast, no gesture).
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) { false }
        Log.i("lynko", "tap ($x01,$y01) ok=$ok")
        return ok
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
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) { false }
        Log.i("lynko", "swipe ok=$ok")
        return ok
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

    // SEG duration is ADAPTIVE, not fixed. A fast flick has moves arriving
    // ~8-16ms apart; a slow drag can gap >100ms. A fixed window is wrong for
    // both: 300ms stretched every swipe to seconds ("smooth but not
    // instant"), 60ms expired on slow drags ("triggers only when I release",
    // because expiry dropped the stroke into recorded-replay mode). Each
    // segment now lasts ≈ the REAL gap between the last two pointer samples
    // plus a jitter margin, so the injected gesture replays at wall-clock
    // speed (sharp) and no arrival outruns its window (live).
    private const val SEG_MARGIN_MS = 25L   // jitter allowance over measured gap
    private const val SEG_MIN_MS = 30L      // floor: sub-frame gaps (hi-poll mice) need a window
    private const val SEG_MAX_MS = 120L     // ceiling: a 120ms window is already a pause
    private const val DRAG_DOWN_MS = 80L    // initial press: LAN delivers the 1st move in ~15ms
    private const val MIN_PATH_MS = 120L // replayed gesture: never faster than a real tap-hold
    private const val MAX_PATH_MS = 900L // ...never slower than a long-press trigger
    private const val MAX_POINTS = 64

    // The live stroke-chain (dragStart press → pipelined continuations) is
    // the elegant path, but it is FRAGILE on real devices: if the first
    // continuation misses the willContinue window the phone finger is left
    // frozen mid-motion, and one stuck finger rejects EVERY later gesture
    // (the reported "swipes stopped working entirely"). Pure-replay mode
    // never strands a finger: the whole stroke is buffered during the drag
    // and injected as ONE self-contained gesture (lift included) on release.
    // Set LIVE_CHAIN=true to re-enable the experimental live path.
    private const val LIVE_CHAIN = false

    private var dragStroke: GestureDescription.StrokeDescription? = null
    private var dragLastPx: Pair<Float, Float>? = null
    private var dragLive = false
    private var dragT0 = 0L
    private var dragLastSegT = 0L
    private var dragPrevGap = 0L
    private var dragPendingDt = 0L        // accumulated dt while waiting to dispatch next segment
    private var dragSegEnd = 0L           // uptime when the current segment ends (for coalescing)
    private val dragPts = ArrayList<Pair<Float, Float>>(MAX_POINTS)

    /** Segment window for the NEXT continuation.
     *  Uses max(desktop dt, arrival gap) + margin. The acceptance window
     *  must cover both the real pointer interval (dt) and the network jitter
     *  (arrival gap), otherwise a late IPC arrives after the chain expired. */
    private fun nextSegMs(now: Long, dtMs: Long): Long {
        val arrivalGap = now - dragLastSegT
        return (maxOf(dtMs, arrivalGap) + SEG_MARGIN_MS).coerceIn(SEG_MIN_MS, SEG_MAX_MS)
    }

    fun dragStart(ctx: Context, x01: Float, y01: Float, dtMs: Long): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        val sz = ScreenSize.size(ctx)
        val px = x01.coerceIn(0f, 1f) * sz.x
        val py = y01.coerceIn(0f, 1f) * sz.y
        dragStroke = null
        dragLive = false
        dragPts.clear()
        dragPts.add(px to py)
        dragLastPx = px to py
        val now = android.os.SystemClock.uptimeMillis()
        dragT0 = now
        dragLastSegT = now
        dragPrevGap = DRAG_DOWN_MS
        if (!LIVE_CHAIN) {
            // Buffer-only: nothing is dispatched until dragEnd injects the
            // whole stroke. Can never leave a finger down on the phone.
            Log.i("lynko", "dragStart ($x01,$y01) buffer-only")
            DevLog.add("dragStart ($x01,$y01) buffer")
            return true
        }
        // A zero-length stroke is illegal; nudge 1px so the press registers.
        val path = Path().apply { moveTo(px, py); lineTo(px + 1f, py) }
        val down = GestureDescription.StrokeDescription(path, 0L, DRAG_DOWN_MS, true)
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(down).build(), null, null)
        } catch (t: Throwable) {
            Log.w("lynko", "dragStart threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        if (ok) { dragStroke = down; dragLive = true; dragSegEnd = now + DRAG_DOWN_MS; dragPendingDt = 0L }
        // Returns true regardless: the drag resolves on release either way
        // (live chain, or the recorded replay below).
        Log.i("lynko", "dragStart ($x01,$y01) live=$dragLive")
        DevLog.add("dragStart ($x01,$y01) live=$dragLive")
        return true
    }

    fun dragMove(ctx: Context, x01: Float, y01: Float, dtMs: Long): Boolean {
        val svc = LynkoAccessibilityService.instance ?: return false
        val sz = ScreenSize.size(ctx)
        val px = x01.coerceIn(0f, 1f) * sz.x
        val py = y01.coerceIn(0f, 1f) * sz.y
        if (dragPts.size < MAX_POINTS) dragPts.add(px to py)
        val prev = dragStroke
        val last = dragLastPx
        if (prev == null || last == null || !dragLive) return false // recorded: buffered only
        val now = android.os.SystemClock.uptimeMillis()
        // Degenerate (<1px) segment: illegal, so advance the anchor but skip
        // the dispatch — the stroke timeline keeps tracking the pointer.
        if (kotlin.math.abs(px - last.first) < 1f && kotlin.math.abs(py - last.second) < 1f) {
            dragLastPx = px to py
            return true
        }
        // COALESCE bursty arrivals: if the previous segment is still within
        // its window (network delivered several samples bunched together),
        // don't dispatch a fresh 30ms-floor segment per sample — that
        // stretches an 80ms flick into 330ms of stroke time. Accumulate the
        // dt and advance the anchor; the next dispatch spans them all.
        if (now < dragSegEnd) {
            dragPendingDt += maxOf(dtMs, 1L)
            dragLastPx = px to py
            return true
        }
        val arrivalGap = now - dragLastSegT
        val segMs = (maxOf(dtMs, arrivalGap) + SEG_MARGIN_MS + dragPendingDt)
            .coerceIn(SEG_MIN_MS, SEG_MAX_MS)
        dragPendingDt = 0L
        val path = Path().apply { moveTo(last.first, last.second); lineTo(px, py) }
        val cont = try { prev.continueStroke(path, 0L, segMs, true) } catch (t: Throwable) { null }
        if (cont == null) {
            dragLive = false; dragStroke = null
            Log.w("lynko", "dragMove chain expired (gap ${arrivalGap}ms) -> recorded mode")
            DevLog.add("chain expired gap=${arrivalGap}ms")
            return false
        }
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(cont).build(), null, null)
        } catch (t: Throwable) {
            Log.w("lynko", "dragMove threw ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        DevLog.add("dragMove seg=${segMs}ms dt=${dtMs} gap=${arrivalGap}ms live=$ok")
        if (ok) {
            dragStroke = cont
            dragLastPx = px to py
            dragPrevGap = arrivalGap
            dragLastSegT = now
            dragSegEnd = now + segMs
        } else {
            dragLive = false; dragStroke = null
            Log.w("lynko", "dragMove dispatch refused -> recorded mode")
            DevLog.add("dispatch refused -> recorded")
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
        val now = android.os.SystemClock.uptimeMillis()
        val live = dragLive && prev != null && last != null
        dragStroke = null
        dragLastPx = null
        dragLive = false
        dragSegEnd = 0L
        dragPendingDt = 0L
        val started = dragT0
        dragT0 = 0L
        val pts = ArrayList(dragPts)
        dragPts.clear()

        if (live && prev != null && last != null) {
            // Lift segment: carry the leftover real dt plus the tail arrival
            // gap, so a lift that follows a coalesced burst still spans its
            // true timeline — otherwise the chain snaps at the last hop.
            val lift = (now - dragLastSegT + SEG_MARGIN_MS + dragPendingDt).coerceIn(15L, 120L)
            val ex = if (kotlin.math.abs(px - last.first) < 1f) last.first + 1f else px
            val ey = if (kotlin.math.abs(py - last.second) < 1f) last.second + 1f else py
            val path = Path().apply { moveTo(last.first, last.second); lineTo(ex, ey) }
            val up = try { prev.continueStroke(path, 0L, lift, false) } catch (t: Throwable) { null }
            if (up != null) {
                val liftOk = try {
                    svc.dispatchGesture(GestureDescription.Builder().addStroke(up).build(), null, null)
                } catch (t: Throwable) { false }
                Log.i("lynko", "dragEnd live lift seg=${lift}ms ok=${liftOk}")
                if (liftOk) {
                    Log.i("lynko", "dragEnd ($x01,$y01) live lift")
                    DevLog.add("dragEnd live lift")
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
        val elapsed = (now - started).coerceIn(MIN_PATH_MS, MAX_PATH_MS)
        val stroke = GestureDescription.StrokeDescription(path, 0L, elapsed)
        val ok = try {
            svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (t: Throwable) { false }
        Log.i("lynko", "dragEnd ($x01,$y01) replay ${pts.size}pts/${elapsed}ms ok=$ok")
        DevLog.add("dragEnd replay ${pts.size}pts/${elapsed}ms ok=$ok")
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
