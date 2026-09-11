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
    private val scaleToDisplay = (720f / realSize.x).coerceAtMost(1f)
    private val width = (realSize.x * scaleToDisplay).toInt().coerceAtLeast(360)
    private val height = (realSize.y * scaleToDisplay).toInt().coerceAtLeast(720)
    private val density = (metrics.densityDpi * scaleToDisplay).toInt().coerceAtLeast(120)
    private var lastFrameAt = 0L
    private var jpegQuality = 75
    private var drops = 0

    // Reused per frame — zero steady-state allocation, no GC churn.
    private val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val row = ByteArray(width * 4)
    private val baos = ByteArrayOutputStream(256 * 1024)

    /** Backpressure feedback from LinkService: when frames are being dropped
     * on the send side, step JPEG quality down (floor 50) until the link
     * drains; recover slowly when frames go through again. */
    fun noteDrop() { drops++; if (drops % 3 == 0 && jpegQuality > 50) jpegQuality -= 10 }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var running = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_ON) {
                Log.i("lynko", "screen on — rebuilding capture pipeline")
                handler?.post { buildPipeline("rebuild-after-unlock") }
            }
        }
    }

    fun start() {
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

        handler?.post { buildPipeline("start") }
    }

    /** Idempotent: tears down any existing reader/display, then creates a
     * fresh pair. Called from start() and on every SCREEN_ON. Must run on
     * the frame handler thread. */
    private fun buildPipeline(why: String) {
        // Drop the old pipeline first (in case this is a rebuild).
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        lastFrameAt = 0L

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        reader = r
        r.setOnImageAvailableListener({ rr ->
            val img = rr.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // ~15 fps cap with LATEST-wins: when frames arrive faster,
                // skip them — the next frame is fresher, never queue stale.
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < 66) return@setOnImageAvailableListener
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
                if (running) onFrame(baos.toByteArray())
            } catch (e: Exception) {
                Log.w("lynko", "frame encode failed: ${e.message}")
            } finally {
                img.close()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "lynko-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
        Log.i("lynko", "capture pipeline up ($why) ${width}x${height}@$density")
    }

    fun stop() {
        running = false
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
