package com.lynko.app

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Bridges NotificationListenerService events to the active WS link. */
object LinkNotifier {
    val broadcaster = AtomicReference<((String) -> Unit)?>(null)

    fun push(pkg: String, title: String, text: String, notifId: Int = 0) {
        val send = broadcaster.get() ?: return
        val json = JSONObject()
            .put("t", "notification")
            .put("d", JSONObject()
                .put("app", pkg)
                .put("title", title)
                .put("body", text)
                .put("notifId", notifId))
        send(json.toString())
    }
}
