package dev.nisarg.paisa.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.Categoriser
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.Design.animateIn
import dev.nisarg.paisa.ui.Receipt.TornEdge
import dev.nisarg.paisa.ui.Receipt.leaderRow
import dev.nisarg.paisa.ui.Receipt.line
import dev.nisarg.paisa.ui.Receipt.px
import dev.nisarg.paisa.ui.Receipt.rule

/**
 * Rapid filing.
 *
 * Filing 751 rows one at a time is not a task anyone finishes. Filing by
 * *merchant* is: payees repeat, so a single tap settles every row for that
 * payee — past and future — and merchants are served biggest-money-first, so
 * the unfiled rupee total collapses fastest even if you quit after a minute.
 *
 * TRANSFER sits alongside the categories because the biggest distortion in this
 * data was money that moved without being spent — card bills and settling up
 * with friends. Without a tile for it, the only way to record "not a spend"
 * would be to file it as one.
 */
class FileActivity : Activity() {

    private val db by lazy { PaisaDb.get(this) }

    private var queue: List<PaisaDb.UnfiledMerchant> = emptyList()
    private var index = 0
    private var filedRows = 0
    private var filedValue = 0L
    private var skipped = 0

    /** Everything before this is history; everything after is the month being watched. */
    private fun cycleStart(): Long =
        db.lastSalary()?.at ?: (System.currentTimeMillis() - 30L * 86_400_000)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        queue = db.unfiledMerchants()
        render()
    }

    /** A split made on the detail screen changes this merchant's numbers. */
    override fun onResume() {
        super.onResume()
        if (queue.isNotEmpty()) render()
    }

    private fun money(minor: Long) = Money.format(minor).removePrefix("₹")

    private fun render() {
        // Skipped merchants sit at the end of the queue rather than vanishing.
        val current = queue.getOrNull(index)
        setContentView(page(if (current == null) doneRoll() else fileRoll(current)))
    }

    // ---- one merchant at a time -------------------------------------------

    private fun fileRoll(m: PaisaDb.UnfiledMerchant): LinearLayout {
        val roll = roll()
        val remainingTotal = db.unfiledTotal()

        roll.addView(line("F I L I N G", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.32f, topPad = 12))
        roll.addView(line("${index + 1} OF ${queue.size} PAYEES", 10.5f, Receipt.inkSoft,
            centre = true, tracking = 0.2f, topPad = 3))
        roll.addView(rule("═"))

        roll.addView(line("STILL UNFILED", 10.5f, Receipt.inkSoft, tracking = 0.16f))
        roll.addView(line(money(remainingTotal), 22f, Receipt.inkSoft, bold = true))

        // Offered up front, because clearing the small historical tail is what
        // makes the remaining list short enough to be worth reading.
        val (smallN, smallV) = db.countSmallBefore(500_00L, cycleStart())
        if (smallN > 0) {
            roll.addView(TextView(this).apply {
                text = ">> SETTLE $smallN SMALL ONES (${money(smallV)})"
                textSize = 12f
                typeface = Receipt.monoBold
                gravity = Gravity.CENTER
                setTextColor(Receipt.stampAmber)
                minHeight = px(Design.TOUCH_MIN)
                setPadding(0, px(12), 0, px(2))
                setOnClickListener { confirmSettle(smallN, smallV) }
            })
            roll.addView(line("under ₹500, before this cycle — filed as Other",
                10f, Receipt.inkFaint))
        }
        roll.addView(rule("─"))

        roll.addView(line(db.displayName(m.merchant).uppercase(), 19f, Receipt.ink, bold = true, topPad = 8))
        // The raw handle stays visible under a name: it is the only thing that
        // ties the row back to the bank message.
        if (db.displayName(m.merchant) != m.merchant) {
            roll.addView(line(m.merchant, 10f, Receipt.inkFaint))
        }
        roll.addView(leaderRow(
            if (m.count == 1) "1 PAYMENT" else "${m.count} PAYMENTS",
            money(m.totalMinor), Receipt.inkSoft, Receipt.ink, bold = true
        ))
        m.ruleGuess?.let { roll.addView(line("looks like ${labelOf(it)}", 11f, Receipt.inkFaint)) }

        // The payments themselves. A name and a total alone asks the user to
        // recall an amount out of context; the date, time and original message
        // are what actually jog a memory.
        roll.addView(rule("─"))
        roll.addView(line("THE PAYMENTS", 10.5f, Receipt.inkSoft, tracking = 0.16f))
        for (pay in db.paymentsFor(m.merchant, 8)) {
            roll.addView(leaderRow(
                android.text.format.DateFormat.format("dd MMM, h:mm a", pay.at).toString().uppercase(),
                money(pay.amountMinor), Receipt.inkSoft, Receipt.ink
            ) {
                startActivity(android.content.Intent(this, SplitActivity::class.java)
                    .putExtra(SplitActivity.EXTRA_RAW_ID, pay.rawEventId))
            })
            pay.body?.let {
                roll.addView(line(
                    "   " + it.replace("\n", " ").take(64),
                    10f, Receipt.inkFaint))
            }
            if (pay.splits.isNotEmpty()) {
                roll.addView(line("   already split into ${pay.splits.size} parts",
                    10f, Receipt.stampGreen))
            }
        }
        if (m.count > 8) roll.addView(line("   ...and ${m.count - 8} more", 10f, Receipt.inkFaint))
        roll.addView(line("tap a payment to split it across categories",
            10.5f, Receipt.stampAmber, topPad = 6))

        roll.addView(rule())
        roll.addView(line("FILE ALL ${m.count} AS", 10.5f, Receipt.inkSoft, tracking = 0.16f))
        roll.addView(tiles(m) { category ->
            val n = db.fileMerchant(m.merchant, category)
            filedRows += n
            filedValue += m.totalMinor
            index++
            render()
        })

        roll.addView(TextView(this).apply {
            text = if (db.displayName(m.merchant) != m.merchant) "RENAME PAYEE" else "NAME THIS PAYEE"
            textSize = 11.5f
            typeface = Receipt.mono
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(Receipt.stampAmber)
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(14), 0, px(2))
            setOnClickListener { askForName(m.merchant) }
        })
        roll.addView(TextView(this).apply {
            text = "SKIP THIS ONE"
            textSize = 11.5f
            typeface = Receipt.mono
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(Receipt.inkFaint)
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(14), 0, px(6))
            setOnClickListener { skipped++; index++; render() }
        })
        roll.addView(closeRow("DONE FOR NOW"))
        return roll
    }

    // ---- the tally ---------------------------------------------------------

    private fun doneRoll(): LinearLayout {
        val roll = roll()
        roll.addView(line("F I L I N G", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.32f, topPad = 12))
        roll.addView(line("SESSION TOTAL", 10.5f, Receipt.inkSoft,
            centre = true, tracking = 0.2f, topPad = 3))
        roll.addView(rule("═"))

        roll.addView(leaderRow("PAYMENTS FILED", "$filedRows", Receipt.ink, Receipt.ink, bold = true))
        roll.addView(leaderRow("VALUE SORTED", money(filedValue), Receipt.inkSoft, Receipt.ink))
        if (skipped > 0) roll.addView(leaderRow("SKIPPED", "$skipped", Receipt.inkFaint, Receipt.inkFaint))
        roll.addView(rule("─"))
        roll.addView(leaderRow("STILL UNFILED", money(db.unfiledTotal()), Receipt.inkSoft, Receipt.inkSoft))

        roll.addView(line(
            if (queue.isEmpty()) "Nothing left to file."
            else "Every answer is remembered — those payees\nfile themselves from now on.",
            11.5f, Receipt.inkSoft, topPad = 14))

        // More merchants may exist beyond the first page, or be freshly skipped.
        roll.addView(TextView(this).apply {
            text = "[ LOAD MORE ]"
            textSize = 12.5f
            typeface = Receipt.monoBold
            gravity = Gravity.CENTER
            setTextColor(Receipt.ink)
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(18), 0, px(6))
            setOnClickListener {
                queue = db.unfiledMerchants()
                index = 0
                render()
            }
        })
        roll.addView(closeRow("CLOSE"))
        return roll
    }

    // ---- parts -------------------------------------------------------------

    /**
     * Every category, ranked. Filing is a deliberate sit-down session, so the
     * full set is shown rather than a guessed six — hunting for a missing
     * category is worse than scrolling past one you did not need.
     *
     * Order: the rule's guess, then what this user actually uses, then their own
     * invented ones, then the rest.
     */
    private fun choices(m: PaisaDb.UnfiledMerchant): List<String> {
        val out = LinkedHashSet<String>()
        m.ruleGuess?.let(out::add)
        db.favouriteCategories().forEach(out::add)
        db.customCategories().forEach(out::add)
        Categoriser.Category.entries.forEach { if (it != Categoriser.Category.OTHER) out.add(it.name) }
        return out.toList()
    }

    private fun labelOf(name: String): String =
        runCatching { Categoriser.Category.valueOf(name).label }
            .getOrDefault(name.lowercase().replaceFirstChar { it.uppercase() })

    private fun isSpending(name: String): Boolean =
        runCatching { Categoriser.Category.valueOf(name).isSpending }.getOrDefault(true)

    /**
     * Bank SMS carries UPI handles, not shop names. Only the user knows that
     * "vyapar.900000000001@hdfcbank" is a bike showroom, and having told us once
     * they should never be asked again.
     */
    /**
     * Bulk changes get a confirmation with the real numbers in it. A tap that
     * silently rewrites 190 rows is not something to discover afterwards.
     */
    private fun confirmSettle(n: Int, value: Long) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Settle $n small payments?")
            .setMessage(
                "Everything under ₹500 from before this cycle — ${money(value)} in total — " +
                    "will be filed as Other.\n\nThis cycle is left untouched, and no payee " +
                    "is taught anything, so future payments still ask."
            )
            .setPositiveButton("SETTLE") { _, _ ->
                val done = db.settleSmallBefore(500_00L, cycleStart())
                queue = db.unfiledMerchants()
                index = 0
                filedRows += done
                android.widget.Toast.makeText(
                    this, "Settled $done payments", android.widget.Toast.LENGTH_LONG).show()
                render()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun askForName(merchant: String) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Shradha Motors"
            setText(db.displayName(merchant).takeIf { it != merchant } ?: "")
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setTextColor(Receipt.ink)
            setHintTextColor(Receipt.inkFaint)
            typeface = Receipt.mono
            setPadding(px(16), px(16), px(16), px(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Name this payee")
            .setMessage(merchant)
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                db.setAlias(merchant, input.text.toString()); render()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    /** Lets the category list grow to fit a life, instead of the life being filed
     *  into whatever list shipped. */
    private fun askForNewCategory(onNamed: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. RENT SHARE, HAIRCUT, PARENTS"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(24))
            setTextColor(Receipt.ink)
            setHintTextColor(Receipt.inkFaint)
            typeface = Receipt.mono
            setPadding(px(16), px(16), px(16), px(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("New category")
            .setView(input)
            .setPositiveButton("CREATE") { _, _ ->
                db.addCustomCategory(input.text.toString())?.let(onNamed)
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun tiles(m: PaisaDb.UnfiledMerchant, onPick: (String) -> Unit) =
        GridLayout(this).apply {
            columnCount = 2
            setPadding(0, px(8), 0, 0)
            for (name in choices(m)) addView(TextView(this@FileActivity).apply {
                val notSpending = !isSpending(name)
                val label = labelOf(name).uppercase()
                text = if (notSpending) "$label *" else label
                textSize = 12.5f
                typeface = if (notSpending) Receipt.monoBold else Receipt.mono
                gravity = Gravity.CENTER
                setTextColor(if (notSpending) Receipt.stampGreen else Receipt.ink)
                contentDescription = "File as $label"
                background = dashedCell()
                minHeight = px(Design.TOUCH_MIN + 6)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener { onPick(name) }
            })
            addView(TextView(this@FileActivity).apply {
                text = "CAN'T REMEMBER"
                textSize = 12.5f
                typeface = Receipt.mono
                gravity = Gravity.CENTER
                setTextColor(Receipt.inkFaint)
                background = dashedCell()
                minHeight = px(Design.TOUCH_MIN + 6)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 2, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener {
                    // Files it away without teaching the payee, so it stops
                    // reappearing but a future payment can still be asked about.
                    filedRows += db.giveUpOn(m.merchant)
                    filedValue += m.totalMinor
                    index++
                    render()
                }
            })
            addView(TextView(this@FileActivity).apply {
                text = "+ NEW CATEGORY"
                textSize = 12.5f
                typeface = Receipt.monoBold
                gravity = Gravity.CENTER
                setTextColor(Receipt.stampAmber)
                background = dashedCell()
                minHeight = px(Design.TOUCH_MIN + 6)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(0, 2, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener { askForNewCategory { onPick(it) } }
            })
            addView(TextView(this@FileActivity).apply {
                text = "* not counted as spending"
                textSize = 10f
                typeface = Receipt.mono
                setTextColor(Receipt.inkFaint)
                setPadding(px(4), px(8), 0, 0)
                layoutParams = GridLayout.LayoutParams().apply {
                    columnSpec = GridLayout.spec(0, 2)
                }
            })
        }

    private fun dashedCell() = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(px(1), Receipt.inkFaint, px(4).toFloat(), px(3).toFloat())
    }

    private fun closeRow(label: String) = TextView(this).apply {
        text = label
        textSize = 11.5f
        typeface = Receipt.mono
        gravity = Gravity.CENTER
        letterSpacing = 0.12f
        setTextColor(Receipt.inkFaint)
        minHeight = px(Design.TOUCH_MIN)
        setPadding(0, px(10), 0, px(14))
        setOnClickListener { finish() }
    }

    private fun roll() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Receipt.paper)
        setPadding(px(20), px(6), px(20), px(8))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    private fun page(body: LinearLayout): View {
        val slip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Receipt.paperEdge)
            addView(TornEdge(this@FileActivity, pointingDown = false))
            addView(body)
            addView(TornEdge(this@FileActivity, pointingDown = true))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        slip.animateIn(this)
        return android.widget.ScrollView(this).apply {
            setBackgroundColor(Receipt.paperEdge)
            isFillViewport = false
            addView(slip)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                    insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
                val top = bars?.top ?: @Suppress("DEPRECATION") insets.systemWindowInsetTop
                val bot = bars?.bottom ?: @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(0, top, 0, bot)
                insets
            }
        }
    }
}
