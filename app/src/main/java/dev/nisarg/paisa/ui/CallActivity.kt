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
        // A call screen that lets the display sleep mid-ring is not a call screen.
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        ring()
    }

    /**
     * singleTask re-delivers to the live instance instead of calling onCreate, so
     * without this a second call is silently swallowed — the screen keeps showing
     * the previous caller and never rings again.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        ring()
    }

    override fun onResume() {
        super.onResume()
        // onPause stops the ringtone, so coming back has to start it again.
        if (!ringing) ring()
    }

    private var ringing = false

    private fun ring() {
        ringer?.cancel()
        Ringer.stop()
        val who = whoIsCalling()
        setContentView(build(who))
        startRinging(who)
        ringing = true
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
        Ringer.start(this, who.mood)
        ringer = object : android.os.CountDownTimer(10 * 60_000L, 60L) {
            override fun onTick(remaining: Long) {
                val elapsed = System.currentTimeMillis() - started
                face?.pulse = (elapsed % 1400L) / 1400f
                timerLabel?.text = Caller.ringingLabel(elapsed)
            }
            // Nobody rings forever. Ten minutes and it gives up, and the call
            // stays in MISSED CALLS where it can be answered later.
            override fun onFinish() { Ringer.stop(); finish() }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        ringer?.cancel()
        Ringer.stop()
        // Every way out of this screen ends here — answered, snoozed, declined,
        // rang out, or backed out of — so this is the one place that reliably
        // clears the notification. Without it, dismissing the full-screen call
        // left "calling" sitting in the shade indefinitely, because only the
        // decline broadcast ever cancelled it. Guarded on isFinishing so a
        // configuration change does not silently dismiss a live call.
        if (isFinishing) DailySummary.dismiss(this)
    }

    /** Leaving the screen must silence it — a ringtone outliving its call is a bug. */
    override fun onPause() {
        super.onPause()
        ringer?.cancel()
        Ringer.stop()
        ringing = false
    }

    /**
     * The caller is chosen by what today actually cost.
     *
     * A forced mood exists only so the roster can be auditioned without spending
     * real money to reach the interesting end of it:
     *   adb shell am start -n dev.nisarg.paisa/.ui.CallActivity --es mood DOOM
     */
    private fun whoIsCalling(): Caller.Persona {
        intent?.getStringExtra("mood")?.uppercase()?.let { forced ->
            runCatching { Caller.Mood.valueOf(forced) }.getOrNull()?.let { mood ->
                val day = intent?.getLongExtra("voice", System.currentTimeMillis() / 86_400_000L)
                    ?: 0L
                return Caller.all.filter { it.mood == mood }.let { it[(day.mod(it.size.toLong())).toInt()] }
            }
        }
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
        roll.addView(line(Caller.subtitle(who.mood, db.missedCalls().size), 10f,
            Receipt.inkFaint, centre = true, topPad = 3))
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
            // Answering should cost something: they get the last word first.
            ringer?.cancel()
            Ringer.stop()
            timerLabel?.text = "connected"
            android.widget.Toast.makeText(this, Caller.greeting(who.mood),
                android.widget.Toast.LENGTH_LONG).show()
            db.clearSnooze()
            timerLabel?.postDelayed({
                startActivity(Intent(this, FileActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }, 1400L)
        })
        // Snoozing is not dismissal: it survives in the app so a busy evening
        // cannot quietly erase the day.
        roll.addView(action("SNOOZE  —  CALL BACK LATER", Receipt.ink) {
            Ringer.stop()
            db.snooze(System.currentTimeMillis() + DailySummary.SNOOZE_MS)
            DailySummary.scheduleSnoozeCallback(this, DailySummary.SNOOZE_MS)
            finish()
        })
        roll.addView(quiet(Caller.declineLabel(who.mood)) {
            Ringer.stop()
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
