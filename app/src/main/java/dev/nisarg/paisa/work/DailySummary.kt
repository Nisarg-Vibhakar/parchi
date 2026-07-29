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

    /**
     * The call may ring between HOUR and this, and at no other time.
     *
     * A phone call about your shopping at one in the morning is worse than no
     * call at all, and the alarm layer cannot promise it will not deliver late.
     * So the promise is kept here instead, where it is just an `if`.
     */
    const val LATEST_HOUR = 23

    const val ACTION_DECLINE = "decline"
    const val ACTION_SNOOZE = "snooze"

    /** Two hours: long enough to finish dinner, short enough to still be today. */
    const val SNOOZE_MS = 2 * 60 * 60 * 1000L

    /**
     * Arms the next evening's call. One-shot, re-armed after every delivery.
     *
     * This used to be `setInexactRepeating(RTC, …, INTERVAL_DAY, …)` and it
     * produced a call at one in the morning. Two things were wrong. `RTC`
     * without `_WAKEUP` does not wake the device, so a 9pm alarm on a sleeping
     * phone was not delivered at all — it sat pending until something else woke
     * the device, and a payment SMS at 00:30 did exactly that. And Doze defers
     * day-length inexact repeats hard, so even awake it drifted.
     *
     * `setAndAllowWhileIdle` survives Doze and still needs no
     * SCHEDULE_EXACT_ALARM, which this feature does not deserve. It is one-shot,
     * hence the re-arm in [DailySummaryReceiver].
     */
    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR)
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, dailyIntent(context))
        Log.i(TAG, "daily summary scheduled for ${next.time}")
    }

    private fun dailyIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 1, Intent(context, DailySummaryReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Declining still records the call; it just does not ring again. */
    private fun declineIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 4, Intent(context, SnoozeReceiver::class.java).setAction(ACTION_DECLINE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Rings again after the snooze, so a busy moment does not lose the day.
     *
     * Same wakeup treatment as [schedule] — with plain `RTC` the callback was
     * deferred or dropped while the device dozed, which is why snoozing appeared
     * to do nothing at all.
     */
    fun scheduleSnoozeCallback(context: Context, delayMs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs,
            PendingIntent.getBroadcast(
                context, 5, Intent(context, DailySummaryReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        Log.i(TAG, "callback in ${delayMs / 60000} minutes")
    }

    /**
     * Clears the call notification. Every way out of the call screen has to go
     * through here — dismissing the full-screen call used to leave "calling"
     * sitting in the shade, because only the decline broadcast ever cancelled it.
     */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /** True when the clock is inside the window this feature is allowed to ring in. */
    fun withinCallingHours(nowMs: Long = System.currentTimeMillis()): Boolean {
        val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
        return hour in HOUR..LATEST_HOUR
    }

    fun post(context: Context) {
        // A late delivery must never ring. The alarm layer gives no guarantee it
        // will fire on time, so the guarantee lives here: outside the window,
        // re-arm for tomorrow evening and stay silent.
        if (!withinCallingHours()) {
            Log.i(TAG, "delivered outside $HOUR:00-$LATEST_HOUR:59 — re-arming, not calling")
            schedule(context)
            return
        }

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

        val salaryNow = db.lastSalary()
        val state = salaryNow?.let {
            val gap = Cycle.typicalGapDays(db.salaryHistory().map { s -> s.at })
            Cycle.State(it.at, it.at + gap * Cycle.DAY_MS, it.amountMinor,
                db.spentBetween(it.at, now), now)
        }
        val who = Caller.forSpend(
            spentTodayMinor = spent,
            allowancePerDayMinor = state?.allowancePerDayMinor ?: 0L,
            cycleOverspent = state?.overspent == true,
            epochDay = System.currentTimeMillis() / 86_400_000L,
        )
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

        // The notification alone only takes the screen when the phone is locked.
        // With the overlay permission granted we can put the call up regardless,
        // which is the entire point — a call you can ignore is a notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(context)
        ) {
            runCatching {
                context.startActivity(
                    Intent(context, dev.nisarg.paisa.ui.CallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }.onFailure { Log.w(TAG, "could not take the screen: ${it.message}") }
        }
        Log.i(TAG, "calling as ${who.name} (${who.mood}): $title")
    }
}

/**
 * Handles the notification's own actions.
 *
 * This used to ignore [Intent.getAction] entirely and treat every broadcast as a
 * decline, which is why snoozing from the notification did nothing: it recorded
 * a decline and cancelled, and no callback was ever scheduled.
 */
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val db = PaisaDb.get(app)
        when (intent.action) {
            DailySummary.ACTION_SNOOZE -> {
                db.snooze(System.currentTimeMillis() + DailySummary.SNOOZE_MS)
                DailySummary.scheduleSnoozeCallback(app, DailySummary.SNOOZE_MS)
            }
            // Decline, and anything unrecognised: recorded, but not called back.
            else -> db.snooze(0L)
        }
        DailySummary.dismiss(app)
    }
}

class DailySummaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                DailySummary.post(app)
            } finally {
                // The daily alarm is one-shot now, so every delivery has to arm
                // the next one or the feature quietly stops after a single day.
                // post() also re-arms when it declines to ring; doing it twice is
                // harmless, missing it once is not.
                runCatching { DailySummary.schedule(app) }
                pending.finish()
            }
        }.start()
    }
}
