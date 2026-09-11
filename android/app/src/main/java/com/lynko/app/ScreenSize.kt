package com.lynko.app

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

/**
 * PHYSICAL screen size, system bars included.
 *
 * ctx.resources.displayMetrics inside a Service returns the app window
 * (height minus status/navigation bars) — e.g. 1080x2264 on a 1080x2400
 * panel. Both gesture injection and full-screen capture need the REAL
 * size; using the window metrics scales every Y coordinate to ~63% of
 * the panel, so taps land in the wrong place.
 */
object ScreenSize {
    fun size(ctx: Context): Point {
        val wm = ctx.getSystemService(WindowManager::class.java)
            ?: return Point(
                ctx.resources.displayMetrics.widthPixels,
                ctx.resources.displayMetrics.heightPixels,
            )
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }
}
