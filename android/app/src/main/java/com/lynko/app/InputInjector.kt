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
        val metrics = ctx.resources.displayMetrics
        val x = x01 * metrics.widthPixels
        val y = y01 * metrics.heightPixels
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
        val metrics = ctx.resources.displayMetrics
        val path = Path().apply {
            moveTo(x1 * metrics.widthPixels, y1 * metrics.heightPixels)
            lineTo(x2 * metrics.widthPixels, y2 * metrics.heightPixels)
        }
        val stroke = if (Build.VERSION.SDK_INT >= 26) {
            GestureDescription.StrokeDescription(path, 0, 250, true)
        } else {
            GestureDescription.StrokeDescription(path, 0, 250)
        }
        svc.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        Log.i("lynko", "swipe")
        return true
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
}
