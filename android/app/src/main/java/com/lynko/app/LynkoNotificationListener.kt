package com.lynko.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class LynkoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Log.i("lynko", "notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: return
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""
        Log.i("lynko", "notification: $title — $text")
        LinkNotifier.push(sbn.packageName, title, text)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}
}
