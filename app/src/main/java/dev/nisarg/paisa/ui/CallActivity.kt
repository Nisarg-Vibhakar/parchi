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
        val epochDay = System.currentTimeMillis() / 86_400_000L
        setContentView(build(Caller.forDay(epochDay)))
    }

    private fun money(m: Long) = Money.format(m).removePrefix("₹")

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
        roll.addView(line(who.name, 22f, Receipt.ink, bold = true, centre = true, topPad = 6))
        roll.addView(line("\"${who.line}\"", 12f, Receipt.stampAmber, centre = true, topPad = 6))
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
        roll.addView(action("ANSWER  —  SORT IT NOW", Receipt.stampGreen) {
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
        roll.addView(quiet("DECLINE") {
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
