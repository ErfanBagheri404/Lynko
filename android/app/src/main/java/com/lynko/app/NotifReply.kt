package com.lynko.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Replies to a notification via its RemoteInput action (Samsung Flow parity).
 * We capture the reply PendingIntent + remoteInputs when the notification is
 * posted, keep them for a short window, and fire them with the typed text.
 */
object NotifReply {
    private data class ReplyTarget(
        val action: PendingIntent,
        val remoteInputs: Array<android.app.RemoteInput>,
        val at: Long
    )

    // key: "pkg|id" — most recent per notification
    private val targets = HashMap<String, ReplyTarget>()
    private const val MAX_AGE_MS = 10 * 60 * 1000L // 10 minutes

    private fun key(sbn: StatusBarNotification): String = "${sbn.packageName}|${sbn.id}"

    fun capture(sbn: StatusBarNotification) {
        try {
            val actions = sbn.notification?.actions ?: return
            for (a in actions) {
                val inputs = a.remoteInputs ?: continue
                if (inputs.isNotEmpty()) {
                    Log.i("lynko", "reply target captured: ${sbn.packageName} id=${sbn.id}")
                    synchronized(targets) { targets[key(sbn)] = ReplyTarget(a.actionIntent, inputs, System.currentTimeMillis()) }
                    return
                }
            }
        } catch (e: Exception) {
            Log.e("lynko", "reply capture failed: ${e.message}")
        }
    }

    fun drop(sbn: StatusBarNotification) {
        synchronized(targets) { targets.remove(key(sbn)) }
    }

    /** @return error hint or null on success (fires the reply). */
    fun reply(pkg: String, notifId: Int, text: String): String? {
        val target: ReplyTarget = synchronized(targets) {
            val t = targets["$pkg|$notifId"]
            if (t != null && System.currentTimeMillis() - t.at > MAX_AGE_MS) {
                targets.remove("$pkg|$notifId"); null
            } else t
        } ?: return "no replyable notification for $pkg (may have expired)"

        return try {
            val bundle = Bundle()
            for (ri in target.remoteInputs) bundle.putCharSequence(ri.resultKey, text)
            val fillIntent = Intent()
            android.app.RemoteInput.addResultsToIntent(target.remoteInputs, fillIntent, bundle)
            target.action.send(NotifCtxHolder.ctx, 0, fillIntent)
            Log.i("lynko", "reply sent: $pkg id=$notifId")
            null
        } catch (e: Exception) {
            Log.e("lynko", "reply failed: ${e.message}")
            "reply failed: ${e.message}"
        }
    }
}

/** ApplicationContext held for firing PendingIntents. */
object NotifCtxHolder { @Volatile var ctx: android.content.Context? = null }
