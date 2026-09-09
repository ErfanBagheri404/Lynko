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
 * LinkService (consent tokens are single-use) and passed in here. */
class ScreenSession(
    context: Context,
    private val projection: MediaProjection,
    private val onFrame: (ByteArray) -> Unit,
) {
    private val metrics = context.resources.displayMetrics
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.densityDpi

    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var registered = false

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
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(img.planes[0].buffer)
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                bitmap.recycle()
                if (running) onFrame(baos.toByteArray())
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
