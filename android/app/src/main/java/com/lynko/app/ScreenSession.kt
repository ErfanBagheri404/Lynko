package com.lynko.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/** MediaProjection capture -> JPEG frames. Projection is created ONCE by
 * LinkService (consent tokens are single-use) and passed in here.
 *
 * Threading: ALL pipeline work (build/teardown/frame callbacks) runs on the
 * frame handler thread — closing the reader from another thread while a
 * callback is mid-copy segfaults (Bitmap_copyPixelsFromBuffer use-after-free).
 *
 * Screen lock: with AUTO_MIRROR, turning the physical display off stops the
 * compositor feed and on MIUI it does NOT resume on unlock — the mirror goes
 * permanently black. Fix: rebuild the whole capture pipeline (fresh
 * ImageReader + fresh VirtualDisplay re-attached to the same projection)
 * whenever the screen turns back on. */
class ScreenSession(
    private val context: Context,
    private val projection: MediaProjection,
    /** Pipeline creation failed (e.g. consent token consumed/expired —
     * SecurityException from createVirtualDisplay). MUST be handled by the
     * owner: without this the exception dies on the frame HandlerThread and
     * crashes the whole process (observed FATAL EXCEPTION: lynko-frames). */
    private val onFatal: (String) -> Unit = {},
    /** First real frame captured — the moment mirroring is genuinely live.
     * Owners set "mirroring" state here, not at start(), so the UI never
     * claims streaming before frames exist. */
    private val onLive: () -> Unit = {},
    private val onFrame: (ByteArray) -> Unit,
) {
    private val metrics = context.resources.displayMetrics
    // Capture AT 720p directly: the compositor scales on the GPU while
    // composing, so the phone CPU never touches full-res pixels. Encoding
    // 4x fewer pixels is 4x faster AND the desktop decodes 4x smaller
    // frames. Touch coords stay normalized — taps map onto the 720p
    // surface exactly as they did onto the native one.
    //
    // Size source: PHYSICAL panel via ScreenSize (system bars included).
    // Service displayMetrics returns the app window (2400-136=2264 on this
    // device), which produced a 720x1510 VirtualDisplay on a 1080x2400
    // panel — wrong aspect, taps mapped 37% off vertically.
    private val realSize = ScreenSize.size(context)
    // Capture at 540p: 720p frames saturated the desktop Tauri IPC bridge
    // (211 drops/25s in production logs). 540p halves the per-frame bytes
    // while still looking sharp on the mirrored window. Touch coords stay
    // normalized — same mapping as 720p.
    private var scaleToDisplay = (540f / realSize.x).coerceAtMost(1f)
    private var width = (realSize.x * scaleToDisplay).toInt().coerceAtLeast(360)
    private var height = (realSize.y * scaleToDisplay).toInt().coerceAtLeast(720)
    private var density = (metrics.densityDpi * scaleToDisplay).toInt().coerceAtLeast(120)
    private var lastFrameAt = 0L
    private var jpegQuality = 65
    private var drops = 0

    /** Capture-width target the desktop asked for (0 = keep the 540p default). */
    @Volatile private var requestedMaxWidth = 0
    /** JPEG quality ceiling the desktop asked for (0 = keep 65). The
     *  adaptive backoff may still step below it under link pressure. */
    @Volatile private var requestedQuality = 0

    /** Adaptive frame pacing. 33ms = 30fps target on a healthy LAN; the
     *  old fixed 66ms cap made every mirror 15fps by construction. On any
     *  send-side drop (link saturated) the interval backs off toward 66ms
     *  so a congested link degrades to smooth-15 rather than stutter-and-
     *  queue; it recovers one step per clean second. */
    @Volatile private var frameIntervalMs = 33L
    private var cleanFrames = 0
    private var lastAdaptAt = 0L

    // Reused per frame — zero steady-state allocation, no GC churn. Sized
    // from the CURRENT width, so a quality change rebuilds them.
    private var rawBitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private var row: ByteArray = ByteArray(width * 4)
    private val baos = ByteArrayOutputStream(256 * 1024)

    /** Backpressure feedback from LinkService: when frames are being dropped
     * on the send side, step JPEG quality down (floor 50) until the link
     * drains; recover slowly when frames go through again. */
    fun noteDrop() {
        drops++
        // Back off pacing immediately — a dropped frame means the link is
        // congested; sending the next one sooner only queues it behind the
        // backlog. Steps 33→40→50→66ms, floors at 15fps.
        if (frameIntervalMs < 66L) frameIntervalMs = (frameIntervalMs + 10L).coerceAtMost(66L)
        if (drops % 3 == 0 && jpegQuality > 40) jpegQuality -= 10
    }

    /** JPEG quality ceiling: the desktop's preset wins, otherwise the
     *  tuned 65 default. The adaptive backoff may dip BELOW this under link
     *  pressure but must never recover ABOVE it — otherwise an eco preset
     *  would silently drift back up to 65. */
    private fun qualityCeiling() = if (requestedQuality > 0) requestedQuality else 65

    /** Called after a frame goes through cleanly — recovers pacing toward
     *  30fps one step per clean second (no thrash: one step per second max,
     *  decided by wall clock, not per frame). */
    fun noteClean() {
        val now = System.currentTimeMillis()
        if (now - lastAdaptAt >= 1000L) {
            lastAdaptAt = now
            if (frameIntervalMs > 33L) frameIntervalMs -= 6L
            if (jpegQuality < qualityCeiling()) {
                jpegQuality = (jpegQuality + 5).coerceAtMost(qualityCeiling())
            }
        }
        cleanFrames++
    }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON) {
                Log.i("lynko", "screen on — rebuilding capture pipeline")
                handler?.post { safeBuild("rebuild-after-unlock") }
            }
        }
    }

    companion object {
        @Volatile private var active: ScreenSession? = null

        /** Re-tune the live capture. A no-op when the mirror is not running,
         *  so a preset clicked in Settings before starting costs nothing.
         *  Runs on the WS thread; the pipeline is only torn down and rebuilt
         *  on the frame handler thread. The MediaProjection token is never
         *  released, so no fresh consent is needed. */
        fun applyQuality(maxWidth: Int, quality: Int) {
            active?.applyQualityLive(maxWidth, quality)
        }
    }

    private fun applyQualityLive(maxWidth: Int, quality: Int) {
        if (maxWidth <= 0 && quality <= 0) return
        if (maxWidth > 0) requestedMaxWidth = maxWidth.coerceIn(360, 2160)
        if (quality > 0) requestedQuality = quality.coerceIn(30, 100)
        handler?.post { safeBuild("quality") }
    }

    /** Resolve the effective capture size: the desktop's capture width wins,
     *  otherwise the tuned 540p default. */
    private fun applyRequested() {
        val target = if (requestedMaxWidth > 0) requestedMaxWidth else 540
        scaleToDisplay = (target / realSize.x.toFloat()).coerceAtMost(1f)
        width = (realSize.x * scaleToDisplay).toInt().coerceAtLeast(360)
        height = (realSize.y * scaleToDisplay).toInt().coerceAtLeast(720)
        density = (metrics.densityDpi * scaleToDisplay).toInt().coerceAtLeast(120)
        if (requestedQuality > 0) jpegQuality = requestedQuality
        rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        row = ByteArray(width * 4)
    }

    fun start() {
        active = this
        // ImageReader callbacks need a looper — the WS thread has none, so
        // give the reader its own background handler thread.
        thread = HandlerThread("lynko-frames").also { it.start() }
        handler = Handler(thread!!.looper)
        running = true

        val f = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(screenReceiver, f)
        }

        handler?.post { safeBuild("start") }
    }

    /** Idempotent: tears down any existing reader/display, then creates a
     * fresh pair. Called from start() and on every SCREEN_ON. Must run on
     * the frame handler thread. */
    private var liveReported = false
    private fun safeBuild(why: String) {
        if (!running) return
        try { buildPipeline(why) } catch (e: Exception) {
            Log.e("lynko", "capture pipeline failed", e)
            running = false
            stop()
            onFatal(e.message ?: "Capture failed")
        }
    }

    private fun buildPipeline(why: String) {
        // Drop the old pipeline first (in case this is a rebuild).
        virtualDisplay?.surface = null
        reader?.close()
        reader = null
        lastFrameAt = 0L
        applyRequested()

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        reader = r
        r.setOnImageAvailableListener({ rr ->
            val img = rr.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // Adaptive fps cap with LATEST-wins: when frames arrive faster
                // than the current interval (30fps healthy → backs off to
                // 15fps under link pressure), skip them — the next frame is
                // fresher, never queue stale.
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < frameIntervalMs) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = img.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val stride = plane.rowStride
                if (stride == width * 4) {
                    rawBitmap.copyPixelsFromBuffer(buffer)
                } else {
                    // stride padding (ImageReader rounds rows to 64B): bulk
                    // copy would skew into bands — copy row by row.
                    val rowBytes = width * 4
                    val bb = java.nio.ByteBuffer.allocate(rowBytes * height)
                    for (y in 0 until height) {
                        buffer.position(y * stride)
                        buffer.get(row, 0, rowBytes)
                        bb.put(row)
                    }
                    bb.rewind()
                    rawBitmap.copyPixelsFromBuffer(bb)
                }
                baos.reset()
                rawBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                if (running) {
                    if (!liveReported) { liveReported = true; onLive() }
                    onFrame(baos.toByteArray())
                }
            } catch (e: Exception) {
                Log.w("lynko", "frame encode failed: ${e.message}")
            } finally {
                img.close()
            }
        }, handler)

        if (virtualDisplay != null) {
            virtualDisplay?.surface = r.surface
        } else virtualDisplay = projection.createVirtualDisplay(
            "lynko-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
        Log.i("lynko", "capture pipeline up ($why) ${width}x${height}@$density — pacing ${frameIntervalMs}ms")
    }

    fun stop() {
        running = false
        if (active === this) active = null
        try { context.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        val h = handler
        if (h != null) {
            h.post { teardown() }
        } else {
            teardown()
        }
    }

    /** Runs on the lynko-frames looper — after any in-flight callback. */
    private fun teardown() {
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
        Log.i("lynko", "screen session stopped")
    }
}
