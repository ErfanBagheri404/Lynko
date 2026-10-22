package com.lynko.app

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Bridges NotificationListenerService events to the active WS link. */
object LinkNotifier {
    val broadcaster = AtomicReference<((String) -> Unit)?>(null)

    /** Samsung Flow parity: the Smart View bar's "Do not disturb".
     *  While set, phone notifications are NOT relayed to the desktop, so
     *  mirroring stays quiet. Off again the moment the desktop clears it. */
    val dnd = java.util.concurrent.atomic.AtomicBoolean(false)

    fun push(pkg: String, title: String, text: String, notifId: Int = 0) {
        if (dnd.get()) return
        val send = broadcaster.get() ?: return
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
                    .put("notifId", notifId))
        } catch (_: Exception) { return }
        try { send(json.toString()) } catch (_: Exception) {}
    }
}
