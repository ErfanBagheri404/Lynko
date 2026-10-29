package com.lynko.app

import android.service.notification.NotificationListenerService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Bridges NotificationListenerService events to the active WS link. */
object LinkNotifier {
    val broadcaster = AtomicReference<((String) -> Unit)?>(null)

    /** Samsung Flow parity: the Smart View bar's "Do not disturb".
     *  While set, phone notifications are NOT relayed to the desktop, so
     *  mirroring stays quiet. Off again the moment the desktop clears it. */
    val dnd = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Live listener instance, registered by LynkoNotificationListener on
     *  connect. Needed for desktop-driven dismissal: cancelNotification()
     *  exists ONLY on the listener, not on NotificationManager. */
    val listener = AtomicReference<NotificationListenerService?>(null)

    /** OTP detection, same shape as the desktop: 4–8 digits NOT embedded in
     *  a longer digit run (a phone number / order id never matches). Tagged
     *  `otp: true` on the wire so the desktop surfaces "Copy code". */
    private val OTP = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** pkg|id → sbn.tag, so a dismissal can cancel the exact notification
     *  (notifications posted under a tag don't match a null-tag cancel). */
    private val tags = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>) = size > 64
    }

    /** Loop guard: keys WE cancelled from a desktop dismissal. The listener's
     *  onNotificationRemoved swallows these — never bounce a dismissal back. */
    private val selfCancelled = java.util.Collections.synchronizedSet(HashSet<String>())

    fun push(pkg: String, title: String, text: String, notifId: Int = 0, tag: String? = null) {
        if (dnd.get()) return
        val send = broadcaster.get() ?: return
        if (tag != null) synchronized(tags) { tags["$pkg|$notifId"] = tag }
        // Called from the notification listener thread — a send on a socket
        // closing underneath throws and kills the process (and the
        // accessibility service with it). Swallow: the notification is gone.
        val json = try {
            JSONObject()
                .put("t", "notification")
                .put("d", JSONObject()
                    .put("app", pkg)
                    .put("title", title)
                    .put("body", text)
                    .put("notifId", notifId)
                    .put("otp", OTP.find(text)?.value ?: OTP.find(title)?.value != null))
        } catch (_: Exception) { return }
        try { send(json.toString()) } catch (_: Exception) {}
    }

    /** Desktop dismissal: cancel the matching Android notification.
     *  Returns false when no listener is connected (notification left alone —
     *  the desktop already dropped its card, and a silent phone-side cancel
     *  we can't perform must not loop an error dismissal back). */
    fun cancel(pkg: String, notifId: Int): Boolean {
        val l = listener.get() ?: return false
        val tag = synchronized(tags) { tags.remove("$pkg|$notifId") }
        // Mark BEFORE cancel lands: onNotificationRemoved fires immediately
        // after and must not forward this removal (loop guard).
        selfCancelled.add("$pkg|$notifId")
        return try {
            l.cancelNotification(tag, notifId)
            true
        } catch (_: Exception) {
            selfCancelled.remove("$pkg|$notifId")
            false
        }
    }

    /** @return true when this removal was caused by OUR desktop dismissal —
     *  the caller must not forward it (that's the delivery loop). */
    fun wasSelfCancelled(pkg: String, notifId: Int): Boolean =
        selfCancelled.remove("$pkg|$notifId")
}
