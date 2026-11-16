package com.lynko.app

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The three pre-enable gates: detect their state and open the system screen
 * (or dialog) that grants them. Detection is a local query — never a
 * round-trip, never a dialog; `open*` are the ones that leave the app, so the
 * UI can tell them apart from a plain state read.
 *
 * Every detection read degrades to `false` instead of throwing.
 */
object Prep {

    data class State(val taps: Boolean, val notif: Boolean, val awake: Boolean)

    /** All three grants in one pass: Settings.Secure + NotificationManager + PowerManager
     *  reads, no settings round-trip and no dialog. */
    fun state(ctx: Context): State =
        State(taps(ctx), notif(ctx), awake(ctx))

    /** `true` when this app's own a11y service is on: Settings.Secure.ACCESSIBILITY_ENABLED
     *  plus Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES — local query, no round-trip. */
    private fun taps(ctx: Context): Boolean = runCatching {
        val cr = ctx.contentResolver
        // Global toggle off = nothing enabled, even if our component name lingers
        // in the enabled-services string (MIUI leaves it there after a wipe).
        Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 0 &&
            LynkoAccessibilityService.enabled(ctx)
    }.getOrDefault(false)

    /** Android 13+: Context.checkSelfPermission(POST_NOTIFICATIONS); older:
     *  NotificationManager.areNotificationsEnabled() — local query, no dialog. */
    private fun notif(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ctx.getSystemService(NotificationManager::class.java)
                ?.areNotificationsEnabled() == true
        }
    }.getOrDefault(false)

    /** PowerManager.isIgnoringBatteryOptimizations(packageName) — local query, no dialog. */
    private fun awake(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /** Settings.ACTION_ACCESSIBILITY_SETTINGS — settings round-trip (leaves the app).
     *  No ComponentName extra: android.provider.Settings exposes no
     *  EXTRA_ACCESSIBILITY_SETTINGS_COMPONENT_NAME in compileSdk 35, so the row
     *  can't be pre-highlighted; MIUI shows the plain list. */
    fun openTaps(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /** Settings.ACTION_APP_NOTIFICATION_SETTINGS + EXTRA_APP_PACKAGE — settings round-trip.
     *  ponytail: EXTRA_APP_PACKAGE is API 26 = minSdk, so the ACTION_APPLICATION_DETAILS_SETTINGS
     *  fallback is dead code; revive it only if minSdk drops below 26. */
    fun openNotifications(ctx: Context) {
        start(ctx, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }

    /** Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS + package: URI — runtime system
     *  dialog, not a settings round-trip. No-op when already exempt. */
    fun openBattery(ctx: Context) {
        if (awake(ctx)) return
        start(ctx, Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${ctx.packageName}")
        ))
    }

    /** startActivity with FLAG_ACTIVITY_NEW_TASK when the caller isn't an Activity
     *  (Service/Context need their own task). Swallows every launch failure. */
    private fun start(ctx: Context, intent: Intent) {
        runCatching {
            if (ctx !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }
}
