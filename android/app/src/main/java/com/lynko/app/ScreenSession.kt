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
    // Native phone resolution at ~15 fps / adaptive JPEG (75 start, 35 floor).
    // The WS send queue is bounded (sendBusy gate in LinkService): saturated
    // frames are dropped and quality steps down until the link drains.
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.densityDpi / 2
    private var lastFrameAt = 0L
    private var jpegQuality = 75
    private var drops = 0

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
                val bitmap = if (stride == width * 4) {
                    // tightly packed rows: single bulk copy
                    val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    b.copyPixelsFromBuffer(buffer)
                    b
                } else {
                    // stride padding: copy row by row into a packed bitmap.
                    // ImageReader may round rows up to a 64-byte boundary;
                    // copyPixelsFromBuffer would skew the image into bands.
                    val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val packed = java.nio.ByteBuffer.allocate(width * height * 4)
                    val rowBytes = width * 4
                    val row = ByteArray(rowBytes)
                    for (y in 0 until height) {
                        buffer.position(y * stride)
                        buffer.get(row, 0, rowBytes)
                        packed.put(row)
                    }
                    packed.rewind()
                    b.copyPixelsFromBuffer(packed)
                    b
                }
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, baos)
                bitmap.recycle()
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
