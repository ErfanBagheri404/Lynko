package com.lynko.app

import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard sync (Samsung Flow parity):
 *  - clipboard watch loop phone → desktop
 *  - write desktop → phone via ClipboardBridge
 *
 * The system fires onPrimaryClipChanged but only while our process is alive
 * AND the listener is added; on MIUI background clipboard access is redacted,
 * so we also poll every 2s as a fallback while a link is active.
 */
object ClipSync {
    @Volatile private var lastPushed = ""
    @Volatile private var watching = false
    private var cm: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var poller: Thread? = null

    fun start(ctx: Context, push: (String) -> Unit) {
        if (watching) return
        watching = true
        lastPushed = ClipboardBridge.read(ctx)
        val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm = manager
        // Listener is REMOVED in stop(): re-adding per link otherwise piles up
        // listeners bound to dead sockets across reconnects.
        val l = ClipboardManager.OnPrimaryClipChangedListener {
            if (!watching) return@OnPrimaryClipChangedListener
            val text = try { ClipboardBridge.read(ctx) } catch (e: Exception) { "" }
            if (text.isNotBlank() && text != lastPushed) {
                lastPushed = text
                push(buildJson(text))
            }
        }
        listener = l
        manager.addPrimaryClipChangedListener(l)
        // MIUI redacts clipboard for background apps; the listener alone is
        // unreliable, so also poll while a link is active. stop() interrupts
        // this thread — without that, a wake during the 2s sleep pushed to
        // the CLOSED socket (WebsocketNotConnectedException killed the
        // process, taking the accessibility service with it).
        poller = Thread {
            while (watching) {
                try {
                    Thread.sleep(2000)
                    if (!watching) break
                    val text = try { ClipboardBridge.read(ctx) } catch (e: Exception) { "" }
                    if (text.isNotBlank() && text != lastPushed) {
                        lastPushed = text
                        push(buildJson(text))
                    }
                } catch (_: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    fun stop() {
        watching = false
        try { cm?.removePrimaryClipChangedListener(listener) } catch (_: Exception) {}
        cm = null
        listener = null
        poller?.interrupt()
        poller = null
    }

    /** Desktop wrote the phone clipboard — don't echo it back. */
    fun markLocal(text: String) { lastPushed = text }

    private fun buildJson(text: String): String =
        org.json.JSONObject()
            .put("t", "clipboard")
            .put("d", org.json.JSONObject().put("text", text))
            .toString()
}
