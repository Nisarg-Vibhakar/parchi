package dev.nisarg.paisa.capture

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb

/**
 * Reads the EXISTING SMS inbox.
 *
 * This is what makes Phase 1 pay off immediately instead of after a week:
 * notifications have to be waited for, but months of real bank messages are
 * already sitting on the phone. The parser can be measured against genuine data
 * the first afternoon.
 *
 * Idempotent — SMS dedupe hashes include the message timestamp, so running this
 * repeatedly imports nothing twice.
 */
object SmsBackfill {

    private const val TAG = "PaisaBackfill"

    data class Stats(val scanned: Int, val imported: Int, val skipped: Int)

    /**
     * The first real backfill hit a 5,000-message cap with history still behind
     * it. The scan is cheap — non-monetary messages are discarded before any
     * write — so the cap exists only as a runaway guard, not as a budget.
     */
    fun run(context: Context, maxMessages: Int = 100_000): Stats {
        val db = PaisaDb.get(context)
        var scanned = 0
        var imported = 0
        var skipped = 0

        val uri: Uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        try {
            context.contentResolver.query(
                uri, projection, null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && scanned < maxMessages) {
                    scanned++
                    val sender = c.getString(0)
                    val body = c.getString(1)
                    val date = c.getLong(2)

                    if (!PaymentSources.looksMonetary(body)) {
                        skipped++
                        continue
                    }

                    val id = db.insertRawEvent(
                        source = "sms",
                        packageName = null,
                        sender = sender,
                        title = null,
                        body = body,
                        extrasJson = null,
                        postedAt = date,
                        notifKey = null,
                    )
                    if (id > 0) imported++ else skipped++
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "backfill failed", t)
        }

        Log.i(TAG, "backfill scanned=$scanned imported=$imported skipped=$skipped")
        return Stats(scanned, imported, skipped)
    }
}
