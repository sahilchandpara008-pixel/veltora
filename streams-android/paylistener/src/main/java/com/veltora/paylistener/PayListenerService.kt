package com.veltora.paylistener

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Forwards notifications of the UPI apps in [Forwarder.APPS]; the server decides what counts as money received. */
class PayListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in Forwarder.APPS) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val text = listOf(title, body).filter { it.isNotBlank() }.joinToString(" | ")
        if (text.isBlank()) return
        if (!Forwarder.firstTime(this, "${sbn.packageName}|${sbn.postTime / 60000}|${text.hashCode()}")) return
        Forwarder.send(this, "notif:${sbn.packageName}", text)
    }
}
