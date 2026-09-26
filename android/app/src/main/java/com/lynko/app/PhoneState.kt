package com.lynko.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject

/**
 * Single source of truth for what the phone is actually doing, so the phone
 * UI and the desktop never show a state the device isn't in.
 *
 *  link   — a desktop is connected to the WS link right now
 *  mirror — the screen pipeline is producing frames
 *  locked — screen off / keyguard showing
 *
 * Every change pushes a "phone_state" event over the link (when open) and
 * fires [onChange] so the local UI can redraw. Lock state is sampled on a
 * 1s tick: MIUI is inconsistent with ACTION_SCREEN_ON/OFF broadcasts.
 */
object PhoneState {

    @Volatile var link: Boolean = false; private set
    @Volatile var mirror: Boolean = false; private set
    @Volatile var locked: Boolean = false; private set
    /** Whether gestures can actually be injected right now — the a11y
     *  service being BOUND (not merely listed in the enabled-settings
     *  string, which MIUI leaves stale after a reinstall). */
    @Volatile var control: Boolean = false; private set

    /** LinkService installs this: send JSON over every open link connection. */
    @Volatile var broadcaster: ((JSONObject) -> Unit)? = null

    /** MainActivity installs this: redraw the status card. */
    @Volatile var onChange: (() -> Unit)? = null

    /** ScreenSession hooks: lock/unlock notifications while mirroring. */
    @Volatile var onLock: (() -> Unit)? = null
    @Volatile var onUnlock: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null
    private var ctx: Context? = null

    fun attach(context: Context) {
        ctx = context.applicationContext
        if (tick != null) return
        val r = object : Runnable {
            override fun run() {
                sample(context.applicationContext)
                main.postDelayed(this, 1000)
            }
        }
        tick = r
        main.post(r)
    }

    fun detach() {
        tick?.let { main.removeCallbacks(it) }
        tick = null
        ctx = null
        link = false; mirror = false
        broadcaster = null; onChange = null; onLock = null; onUnlock = null
    }

    private fun sample(c: Context) {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val wasLocked = locked
        locked = !pm.isInteractive || km.isKeyguardLocked
        // Control availability flips with the a11y binding, not the
        // (stale-prone) settings string — MIUI shows Lynko "On" in its
        // accessibility screen while nothing is actually bound.
        val wasControl = control
        control = LynkoAccessibilityService.instance != null
        if (locked != wasLocked) {
            Log.i("lynko", "state: locked=$locked")
            if (mirror) {
                try { if (locked) onLock?.invoke() else onUnlock?.invoke() } catch (_: Exception) {}
            }
            publish()
        }
        if (control != wasControl) {
            Log.i("lynko", "state: control=$control (a11y bound=$control)")
            publish()
        }
    }

    fun setLink(v: Boolean) { if (link != v) { link = v; Log.i("lynko", "state: link=$v"); publish() } }
    fun setMirror(v: Boolean) { if (mirror != v) { mirror = v; Log.i("lynko", "state: mirror=$v"); publish() } }

    fun describe(): JSONObject = JSONObject()
        .put("t", "phone_state")
        .put("d", JSONObject().put("link", link).put("mirror", mirror).put("locked", locked).put("control", control).put("usb", UsbState.connected))

    private fun publish() {
        val json = describe()
        broadcaster?.invoke(json)
        main.post { try { onChange?.invoke() } catch (_: Exception) {} }
    }
}
