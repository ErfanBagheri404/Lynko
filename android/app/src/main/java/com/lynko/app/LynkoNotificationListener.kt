package com.lynko.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LynkoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotifCtxHolder.ctx = applicationContext
        // Desktop dismissals need cancelNotification(), which lives only on
        // this service instance — LinkNotifier calls through this handle.
        LinkNotifier.listener.set(this)
        Log.i("lynko", "notification listener connected")
    }

    override fun onListenerDisconnected() {
        LinkNotifier.listener.set(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName
        // Ongoing/replyable capture (for reply support)
        NotifReply.capture(sbn)
        Log.i("lynko", "notification: $title — $text")
        LinkNotifier.push(pkg, title, text, sbn.id, sbn.tag)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifReply.drop(sbn)
        // Loop guard: a removal WE caused (desktop dismissed it) must not be
        // forwarded back — the desktop already dropped the card.
        if (LinkNotifier.wasSelfCancelled(sbn.packageName, sbn.id)) return
        // forward the removal as normal here (wave-3: card gone on phone side).
    }
}
