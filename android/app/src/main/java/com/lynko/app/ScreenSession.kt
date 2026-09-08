package com.lynko.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * MediaProjection capture → JPEG frames → LV1 binary frames over WS.
 * ImageReader-backed VirtualDisplay gives frame callbacks without a visible surface.
 */
class ScreenSession(
    private val context: Context,
    private val onFrame: (ByteArray) -> Unit,
) {
    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    @Volatile private var running = false

    private val metrics = context.resources.displayMetrics
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.densityDpi

    fun start() {
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val code = ScreenPermission.resultCode
        val data = ScreenPermission.resultData ?: return
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("lynko", "projection stopped")
                stop()
            }
        }, null)

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
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
        }, null)

        virtualDisplay = projection?.createVirtualDisplay(
            "lynko-capture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        running = true
        Log.i("lynko", "screen session started ${width}x${height}@$density")
    }

    fun stop() {
        running = false
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        Log.i("lynko", "screen session stopped")
    }
}
