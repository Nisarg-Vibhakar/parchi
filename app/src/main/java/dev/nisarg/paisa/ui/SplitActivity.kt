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
import android.widget.ScrollView
import android.widget.TextView
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.Categoriser
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.Receipt.TornEdge
import dev.nisarg.paisa.ui.Receipt.leaderRow
import dev.nisarg.paisa.ui.Receipt.line
import dev.nisarg.paisa.ui.Receipt.px
import dev.nisarg.paisa.ui.Receipt.rule

/**
 * Splitting one payment across purposes.
 *
 * Rent is the case that forces this: the landlord is paid in full by one person,
 * part of it their own cost and part collected from flatmates. Filing the whole
 * payment as Rent overstates their spending; filing it as recovered understates
 * it. Only parts are true.
 *
 * The remainder is always visible and the split cannot be saved until it reaches
 * zero — a split that does not add up to the payment would silently invent or
 * destroy money.
 */
class SplitActivity : Activity() {

    companion object {
        const val EXTRA_RAW_ID = "raw_event_id"
    }

    private val db by lazy { PaisaDb.get(this) }
    private var rawId = 0L
    private lateinit var payment: PaisaDb.Payment

    private val parts = mutableListOf<PaisaDb.Split>()
    private var pendingCategory: String? = null
    private var typed = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rawId = intent.getLongExtra(EXTRA_RAW_ID, -1L)
        val found = db.paymentById(rawId)
        if (found == null) { finish(); return }
        payment = found
        parts.addAll(payment.splits)
        render()
    }

    private fun money(minor: Long) = Money.format(minor).removePrefix("₹")
    private fun assigned() = parts.sumOf { it.amountMinor }
    private fun remainder() = payment.amountMinor - assigned()

    private fun label(name: String) =
        runCatching { Categoriser.Category.valueOf(name).label }
            .getOrDefault(name.lowercase().replaceFirstChar { it.uppercase() })

    // ---- screen ------------------------------------------------------------

    private fun render() {
        val roll = roll()
        roll.addView(line("S P L I T", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.32f, topPad = 12))
        roll.addView(rule("═"))

        roll.addView(line(db.displayName(payment.merchant).uppercase(), 16f, Receipt.ink, bold = true))
        roll.addView(line(
            android.text.format.DateFormat.format("dd MMM yyyy, h:mm a", payment.at).toString(),
            11.5f, Receipt.inkFaint))
        roll.addView(leaderRow("PAYMENT", money(payment.amountMinor), Receipt.inkSoft, Receipt.ink, bold = true))
        roll.addView(rule("─"))

        if (parts.isEmpty()) {
            roll.addView(line("no parts yet", 12f, Receipt.inkFaint, topPad = 4))
        } else {
            for ((i, p) in parts.withIndex()) {
                roll.addView(leaderRow(
                    "${i + 1}. ${label(p.category).uppercase()}", money(p.amountMinor),
                    Receipt.ink, Receipt.ink
                ) { parts.removeAt(i); render() })
            }
            roll.addView(line("tap a part to remove it", 10.5f, Receipt.inkFaint))
        }

        // Marking the exceptional is per-payment, and this is the only screen
        // that already addresses a single payment.
        val oneOff = db.isOneOff(rawId)
        roll.addView(TextView(this).apply {
            text = if (oneOff) "[x] ONE-OFF — EXCLUDED FROM TRENDS"
            else "[ ] MARK AS A ONE-OFF"
            textSize = 12f
            typeface = if (oneOff) Receipt.monoBold else Receipt.mono
            setTextColor(if (oneOff) Receipt.stampAmber else Receipt.inkSoft)
            minHeight = px(Design.TOUCH_MIN)
            setPadding(0, px(12), 0, px(4))
            setOnClickListener { db.setOneOff(rawId, !oneOff); render() }
        })
        roll.addView(line("a bike, a deposit, a wedding gift — real spending,\nbut not a pattern",
            10f, Receipt.inkFaint))

        roll.addView(rule("─"))
        val left = remainder()
        roll.addView(leaderRow(
            "REMAINDER", money(left), Receipt.inkSoft,
            if (left == 0L) Receipt.stampGreen else Receipt.stampAmber, bold = true
        ))

        when {
            pendingCategory != null -> roll.addView(amountPad(roll))
            left > 0L -> {
                roll.addView(line("ADD A PART AS", 10.5f, Receipt.inkSoft,
                    tracking = 0.16f, topPad = 12))
                roll.addView(categoryGrid())
            }
        }

        if (left == 0L && parts.isNotEmpty()) {
            roll.addView(bigButton("SAVE SPLIT", Receipt.stampGreen) {
                db.saveSplit(rawId, parts.toList()); finish()
            })
        }
        if (payment.splits.isNotEmpty()) {
            roll.addView(quiet("REMOVE SPLIT") { db.clearSplit(rawId); finish() })
        }
        roll.addView(quiet("CANCEL") { finish() })

        setContentView(page(roll))
    }

    // ---- entering a part ---------------------------------------------------

    private fun categoryGrid(): View {
        val names = LinkedHashSet<String>()
        db.customCategories().forEach(names::add)
        Categoriser.Category.entries.forEach {
            if (it != Categoriser.Category.OTHER) names.add(it.name)
        }
        return GridLayout(this).apply {
            columnCount = 2
            setPadding(0, px(6), 0, 0)
            for (n in names) addView(TextView(this@SplitActivity).apply {
                val notSpending =
                    runCatching { !Categoriser.Category.valueOf(n).isSpending }.getOrDefault(false)
                text = if (notSpending) "${label(n).uppercase()} *" else label(n).uppercase()
                textSize = 12f
                typeface = if (notSpending) Receipt.monoBold else Receipt.mono
                gravity = Gravity.CENTER
                setTextColor(if (notSpending) Receipt.stampGreen else Receipt.ink)
                background = dashed()
                minHeight = px(Design.TOUCH_MIN)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener { pendingCategory = n; typed = StringBuilder(); render() }
            })
        }
    }

    private fun amountPad(roll: LinearLayout): View {
        val cat = pendingCategory!!
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(line("HOW MUCH OF IT WAS ${label(cat).uppercase()}?",
            10.5f, Receipt.inkSoft, tracking = 0.12f, topPad = 12))
        val display = line(if (typed.isEmpty()) "0" else typed.toString(),
            30f, Receipt.ink, bold = true)
        container.addView(display)

        val pad = GridLayout(this).apply { columnCount = 3; setPadding(0, px(6), 0, 0) }
        // "ALL" fills the remainder, because the last part is always the rest.
        for (k in listOf("1","2","3","4","5","6","7","8","9","<","0","ALL")) {
            pad.addView(TextView(this).apply {
                text = k
                textSize = if (k.length > 1) 13f else 18f
                typeface = if (k == "ALL") Receipt.monoBold else Receipt.mono
                gravity = Gravity.CENTER
                setTextColor(if (k == "ALL") Receipt.stampAmber else Receipt.ink)
                background = dashed()
                minHeight = px(Design.TOUCH_MIN)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener {
                    when (k) {
                        "<" -> if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1)
                        "ALL" -> { typed = StringBuilder((remainder() / 100).toString()) }
                        else -> if (typed.length < 8) typed.append(k)
                    }
                    display.text = if (typed.isEmpty()) "0" else typed.toString()
                }
            })
        }
        container.addView(pad)

        container.addView(bigButton("ADD PART", Receipt.ink) {
            val rupees = typed.toString().toLongOrNull() ?: 0L
            val minor = rupees * 100
            // Never let a part exceed what is left — that would invent money.
            if (minor in 1..remainder()) {
                parts.add(PaisaDb.Split(cat, minor))
                pendingCategory = null
                typed = StringBuilder()
                render()
            }
        })
        container.addView(quiet("BACK") { pendingCategory = null; render() })
        return container
    }

    // ---- chrome ------------------------------------------------------------

    private fun dashed() = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(px(1), Receipt.inkFaint, px(4).toFloat(), px(3).toFloat())
    }

    private fun bigButton(t: String, colour: Int, onTap: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        typeface = Receipt.monoBold
        gravity = Gravity.CENTER
        setTextColor(colour)
        background = dashed()
        minHeight = px(Design.TOUCH_MIN)
        setPadding(px(12), px(14), px(12), px(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            topMargin = px(10)
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
        setPadding(0, px(12), 0, px(6))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onTap() }
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
            addView(TornEdge(this@SplitActivity, pointingDown = false))
            addView(body)
            addView(TornEdge(this@SplitActivity, pointingDown = true))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        return ScrollView(this).apply {
            setBackgroundColor(Receipt.paperEdge)
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
