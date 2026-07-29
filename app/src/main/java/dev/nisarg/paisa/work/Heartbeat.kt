package dev.nisarg.paisa.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb

/**
 * A heartbeat row every 6 hours.
 *
 * Motorola's battery optimiser kills background listeners without a crash, a log
 * or any other symptom — spending simply reads zero and looks like a frugal week.
 * A gap in this table is proof the listener was dead, which converts silent data
 * loss into detectable data loss. Three lines of code, and the difference between
 * trusting the numbers and not.
 */
object Heartbeat {

    private const val TAG = "PaisaHeartbeat"
    const val INTERVAL_MS = 6 * 60 * 60 * 1000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, HeartbeatReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Inexact on purpose: this must never be the reason the battery drains.
        am.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + INTERVAL_MS,
            INTERVAL_MS,
            pi
        )
        Log.i(TAG, "heartbeat scheduled")
    }
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaisaDb.get(context).beat("alarm")
        // The daily call is a one-shot alarm that re-arms itself after each
        // delivery. That chain is reliable but it is still a chain, and one
        // dropped link would stop the feature silently until the next reboot.
        // This already runs every six hours; re-arming here costs nothing and
        // makes the failure self-healing.
        DailySummary.schedule(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaisaDb.get(context).beat("boot:${intent.action}")
        Heartbeat.schedule(context)
        // Alarms do not survive a reboot; without this the daily summary would
        // silently stop the first time the phone restarts.
        DailySummary.schedule(context)
    }
}
