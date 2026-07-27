package dev.nisarg.paisa.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.Categoriser
import dev.nisarg.paisa.parse.Cycle
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.Receipt.BlockMeter
import dev.nisarg.paisa.ui.Receipt.Stamp
import dev.nisarg.paisa.ui.Receipt.TornEdge
import dev.nisarg.paisa.ui.Receipt.leaderRow
import dev.nisarg.paisa.ui.Receipt.line
import dev.nisarg.paisa.ui.Receipt.px
import dev.nisarg.paisa.ui.Receipt.receiptColumn
import dev.nisarg.paisa.ui.Receipt.rule
import java.util.Calendar

/**
 * Printed as a receipt.
 *
 * Two views of the same roll:
 *   CYCLE   — payday to payday, because that is how money is actually lived.
 *             The headline is the DAY; a rupee total alone cannot tell you
 *             whether you are fine.
 *   EXPLORE — any window, for what the cycle cannot answer.
 */
class HomeActivity : Activity() {

    private val db by lazy { PaisaDb.get(this) }

    private enum class Tab { CYCLE, EXPLORE }
    private var tab = Tab.CYCLE

    private var rangeFrom = 0L
    private var rangeTo = 0L
    private var rangeLabel = "LAST 30 DAYS"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rangeTo = System.currentTimeMillis()
        rangeFrom = rangeTo - 30L * Cycle.DAY_MS
        // adb shell am start -n dev.nisarg.paisa/.ui.HomeActivity --es tab explore
        if (intent?.getStringExtra("tab")?.lowercase() == "explore") tab = Tab.EXPLORE
    }

    override fun onResume() {
        super.onResume()
        draw()
    }

    private fun money(minor: Long) = Money.format(minor).removePrefix("₹")

    private fun date(ms: Long): String =
        android.text.format.DateFormat.format("dd MMM", ms).toString().uppercase()

    // ---- the roll ----------------------------------------------------------

    private fun draw() {
        val roll = receiptColumn()

        roll.addView(line("P A R C H I", 15f, Receipt.ink, bold = true,
            centre = true, tracking = 0.34f, topPad = 14))
        roll.addView(line(
            if (tab == Tab.CYCLE) "SPEND RECEIPT" else "CUSTOM RANGE",
            10.5f, Receipt.inkSoft, centre = true, tracking = 0.22f, topPad = 3))
        roll.addView(rule("═"))

        when (tab) {
            Tab.CYCLE -> cycleTab(roll)
            Tab.EXPLORE -> exploreTab(roll)
        }

        roll.addView(rule("═"))
        roll.addView(tabSwitch())
        roll.addView(line("* * *", 12f, Receipt.inkFaint, centre = true, topPad = 12))
        roll.addView(TextView(this).apply {
            text = "DIAGNOSTICS"
            textSize = 11f
            typeface = Receipt.mono
            gravity = Gravity.CENTER
            letterSpacing = 0.14f
            setTextColor(Receipt.inkFaint)
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(12), 0, px(14))
            setOnClickListener { startActivity(Intent(this@HomeActivity, DebugActivity::class.java)) }
        })

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Receipt.paperEdge)
            addView(TornEdge(this@HomeActivity, pointingDown = false))
            addView(roll)
            addView(TornEdge(this@HomeActivity, pointingDown = true))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Receipt.paperEdge)
            isFillViewport = true
            addView(page)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
                val top = bars?.top ?: @Suppress("DEPRECATION") insets.systemWindowInsetTop
                val bot = bars?.bottom ?: @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                page.setPadding(0, top, 0, bot + px(24))
                insets
            }
        })
    }

    private fun tabSwitch() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(switchCell("[ CYCLE ]", tab == Tab.CYCLE) { tab = Tab.CYCLE; draw() })
        addView(switchCell("[ EXPLORE ]", tab == Tab.EXPLORE) { tab = Tab.EXPLORE; draw() })
    }

    private fun switchCell(label: String, active: Boolean, onTap: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            typeface = if (active) Receipt.monoBold else Receipt.mono
            // Brackets plus weight carry the state, so it never rests on colour.
            setTextColor(if (active) Receipt.ink else Receipt.inkFaint)
            gravity = Gravity.CENTER
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(12), 0, px(12))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setOnClickListener { onTap() }
        }

    // ---- cycle -------------------------------------------------------------

    private fun cycleTab(roll: LinearLayout) {
        val salary = db.lastSalary()
        val now = System.currentTimeMillis()

        if (salary == null) {
            roll.addView(line("NO SALARY CREDIT FOUND", 13f, Receipt.inkSoft, topPad = 8))
            roll.addView(line("Falling back to the calendar month until", 12f, Receipt.inkFaint, topPad = 6))
            roll.addView(line("a payslip SMS arrives.", 12f, Receipt.inkFaint))
            roll.addView(rule())
            val monthStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val spent = db.spentBetween(monthStart, now)
            roll.addView(bigFigure("SPENT THIS MONTH", money(spent), Receipt.ink))
            breakdown(roll, monthStart, now, spent)
            recent(roll)
            return
        }

        val gap = Cycle.typicalGapDays(db.salaryHistory().map { it.at })
        val s = Cycle.State(
            startAt = salary.at,
            expectedEndAt = salary.at + gap * Cycle.DAY_MS,
            budgetMinor = salary.amountMinor,
            spentMinor = db.spentBetween(salary.at, now),
            now = now,
        )
        val tint = when (s.pace) {
            Cycle.Pace.AHEAD -> Receipt.stampGreen
            Cycle.Pace.ON_TRACK -> Receipt.ink
            Cycle.Pace.BEHIND -> Receipt.stampAmber
            Cycle.Pace.OVERRUN -> Receipt.stampRed
        }

        roll.addView(line("${date(s.startAt)} — ${date(s.expectedEndAt)}", 11.5f,
            Receipt.inkSoft, tracking = 0.1f))

        // The day is the headline; the money only means something against it.
        roll.addView(line("DAY ${s.dayNumber} / ${s.totalDays}", 34f, Receipt.ink,
            bold = true, tracking = -0.02f, topPad = 6))
        roll.addView(line("${s.daysLeft} DAYS TO NEXT PAY", 11.5f, Receipt.inkSoft, tracking = 0.12f))

        roll.addView(rule())
        roll.addView(leaderRow("SPENT", money(s.spentMinor)))
        roll.addView(leaderRow("PAY IN", money(s.budgetMinor), Receipt.inkSoft, Receipt.inkSoft))
        roll.addView(rule("─"))
        roll.addView(leaderRow(
            if (s.overspent) "OVER BY" else "REMAINING",
            money(if (s.overspent) -s.remainingMinor else s.remainingMinor),
            Receipt.ink, tint, bold = true
        ))

        roll.addView(BlockMeter(this).apply {
            set(s.spendFraction, s.timeFraction, tint)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = px(10)
            }
        })
        roll.addView(line("│ marks where today should be", 10.5f, Receipt.inkFaint, topPad = 2))

        // The stamp is the verdict, and the only flourish on the page.
        roll.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(20), 0, px(10))
            addView(Stamp(this@HomeActivity, when (s.pace) {
                Cycle.Pace.AHEAD -> "UNDER PACE"
                Cycle.Pace.ON_TRACK -> "ON PACE"
                Cycle.Pace.BEHIND -> "OVER PACE"
                Cycle.Pace.OVERRUN -> if (s.overspent) "OVERSPENT" else "WILL RUN OUT"
            }, tint))
        })

        val oneOff = db.oneOffTotal(s.startAt, now)
        if (oneOff > 0) {
            roll.addView(rule("─"))
            roll.addView(leaderRow("OF WHICH ONE-OFF", money(oneOff),
                Receipt.inkSoft, Receipt.stampAmber))
            roll.addView(leaderRow("TYPICAL SPEND", money(s.spentMinor - oneOff),
                Receipt.ink, Receipt.ink, bold = true))
            roll.addView(line("one-offs are counted, but they are not a trend",
                10f, Receipt.inkFaint))
        }

        roll.addView(rule())
        roll.addView(leaderRow("BURN / DAY", money(s.burnPerDayMinor), Receipt.inkSoft))
        roll.addView(leaderRow("CYCLE AFFORDS", money(s.budgetPerDayMinor), Receipt.inkSoft))
        if (!s.overspent) {
            roll.addView(leaderRow("LEFT / DAY", money(s.allowancePerDayMinor), Receipt.ink, tint, bold = true))
        }
        roll.addView(line(verdict(s), 11.5f, Receipt.inkSoft, topPad = 10))

        // Shown, not hidden: a total that silently drops ₹26k is no more
        // trustworthy than one that overstates it.
        val aside = db.nonSpendTotals(s.startAt, now)
        if (aside.isNotEmpty()) {
            roll.addView(rule("─"))
            roll.addView(line("NOT COUNTED AS SPENDING", 10.5f, Receipt.inkFaint, tracking = 0.14f))
            for ((cat, amt) in aside) {
                roll.addView(leaderRow(
                    if (cat == "TRANSFER") "CARD BILLS / TRANSFERS" else "INVESTED",
                    money(amt), Receipt.inkFaint, Receipt.inkFaint
                ))
            }
        }

        val pending = db.uncategorisedCount()
        if (pending > 0) {
            roll.addView(rule())
            roll.addView(TextView(this).apply {
                text = ">> ${money(db.unfiledTotal())} UNFILED — TAP TO SORT"
                textSize = 12.5f
                typeface = Receipt.monoBold
                setTextColor(Receipt.ink)
                gravity = Gravity.CENTER
                minHeight = px(Design.TOUCH_MIN)
                setPadding(0, px(12), 0, px(12))
                setOnClickListener {
                    startActivity(Intent(this@HomeActivity, FileActivity::class.java))
                }
            })
        }

        breakdown(roll, s.startAt, now, s.spentMinor)
        recent(roll)
    }

    private fun verdict(s: Cycle.State) = when (s.pace) {
        Cycle.Pace.AHEAD -> "Comfortably under. You could ease up."
        Cycle.Pace.ON_TRACK -> "Right on the line."
        Cycle.Pace.BEHIND -> "Over the line, but it holds to payday."
        Cycle.Pace.OVERRUN -> if (s.overspent)
            "Past this cycle's pay with ${s.daysLeft} days to go."
        else "At this rate it runs dry in ${s.runwayDays} days, ${s.daysLeft} to go."
    }

    // ---- explore -----------------------------------------------------------

    private fun exploreTab(roll: LinearLayout) {
        roll.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, px(6), 0, px(4))
            addView(preset("7D", 7)); addView(preset("30D", 30))
            addView(preset("90D", 90)); addView(preset("1Y", 365))
        })
        roll.addView(dateRow("FROM", rangeFrom) { rangeFrom = it; rangeLabel = "CUSTOM"; draw() })
        roll.addView(dateRow("TO", rangeTo) {
            rangeTo = it + Cycle.DAY_MS - 1; rangeLabel = "CUSTOM"; draw()
        })
        roll.addView(rule())

        val spent = db.spentBetween(rangeFrom, rangeTo)
        val count = db.countBetween(rangeFrom, rangeTo)
        val days = ((rangeTo - rangeFrom) / Cycle.DAY_MS).toInt().coerceAtLeast(1)

        roll.addView(bigFigure(rangeLabel, money(spent), Receipt.ink))
        roll.addView(leaderRow("PAYMENTS", "$count", Receipt.inkSoft, Receipt.inkSoft))
        roll.addView(leaderRow("PER DAY", money(spent / days), Receipt.inkSoft, Receipt.inkSoft))

        breakdown(roll, rangeFrom, rangeTo, spent)

        roll.addView(rule())
        roll.addView(line("BIGGEST DESTINATIONS", 11.5f, Receipt.inkSoft, tracking = 0.14f))
        val tops = db.topMerchants(rangeFrom, rangeTo, 8)
        if (tops.isEmpty()) roll.addView(line("nothing in range", 12f, Receipt.inkFaint, topPad = 6))
        for ((name, total, n) in tops) {
            roll.addView(leaderRow(db.displayName(name).take(20).uppercase() + "  x$n", money(total), Receipt.inkSoft))
        }
    }

    private fun preset(label: String, days: Int) = TextView(this).apply {
        val active = rangeLabel == "LAST $days DAYS"
        text = if (active) "[$label]" else " $label "
        textSize = 12.5f
        typeface = if (active) Receipt.monoBold else Receipt.mono
        setTextColor(if (active) Receipt.ink else Receipt.inkFaint)
        gravity = Gravity.CENTER
        minHeight = px(Design.TOUCH_MIN)
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        setOnClickListener {
            rangeTo = System.currentTimeMillis()
            rangeFrom = rangeTo - days.toLong() * Cycle.DAY_MS
            rangeLabel = "LAST $days DAYS"
            draw()
        }
    }

    private fun dateRow(label: String, value: Long, onPick: (Long) -> Unit) = leaderRow(
        label,
        android.text.format.DateFormat.format("dd MMM yyyy", value).toString().uppercase(),
        Receipt.inkSoft, Receipt.ink
    ) {
        val c = Calendar.getInstance().apply { timeInMillis = value }
        DatePickerDialog(this, { _, y, m, d ->
            onPick(Calendar.getInstance().apply {
                set(y, m, d, 0, 0, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---- shared ------------------------------------------------------------

    private fun bigFigure(label: String, value: String, colour: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, px(6), 0, px(6))
        addView(line(label, 11f, Receipt.inkSoft, tracking = 0.16f))
        addView(line(value, 32f, colour, bold = true, tracking = -0.02f))
    }

    private fun breakdown(roll: LinearLayout, from: Long, to: Long, total: Long) {
        roll.addView(rule())
        roll.addView(line("WHERE IT WENT", 11.5f, Receipt.inkSoft, tracking = 0.14f))
        val rows = db.categoryTotals(from, to)
        if (rows.isEmpty() || total == 0L) {
            roll.addView(line("nothing in range", 12f, Receipt.inkFaint, topPad = 6)); return
        }
        for ((category, amount) in rows.take(9)) {
            val name = category?.let {
                runCatching { Categoriser.Category.valueOf(it).label.uppercase() }.getOrDefault(it)
            } ?: "UNFILED"
            val pct = 100 * amount / total
            roll.addView(leaderRow(
                "$name  ${pct}%", money(amount),
                if (category == null) Receipt.inkFaint else Receipt.ink,
                if (category == null) Receipt.inkFaint else Receipt.ink
            ))
        }
    }

    private fun recent(roll: LinearLayout) {
        roll.addView(rule())
        roll.addView(line("RECENT", 11.5f, Receipt.inkSoft, tracking = 0.14f))
        val txns = db.recentTransactions(12)
        if (txns.isEmpty()) {
            roll.addView(line("nothing captured yet", 12f, Receipt.inkFaint, topPad = 6)); return
        }
        for (t in txns) {
            roll.addView(leaderRow(
                "${date(t.at)} ${db.displayName(t.merchant).take(16).uppercase()}",
                money(t.amountMinor), Receipt.ink
            ))
            roll.addView(line(
                "        " + (t.category?.let {
                    runCatching { Categoriser.Category.valueOf(it).label.lowercase() }.getOrDefault(it)
                } ?: "unfiled"),
                10.5f, Receipt.inkFaint
            ))
        }
    }
}
