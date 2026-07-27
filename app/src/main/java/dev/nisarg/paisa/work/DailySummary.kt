package dev.nisarg.paisa.work

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.Cycle
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.CaptureActivity
import java.util.Calendar

/**
 * One notification a day, at nine in the evening.
 *
 * The day is over, nothing more will be spent, and there is still time to file
 * what happened while it is remembered. Any earlier and it interrupts; any later
 * and it is read in bed and forgotten.
 *
 * It says the two things that can prompt an action — what today cost, and how
 * many payments are still unfiled — and nothing else. A summary that lists every
 * transaction is a summary that gets swiped away, and an app that nags is an app
 * that gets muted.
 */
object DailySummary {

    private const val TAG = "PaisaDaily"
    /**
     * A NEW id on purpose. The first version of this channel was created at
     * IMPORTANCE_LOW, and Android does not allow an app to raise a channel's
     * importance afterwards — only the user can, in settings. A silent channel
     * cannot ring, so the call would have been a notification in costume.
     * Changing the id creates it fresh at the importance it needs.
     */
    const val CHANNEL = "incoming_call_v2"
    private const val NOTIFICATION_ID = 4201
    const val HOUR = 21

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 1, Intent(context, DailySummaryReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Inexact on purpose: a spending summary is never worth waking the device
        // for, and an exact alarm would need a permission this does not deserve.
        am.setInexactRepeating(
            AlarmManager.RTC, next.timeInMillis, AlarmManager.INTERVAL_DAY, pi
        )
        Log.i(TAG, "daily summary scheduled for ${next.time}")
    }

    /** Declining still records the call; it just does not ring again. */
    private fun declineIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 4, Intent(context, SnoozeReceiver::class.java).setAction("decline"),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Rings again after the snooze, so a busy moment does not lose the day. */
    fun scheduleSnoozeCallback(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.RTC, System.currentTimeMillis() + delayMs,
            PendingIntent.getBroadcast(
                context, 5, Intent(context, DailySummaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        Log.i(TAG, "callback in ${delayMs / 60000} minutes")
    }

    fun post(context: Context) {
        val db = PaisaDb.get(context)
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        val spent = db.spentBetween(startOfDay, now)
        val count = db.countBetween(startOfDay, now)
        val unfiled = db.uncategorisedCount()

        // Nothing happened and nothing is waiting: say nothing. A notification
        // that reports zero teaches the user to ignore the next one.
        if (count == 0 && unfiled == 0) {
            Log.i(TAG, "nothing to report"); return
        }

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            // HIGH, because a call that does not ring is just a notification
            // wearing a costume.
            NotificationChannel(CHANNEL, "Incoming call", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Your daily spending, delivered as a call"
                    setSound(
                        android.media.RingtoneManager
                            .getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    enableVibration(true)
                }
        )

        val title = if (count == 0) "Nothing spent today"
        else "${Money.format(spent)} today · $count payment${if (count == 1) "" else "s"}"

        val body = buildString {
            val salary = db.lastSalary()
            if (salary != null) {
                val gap = Cycle.typicalGapDays(db.salaryHistory().map { it.at })
                val s = Cycle.State(
                    salary.at, salary.at + gap * Cycle.DAY_MS,
                    salary.amountMinor, db.spentBetween(salary.at, now), now)
                append(if (s.overspent)
                    "Day ${s.dayNumber} of ${s.totalDays} — ${Money.format(-s.remainingMinor)} past your pay"
                else
                    "Day ${s.dayNumber} of ${s.totalDays} — ${Money.format(s.allowancePerDayMinor)} a day left")
            }
            if (unfiled > 0) {
                if (isNotEmpty()) append("\n")
                append("$unfiled still unfiled — tap to sort")
            }
        }

        db.logCall(spent, unfiled)

        val who = Caller.forDay(System.currentTimeMillis() / 86_400_000L)
        val fullScreen = PendingIntent.getActivity(
            context, 3,
            Intent(context, dev.nisarg.paisa.ui.CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, CaptureActivity::class.java)
                .putExtra("window_ms", 3650L * Cycle.DAY_MS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // A notification about money is the most swipeable object on a phone.
        // CallStyle is the least. Same information, delivered by something people
        // are conditioned not to dismiss without looking.
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setFullScreenIntent(fullScreen, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person = android.app.Person.Builder()
                .setName("${who.name} — ${who.line}")
                .setImportant(true)
                .build()
            runCatching {
                builder.setStyle(
                    Notification.CallStyle.forIncomingCall(person, declineIntent(context), fullScreen)
                )
            }
        }

        nm.notify(NOTIFICATION_ID, builder.build())
        Log.i(TAG, "calling as ${who.name}: $title")
    }
}

class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PaisaDb.get(context.applicationContext).snooze(0L)
        context.getSystemService(NotificationManager::class.java).cancel(4201)
    }
}

class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try { DailySummary.post(app) } finally { pending.finish() }
        }.start()
    }
}
