package com.lynko.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Detects whether a USB cable is connected to the device.
 * Used by LinkService (mDNS TXT) and the phone UI.
 *
 * The phone doesn't need the USB cable for Lynko's normal WiFi flow, but
 * knowing about it helps:
 *  - The desktop prefers USB transport when available (lower latency).
 *  - The phone can warn that VPN won't affect USB links.
 *  - The phone UI can show "USB connected" in the status card.
 */
object UsbState {

    private const val TAG = "lynko"

    /** True when a USB device cable is physically connected. */
    @Volatile
    var connected: Boolean = false
        private set

    /** Callback for PhoneState to fire onChange when USB state changes. */
    @Volatile
    var onChange: (() -> Unit)? = null

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    /**
     * Start listening for USB attach/detach broadcasts.
     * Call from LinkService.onCreate or MainActivity.onCreate.
     */
    fun attach(context: Context) {
        if (receiver != null) return
        appContext = context.applicationContext
        val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        connected = um?.deviceList?.isNotEmpty() == true
        Log.i(TAG, "usb-state: initial connected=$connected, devices=${um?.deviceList?.size ?: 0}")

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        connected = true
                        Log.i(TAG, "usb-state: device attached")
                        onChange?.invoke()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // Don't immediately go false — there might be multiple
                        // devices. Recheck.
                        val um2 = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
                        connected = um2?.deviceList?.isNotEmpty() == true
                        Log.i(TAG, "usb-state: device detached, still=${um2?.deviceList?.size ?: 0}")
                        onChange?.invoke()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        Log.i(TAG, "usb-state: listening")
    }

    fun detach() {
        receiver?.let { r ->
            try {
                appContext?.unregisterReceiver(r)
            } catch (_: Exception) {}
        }
        receiver = null
        appContext = null
        connected = false
    }
}
