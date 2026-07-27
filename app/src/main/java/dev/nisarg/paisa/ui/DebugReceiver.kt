package dev.nisarg.paisa.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.nisarg.paisa.capture.SmsBackfill
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.export.Exporter

/**
 * Lets the whole capture loop be driven over adb instead of by tapping buttons:
 *
 *   adb shell am broadcast -a dev.nisarg.paisa.BACKFILL -n dev.nisarg.paisa/.ui.DebugReceiver
 *   adb shell am broadcast -a dev.nisarg.paisa.REPARSE  -n dev.nisarg.paisa/.ui.DebugReceiver
 *   adb shell am broadcast -a dev.nisarg.paisa.EXPORT   -n dev.nisarg.paisa/.ui.DebugReceiver
 *   adb shell am broadcast -a dev.nisarg.paisa.STATUS   -n dev.nisarg.paisa/.ui.DebugReceiver
 *
 * Results go to logcat under the PaisaDebug tag. Phase 1 development tooling —
 * this goes away with the debug UI when Phase 2 lands.
 */
class DebugReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PaisaDebug"
        const val BACKFILL = "dev.nisarg.paisa.BACKFILL"
        const val REPARSE = "dev.nisarg.paisa.REPARSE"
        const val EXPORT = "dev.nisarg.paisa.EXPORT"
        const val STATUS = "dev.nisarg.paisa.STATUS"
        const val SELF_ADD = "dev.nisarg.paisa.SELF_ADD"
        const val SELF_DEL = "dev.nisarg.paisa.SELF_DEL"
        const val SELF_LIST = "dev.nisarg.paisa.SELF_LIST"
        const val DELETE_RAW = "dev.nisarg.paisa.DELETE_RAW"
        const val SIMULATE_SMS = "dev.nisarg.paisa.SIMULATE_SMS"
        const val SETTLE_SMALL = "dev.nisarg.paisa.SETTLE_SMALL"
        const val DAILY_NOW = "dev.nisarg.paisa.DAILY_NOW"
        const val SET_BUCKET = "dev.nisarg.paisa.SET_BUCKET"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                when (intent.action) {
                    BACKFILL -> {
                        val s = SmsBackfill.run(app)
                        Log.i(TAG, "BACKFILL scanned=${s.scanned} imported=${s.imported} skipped=${s.skipped}")
                    }
                    REPARSE -> Log.i(TAG, "REPARSE rows=${PaisaDb.get(app).reparseAll()}")
                    EXPORT -> Log.i(TAG, "EXPORT file=${Exporter.export(app).absolutePath}")
                    STATUS -> status(app)
                    SELF_ADD -> {
                        val v = intent.getStringExtra("value").orEmpty()
                        if (v.isBlank()) Log.w(TAG, "SELF_ADD needs --es value \"...\"")
                        else { PaisaDb.get(app).addSelfIdentity(v); Log.i(TAG, "SELF_ADD $v") }
                    }
                    SELF_DEL -> {
                        val v = intent.getStringExtra("value").orEmpty()
                        PaisaDb.get(app).removeSelfIdentity(v); Log.i(TAG, "SELF_DEL $v")
                    }
                    SELF_LIST -> Log.i(TAG, "SELF_LIST ${PaisaDb.get(app).selfIdentities()}")
                    // Injects a message as though the network delivered it, so
                    // parser and reconciliation changes can be exercised without
                    // waiting on a real bank or spending real money.
                    SIMULATE_SMS -> {
                        val body = intent.getStringExtra("body").orEmpty()
                        val sender = intent.getStringExtra("sender") ?: "TEST-SIM"
                        if (body.isBlank()) Log.w(TAG, "SIMULATE_SMS needs --es body \"...\"")
                        else Log.i(TAG, "SIMULATE_SMS id=" + PaisaDb.get(app).insertRawEvent(
                            source = "sms", packageName = null, sender = sender,
                            title = null, body = body, extrasJson = null,
                            postedAt = System.currentTimeMillis(), notifKey = null))
                    }
                    // Same action as the button, callable without touching the
                    // phone: settles small pre-cycle payments as Other.
                    SETTLE_SMALL -> {
                        val db = PaisaDb.get(app)
                        val maxMinor = intent.getLongExtra("max_rupees", 500L) * 100
                        val before = db.lastSalary()?.at
                            ?: (System.currentTimeMillis() - 30L * 86_400_000)
                        val (n, v) = db.countSmallBefore(maxMinor, before)
                        val done = db.settleSmallBefore(maxMinor, before)
                        Log.i(TAG, "SETTLE_SMALL expected=$n value=$v settled=$done")
                    }
                    SET_BUCKET -> {
                        val label = intent.getStringExtra("label").orEmpty()
                        val cats = intent.getStringExtra("categories").orEmpty()
                        val rupees = intent.getLongExtra("rupees", 0L)
                        val period = intent.getIntExtra("period", 1)
                        if (label.isBlank() || cats.isBlank()) {
                            Log.w(TAG, "SET_BUCKET needs --es label/--es categories")
                        } else {
                            PaisaDb.get(app).setBucketPlan(
                                dev.nisarg.paisa.parse.Buckets.Plan(
                                    label, cats.split(",").map { it.trim() }.toSet(),
                                    rupees * 100, period))
                            Log.i(TAG, "SET_BUCKET $label = $rupees over $period cycle(s)")
                        }
                    }
                    DAILY_NOW -> dev.nisarg.paisa.work.DailySummary.post(app)
                    DELETE_RAW -> {
                        val id = intent.getLongExtra("id", -1L)
                        Log.i(TAG, "DELETE_RAW id=$id removed=${PaisaDb.get(app).deleteRawEvent(id)}")
                    }
                    else -> Log.w(TAG, "unknown action ${intent.action}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "action ${intent.action} failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun status(context: Context) {
        val db = PaisaDb.get(context)
        Log.i(TAG, "STATUS raw=${db.count("raw_events")}" +
            " notif=${db.countWhere("raw_events", "source='notification'")}" +
            " sms=${db.countWhere("raw_events", "source='sms'")}" +
            " parsed=${db.count("parsed_txn")}" +
            " rejected=${db.countWhere("parsed_txn", "rejected_reason IS NOT NULL")}" +
            " confident=${db.countWhere("parsed_txn", "confidence >= 0.75")}" +
            " beats=${db.count("heartbeat")}")
    }
}
