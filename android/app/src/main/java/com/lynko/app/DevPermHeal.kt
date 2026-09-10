package com.lynko.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Dev-loop quality-of-life: MIUI wipes accessibility + notification-listener
 * grants on every `adb install -r` (stock Android preserves them). With
 * WRITE_SECURE_SETTINGS granted via adb (one-time), the app re-enables its own
 * services on launch so the checklist stays green across reinstalls.
 *
 * No-op when the adb grant is absent (production installs) — user grants
 * through Settings as usual.
 */
object DevPermHeal {
    private const val TAG = "lynko-heal"
    private const val A11Y =
        "com.lynko.app/com.lynko.app.LynkoAccessibilityService"
    private const val NOTIF =
        "com.lynko.app/com.lynko.app.LynkoNotificationListener"

    fun heal(ctx: Context) {
        try {
            // Only attempt if we hold WRITE_SECURE_SETTINGS.
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return

            val cur = ctx.contentResolver

            // --- accessibility ---
            val a11y = Settings.Secure.getString(cur, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!a11y.contains(A11Y)) {
                val merged = if (a11y.isBlank()) A11Y else "$a11y:$A11Y"
                Settings.Secure.putString(
                    cur,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    merged
                )
                Settings.Secure.putInt(cur, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "self-healed accessibility grant")
            }

            // --- notification listener ---
            val notif = Settings.Secure.getString(cur, "enabled_notification_listeners") ?: ""
            if (!notif.contains(NOTIF)) {
                val merged = if (notif.isBlank()) NOTIF else "$notif:$NOTIF"
                Settings.Secure.putString(cur, "enabled_notification_listeners", merged)
                Log.i(TAG, "self-healed notification listener grant")
            }
        } catch (e: Exception) {
            Log.w(TAG, "heal skipped: ${e.message}")
        }
    }
}
