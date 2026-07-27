package dev.nisarg.paisa.capture

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb
import org.json.JSONObject

class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PaisaListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected")
        PaisaDb.get(this).beat("listener_connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected — requesting rebind")
        PaisaDb.get(this).beat("listener_disconnected")
        // Motorola's battery optimiser kills this silently. Ask to come back.
        requestRebind(android.content.ComponentName(this, PaymentNotificationListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            // A capture app that crashes captures nothing. Never let a malformed
            // notification take the listener down.
            Log.e(TAG, "capture failed for ${sbn.packageName}", t)
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val db = PaisaDb.get(this)

        if (!PaymentSources.isPaymentApp(pkg)) {
            db.noteUnmatchedPackage(pkg)   // package name only, never content
            return
        }

        val extras: Bundle = sbn.notification?.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        // Prefer the longest available body — Google Pay sometimes truncates
        // EXTRA_TEXT and puts the full sentence in EXTRA_BIG_TEXT.
        val body = listOfNotNull(bigText, text, subText).maxByOrNull { it.length }

        if (title == null && body == null) return

        val id = db.insertRawEvent(
            source = "notification",
            packageName = pkg,
            sender = null,
            title = title,
            body = body,
            extrasJson = dumpExtras(extras),
            postedAt = sbn.postTime,
            notifKey = sbn.key,
        )
        if (id > 0) Log.i(TAG, "captured #$id from $pkg")
    }

    /**
     * Full bundle dump. The useful field is not always EXTRA_TEXT, and we only
     * get one shot at each notification — store everything now, decide later.
     */
    private fun dumpExtras(extras: Bundle): String {
        val json = JSONObject()
        for (key in extras.keySet()) {
            try {
                val v = extras.get(key)
                when (v) {
                    null -> continue
                    is CharSequence -> json.put(key, v.toString())
                    is Number, is Boolean -> json.put(key, v)
                    is Array<*> -> json.put(key, v.joinToString(" | ") { it?.toString() ?: "" })
                    else -> json.put(key, v.javaClass.simpleName)   // Bitmaps, Icons etc.
                }
            } catch (ignored: Throwable) {
                // Some extras throw on read. Skip the key, keep the rest.
            }
        }
        return json.toString()
    }
}
