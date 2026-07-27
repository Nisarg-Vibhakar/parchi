package dev.nisarg.paisa.ui

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.Cycle
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.Receipt.TornEdge
import dev.nisarg.paisa.ui.Receipt.leaderRow
import dev.nisarg.paisa.ui.Receipt.line
import dev.nisarg.paisa.ui.Receipt.px
import dev.nisarg.paisa.ui.Receipt.rule
import dev.nisarg.paisa.work.Caller
import dev.nisarg.paisa.work.DailySummary
import java.util.Calendar

/**
 * The incoming call.
 *
 * A notification about money is the most swipeable object on a phone. A call is
 * the least. Same information, delivered by something you are conditioned not to
 * dismiss without looking.
 *
 * It is a joke, and it stays one: the caller is absurd, the app's name is on the
 * screen, and declining costs nothing.
 */
class CallActivity : Activity() {

    private val db by lazy { PaisaDb.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val who = whoIsCalling()
        setContentView(build(who))
        startRinging(who)
    }

    private var ringer: android.os.CountDownTimer? = null
    private var face: CallerFace? = null
    private var timerLabel: TextView? = null

    private fun money(m: Long) = Money.format(m).removePrefix("₹")

    /**
     * Rings: pulsing avatar, a counting timer, and a heartbeat buzz. A static
     * screen reads as a dialog; motion is what makes it feel like a call you have
     * to deal with.
     */
    private fun startRinging(who: Caller.Persona) {
        val started = System.currentTimeMillis()
        val vibrator = getSystemService(android.os.Vibrator::class.java)
        ringer = object : android.os.CountDownTimer(10 * 60_000L, 60L) {
            var lastBuzz = 0L
            override fun onTick(remaining: Long) {
                val elapsed = System.currentTimeMillis() - started
                face?.pulse = (elapsed % 1400L) / 1400f
                timerLabel?.text = "ringing  %d:%02d".format(elapsed / 60000, (elapsed / 1000) % 60)
                // A double buzz every three seconds, like a real ringtone pattern.
                if (elapsed - lastBuzz > 3000) {
                    lastBuzz = elapsed
                    runCatching {
                        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(
                            longArrayOf(0, 90, 120, 90), -1))
                    }
                }
            }
            override fun onFinish() {}
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringer?.cancel()
    }

    /** The caller is chosen by what today actually cost. */
    private fun whoIsCalling(): Caller.Persona {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val state = db.lastSalary()?.let {
            val gap = Cycle.typicalGapDays(db.salaryHistory().map { s -> s.at })
            Cycle.State(it.at, it.at + gap * Cycle.DAY_MS, it.amountMinor,
                db.spentBetween(it.at, now), now)
        }
        return Caller.forSpend(
            db.spentBetween(startOfDay, now),
            state?.allowancePerDayMinor ?: 0L,
            state?.overspent == true,
            now / 86_400_000L,
        )
    }

    private fun build(who: Caller.Persona): View {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()

        val roll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Receipt.paper)
            setPadding(px(22), px(10), px(22), px(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        roll.addView(line("INCOMING", 10.5f, Receipt.inkFaint, centre = true,
            tracking = 0.3f, topPad = 10))
        roll.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(6), 0, px(2))
            addView(CallerFace(this@CallActivity, who.mood).also { face = it })
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        })
        roll.addView(line(who.name, 22f, Receipt.ink, bold = true, centre = true, topPad = 4))
        roll.addView(line(Caller.subtitle(who.mood), 10f, Receipt.inkFaint, centre = true, topPad = 3))
        roll.addView(line("\"${who.line}\"", 12.5f, Receipt.stampAmber, centre = true, topPad = 8))

        // The line that actually lands: your own biggest payment today, read back
        // to you by someone disappointed about it.
        val top = db.biggestToday(startOfDay, now)
        Caller.jibe(top?.first?.let { db.displayName(it) }, top?.second ?: 0L,
            db.countBetween(startOfDay, now), who.mood)?.let {
            roll.addView(line("\"$it\"", 11.5f, Receipt.inkSoft, centre = true, topPad = 6))
        }

        timerLabel = line("ringing  0:00", 10f, Receipt.inkFaint, centre = true, topPad = 8)
        roll.addView(timerLabel)
        roll.addView(rule("═"))

        roll.addView(leaderRow("SPENT TODAY", money(db.spentBetween(startOfDay, now)),
            Receipt.inkSoft, Receipt.ink, bold = true))
        roll.addView(leaderRow("PAYMENTS", "${db.countBetween(startOfDay, now)}",
            Receipt.inkSoft, Receipt.inkSoft))

        db.lastSalary()?.let { salary ->
            val gap = Cycle.typicalGapDays(db.salaryHistory().map { it.at })
            val s = Cycle.State(salary.at, salary.at + gap * Cycle.DAY_MS,
                salary.amountMinor, db.spentBetween(salary.at, now), now)
            roll.addView(leaderRow(
                if (s.overspent) "OVER BY" else "LEFT PER DAY",
                money(if (s.overspent) -s.remainingMinor else s.allowancePerDayMinor),
                Receipt.inkSoft,
                if (s.overspent) Receipt.stampRed else Receipt.ink))
        }
        val unfiled = db.uncategorisedCount()
        if (unfiled > 0) {
            roll.addView(leaderRow("UNFILED", "$unfiled", Receipt.inkSoft, Receipt.stampAmber))
        }

        roll.addView(rule("─"))
        roll.addView(action(Caller.answerLabel(who.mood), Receipt.stampGreen) {
            db.clearSnooze()
            startActivity(Intent(this, FileActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        })
        // Snoozing is not dismissal: it survives in the app so a busy evening
        // cannot quietly erase the day.
        roll.addView(action("SNOOZE  —  CALL BACK LATER", Receipt.ink) {
            db.snooze(System.currentTimeMillis() + 2 * 60 * 60 * 1000L)
            DailySummary.scheduleSnoozeCallback(this, 2 * 60 * 60 * 1000L)
            finish()
        })
        roll.addView(quiet(Caller.declineLabel(who.mood)) {
            db.snooze(0L)   // still recorded, just not called back
            finish()
        })
        roll.addView(line("it's Parchi. you're fine.", 10f, Receipt.inkFaint, centre = true))

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Receipt.paperEdge)
            setPadding(px(12), px(12), px(12), px(12))
            addView(LinearLayout(this@CallActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Receipt.paperEdge)
                addView(TornEdge(this@CallActivity, pointingDown = false))
                addView(roll)
                addView(TornEdge(this@CallActivity, pointingDown = true))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            })
        }
        return page
    }

    private fun dashed() = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(px(1), Receipt.inkFaint, px(4).toFloat(), px(3).toFloat())
    }

    private fun action(t: String, colour: Int, onTap: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        typeface = Receipt.monoBold
        gravity = Gravity.CENTER
        setTextColor(colour)
        background = dashed()
        minHeight = px(Design.TOUCH_MIN)
        setPadding(px(12), px(15), px(12), px(15))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(8)
        }
        setOnClickListener { onTap() }
    }

    private fun quiet(t: String, onTap: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 11.5f
        typeface = Receipt.mono
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
        setTextColor(Receipt.inkFaint)
        minHeight = px(Design.TOUCH_MIN)
        setPadding(0, px(14), 0, px(6))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onTap() }
    }
}
