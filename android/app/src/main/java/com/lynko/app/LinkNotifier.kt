package com.lynko.app

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/** Bridges NotificationListenerService events to the active WS link. */
object LinkNotifier {
    val broadcaster = AtomicReference<((String) -> Unit)?>(null)

    fun push(pkg: String, title: String, text: String) {
        val send = broadcaster.get() ?: return
        val json = JSONObject()
            .put("t", "notification")
            .put("d", JSONObject()
                .put("app", pkg)
                .put("title", title)
                .put("text", text))
        send(json.toString())
    }
}
