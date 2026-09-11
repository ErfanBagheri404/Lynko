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

    fun start(ctx: Context, push: (String) -> Unit) {
        if (watching) return
        watching = true
        lastPushed = ClipboardBridge.read(ctx)
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.addPrimaryClipChangedListener {
            val text = try { ClipboardBridge.read(ctx) } catch (e: Exception) { "" }
            if (text.isNotBlank() && text != lastPushed) {
                lastPushed = text
                push(buildJson(text))
            }
        }
        // MIUI redacts clipboard for background apps; the listener alone is
        // unreliable, so also poll while a link is active.
        Thread {
            while (watching) {
                try {
                    Thread.sleep(2000)
                    val text = try { ClipboardBridge.read(ctx) } catch (e: Exception) { "" }
                    if (text.isNotBlank() && text != lastPushed) {
                        lastPushed = text
                        push(buildJson(text))
                    }
                } catch (_: InterruptedException) { break }
            }
        }.start()
    }

    fun stop() { watching = false }

    /** Desktop wrote the phone clipboard — don't echo it back. */
    fun markLocal(text: String) { lastPushed = text }

    private fun buildJson(text: String): String =
        org.json.JSONObject()
            .put("t", "clipboard")
            .put("d", org.json.JSONObject().put("text", text))
            .toString()
}
