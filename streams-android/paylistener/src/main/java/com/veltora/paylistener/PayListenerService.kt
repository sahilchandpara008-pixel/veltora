package com.veltora.paylistener

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Forwards notifications of the UPI apps in [Forwarder.APPS]; the server decides what counts as money received. */
class PayListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        Forwarder.retryPending(this) // send anything that failed while offline
    }

    override fun onListenerDisconnected() {
        // Ask Android to reconnect us (e.g. after the system killed the service).
        requestRebind(android.content.ComponentName(this, PayListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in Forwarder.APPS) return
        // Nothing is sent unless the owner tapped "Allow" in the app's consent dialog.
        if (!getSharedPreferences("veltora_pay_listener", MODE_PRIVATE).getBoolean("consent", false)) return
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
