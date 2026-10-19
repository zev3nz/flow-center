package com.zev3nz.flowcenter

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationListener : NotificationListenerService() {

    companion object {
        var instance: NotificationListener? = null
        // Set by OverlayService while the panel is open so the list updates live
        var onChange: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        handler.post { onChange?.invoke() }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        handler.post { onChange?.invoke() }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
