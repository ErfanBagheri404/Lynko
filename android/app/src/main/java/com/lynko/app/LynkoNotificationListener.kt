package com.lynko.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LynkoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotifCtxHolder.ctx = applicationContext
        Log.i("lynko", "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        val pkg = sbn.packageName
        // Ongoing/replyable capture (for reply support)
        NotifReply.capture(sbn)
        Log.i("lynko", "notification: $title — $text")
        LinkNotifier.push(pkg, title, text, sbn.id)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotifReply.drop(sbn)
    }
}
