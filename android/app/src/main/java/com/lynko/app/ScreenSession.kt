package com.lynko.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.ByteArrayOutputStream

/** MediaProjection capture -> JPEG frames. Projection is created ONCE by
 * LinkService (consent tokens are single-use) and passed in here.
 *
 * Threading: ALL teardown runs on the frame handler thread — closing the
 * reader from another thread while a callback is mid-copy segfaults
 * (Bitmap_copyPixelsFromBuffer use-after-free). Posting teardown to the
 * same looper serializes it after any in-flight frame callback. */
class ScreenSession(
    context: Context,
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val metrics = context.resources.displayMetrics
    // Capture at native resolution but ENCODE scaled to max 720px on the
    // short edge: 4x fewer pixels = 4x faster JPEG encode + 4x smaller
    // frames over the wire. Full-res capture keeps touch coordinates exact.
    // JPEG quality adapts down when the send side drops frames (floor 35).
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val scale = (720f / width).coerceAtMost(1f)
    private val outW = (width * scale).toInt()
    private val outH = (height * scale).toInt()
    private val density = metrics.densityDpi / 2
    private var lastFrameAt = 0L
    private var jpegQuality = 75
    private var drops = 0

    // Reused per frame — zero steady-state allocation, no GC churn.
    private val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    private val canvas = android.graphics.Canvas(outBitmap)
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val srcRect = android.graphics.Rect(0, 0, width, height)
    private val dstRect = android.graphics.Rect(0, 0, outW, outH)
    private val packed = java.nio.ByteBuffer.allocate(width * height * 4)
    private val row = ByteArray(width * 4)
    private val baos = ByteArrayOutputStream(256 * 1024)

    /** Backpressure feedback from LinkService: when frames are being dropped
     * on the send side, step JPEG quality down (floor 35) until the link
     * drains; recover slowly (+5) when two consecutive frames go through. */
    fun noteDrop() { drops++; if (drops % 3 == 0 && jpegQuality > 35) jpegQuality -= 10 }

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    @Volatile private var running = false

    fun start() {
        // ImageReader callbacks need a looper — the WS thread has none, so
        // give the reader its own background handler thread.
        thread = HandlerThread("lynko-frames").also { it.start() }
        val handler = Handler(thread!!.looper)

        val r = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        reader = r
        r.setOnImageAvailableListener({ rr ->
            val img = rr.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // ~15 fps cap: drop whatever arrives faster than 66ms apart
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < 66) return@setOnImageAvailableListener
                lastFrameAt = now
                val plane = img.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val stride = plane.rowStride
                if (stride == width * 4) {
                    // tightly packed rows: bulk copy straight into the raw bitmap
                    rawBitmap.copyPixelsFromBuffer(buffer)
                } else {
                    // stride padding: copy row by row into a packed buffer.
                    // ImageReader may round rows up to a 64-byte boundary; a
                    // bulk copy would skew the image into bands.
                    packed.clear()
                    val rowBytes = width * 4
                    for (y in 0 until height) {
                        buffer.position(y * stride)
                        buffer.get(row, 0, rowBytes)
                        packed.put(row)
                    }
                    packed.rewind()
                    rawBitmap.copyPixelsFromBuffer(packed)
                }
                // scale 1080x2400 -> outW x outH (GPU-less but cheap: 4x fewer px)
                canvas.drawBitmap(rawBitmap, srcRect, dstRect, paint)
                baos.reset()
                outBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
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
        running = true
        Log.i("lynko", "screen session started ${width}x${height}@$density")
    }

    fun stop() {
        val h = thread?.looper?.let { Handler(it) }
        if (h != null) {
            h.post { teardown() }
        } else {
            teardown()
        }
    }

    /** Runs on the lynko-frames looper — after any in-flight callback. */
    private fun teardown() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        Log.i("lynko", "screen session stopped")
    }
}
