package dev.nisarg.paisa.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.nisarg.paisa.data.PaisaDb
import dev.nisarg.paisa.parse.IdentityMatcher
import dev.nisarg.paisa.parse.Money
import dev.nisarg.paisa.ui.Receipt.TornEdge
import dev.nisarg.paisa.ui.Receipt.leaderRow
import dev.nisarg.paisa.ui.Receipt.line
import dev.nisarg.paisa.ui.Receipt.px
import dev.nisarg.paisa.ui.Receipt.rule

/**
 * Reviewing suggested identity merges.
 *
 * One at a time, with the evidence and both sides' spending shown, because a
 * wrong merge is invisible afterwards — the figures simply become wrong and
 * nothing indicates why. Nothing here happens automatically, and every merge is
 * reversible.
 */
class MergeActivity : Activity() {

    private val db by lazy { PaisaDb.get(this) }
    private var queue: List<IdentityMatcher.Candidate> = emptyList()
    private var index = 0
    private var merged = 0
    private var skipped = 0

    private var scanning = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
        // Comparing every payee against every other is quadratic, and this phone
        // has over a thousand of them. Nowhere near the main thread.
        Thread {
            val already = db.merges()
            val found = IdentityMatcher.suggest(db.allPayees())
                .filter { it.a !in already && it.b !in already }
            runOnUiThread {
                queue = found
                scanning = false
                render()
            }
        }.start()
    }

    private fun money(m: Long) = Money.format(m).removePrefix("₹")

    private fun render() {
        if (scanning) { setContentView(page(scanningRoll())); return }
        val c = queue.getOrNull(index)
        setContentView(page(if (c == null) doneRoll() else reviewRoll(c)))
    }

    private fun scanningRoll(): LinearLayout {
        val roll = roll()
        roll.addView(line("S C A N N I N G", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.28f, topPad = 14))
        roll.addView(rule("═"))
        roll.addView(line("Comparing every payee against every other.", 11.5f,
            Receipt.inkSoft, topPad = 8))
        roll.addView(line("A moment.", 11.5f, Receipt.inkFaint))
        roll.addView(quiet("CANCEL") { finish() })
        return roll
    }

    private fun reviewRoll(c: IdentityMatcher.Candidate): LinearLayout {
        val roll = roll()
        roll.addView(line("S A M E   P E R S O N ?", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.24f, topPad = 12))
        roll.addView(line("${index + 1} OF ${queue.size} SUGGESTIONS", 10.5f, Receipt.inkSoft,
            centre = true, tracking = 0.2f, topPad = 3))
        roll.addView(rule("═"))

        // Both sides with their real numbers: a merge is easier to judge when you
        // can see what each identity has actually done.
        for (payee in listOf(c.a, c.b)) {
            val payments = db.paymentsFor(payee, 60)
            roll.addView(line(db.displayName(payee).uppercase(), 14f, Receipt.ink, bold = true,
                topPad = 8))
            if (db.displayName(payee) != payee) {
                roll.addView(line(payee, 10f, Receipt.inkFaint))
            }
            roll.addView(leaderRow(
                if (payments.size == 1) "1 payment" else "${payments.size} payments",
                money(payments.sumOf { it.amountMinor }), Receipt.inkSoft, Receipt.inkSoft))
            if (payee == c.a) roll.addView(line("        ↓ merge into ↓", 11f, Receipt.stampAmber))
        }

        roll.addView(rule("─"))
        roll.addView(line("WHY THIS WAS SUGGESTED", 10.5f, Receipt.inkSoft, tracking = 0.14f))
        roll.addView(line(c.reason, 11f, Receipt.inkFaint, topPad = 4))

        roll.addView(button("MERGE — SAME PERSON", Receipt.stampGreen) {
            // The second is kept as canonical: a readable name beats a handle.
            db.mergePayees(c.a, c.b)
            merged++; index++; render()
        })
        roll.addView(button("KEEP SEPARATE", Receipt.ink) { skipped++; index++; render() })
        roll.addView(quiet("DONE FOR NOW") { finish() })
        return roll
    }

    private fun doneRoll(): LinearLayout {
        val roll = roll()
        roll.addView(line("M E R G E S", 13f, Receipt.ink, bold = true,
            centre = true, tracking = 0.28f, topPad = 12))
        roll.addView(rule("═"))
        roll.addView(leaderRow("MERGED", "$merged", Receipt.ink, Receipt.ink, bold = true))
        roll.addView(leaderRow("KEPT SEPARATE", "$skipped", Receipt.inkSoft, Receipt.inkSoft))
        roll.addView(line(
            if (queue.isEmpty()) "Nothing looked like a duplicate."
            else "Every merge is reversible — nothing was\\nthrown away.",
            11.5f, Receipt.inkSoft, topPad = 14))
        roll.addView(quiet("CLOSE") { finish() })
        return roll
    }

    // ---- chrome ------------------------------------------------------------

    private fun dashed() = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(px(1), Receipt.inkFaint, px(4).toFloat(), px(3).toFloat())
    }

    private fun button(t: String, colour: Int, onTap: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 12.5f
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
        setPadding(0, px(14), 0, px(8))
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
            addView(TornEdge(this@MergeActivity, pointingDown = false))
            addView(body)
            addView(TornEdge(this@MergeActivity, pointingDown = true))
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
