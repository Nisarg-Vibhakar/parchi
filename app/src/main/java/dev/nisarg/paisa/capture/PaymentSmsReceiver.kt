package dev.nisarg.paisa.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb

/**
 * Card transactions frequently produce a bank SMS and no notification at all.
 * Without this receiver, card spending is invisible.
 */
class PaymentSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PaisaSms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return

            // Long SMS arrives split into parts; concatenate before parsing or the
            // amount and the reference land in different rows.
            val sender = messages.firstOrNull()?.originatingAddress
            val postedAt = messages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            val body = messages.joinToString("") { it.messageBody ?: "" }

            if (!PaymentSources.looksMonetary(body)) return

            val id = PaisaDb.get(context).insertRawEvent(
                source = "sms",
                packageName = null,
                sender = sender,
                title = null,
                body = body,
                extrasJson = null,
                postedAt = postedAt,
                notifKey = null,
            )
            if (id > 0) Log.i(TAG, "captured sms #$id from $sender")
        } catch (t: Throwable) {
            Log.e(TAG, "sms capture failed", t)
        }
    }
}
