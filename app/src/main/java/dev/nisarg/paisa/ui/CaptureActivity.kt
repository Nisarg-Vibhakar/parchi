package dev.nisarg.paisa.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
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
import java.util.Calendar

/**
 * The back-tap slip.
 *
 * A torn-off piece of the same roll as the home screen — same paper, same
 * monospace, same rules — so the two surfaces read as one product rather than a
 * dashboard with a dialog bolted on.
 *
 *   CONFIRM — an uncategorised spend arrived recently: one tap to file it
 *   MANUAL  — nothing arrived: type the amount yourself
 *   SUMMARY — nothing pending: today's figure, then out
 *
 * MANUAL exists because of a measured failure: Google Pay posts no notification
 * for payments you make yourself, and the bank SMS that does carry them runs
 * minutes late — or below the bank's alert threshold, never arrives. A ₹1 test
 * payment stayed invisible indefinitely.
 */
class CaptureActivity : Activity() {

    private val db by lazy { PaisaDb.get(this) }
    private var typed = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        dev.nisarg.paisa.work.DailySummary.schedule(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        render()
    }

    /** singleTask re-delivers to the live instance; without this a second tap
     *  would still show the previous payment. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        typed = StringBuilder()
        render()
    }

    private fun money(minor: Long) = Money.format(minor).removePrefix("₹")

    /**
     * Both the back-tap gesture and the app icon fire the same launcher intent —
     * Motorola's gesture list offers only "Open app" — so they cannot be told
     * apart as intents. They are told apart by context instead:
     *
     *   something to confirm  -> the confirm slip, whichever way you arrived
     *   phone still locked    -> you just paid and reached for it: manual entry
     *   phone already in use  -> you tapped the icon: the full receipt
     *
     * The lock state is the honest signal. You back-tap a phone you have just
     * paid with, which is usually still locked or freshly woken; you tap an icon
     * on a phone you are already holding and using.
     */
    private fun render() {
        val window = intent?.getLongExtra("window_ms", 0L) ?: 0L
        val pending = if (window > 0) db.pendingSpend(window) else db.pendingSpend()
        if (pending != null) { show(confirmSlip(pending)); return }

        // An explicit window means we were opened deliberately for filing, not by
        // the gesture, so never redirect.
        if (window > 0L) { show(summarySlip()); return }

        val km = getSystemService(android.app.KeyguardManager::class.java)
        val cameFromGesture = km?.isKeyguardLocked == true || !isInteractiveRecently()
        if (cameFromGesture) { show(manualSlip()); return }

        startActivity(
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** True when the screen has been on long enough that the user was already using it. */
    private fun isInteractiveRecently(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return pm?.isInteractive == true
    }

    private fun show(body: LinearLayout) {
        val slip = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Receipt.paperEdge)
            addView(TornEdge(this@CaptureActivity, pointingDown = false))
            addView(body)
            addView(TornEdge(this@CaptureActivity, pointingDown = true))
            isClickable = true          // taps on the slip must not dismiss
            // Explicit wrap: without it the slip inherits the frame's height and
            // prints a long blank tail below the content.
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM    // reached one-handed; keep it in the thumb arc
            setPadding(px(10), px(10), px(10), px(10))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            addView(slip)
            setOnClickListener { finish() }
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    insets.getInsets(android.view.WindowInsets.Type.systemBars()) else null
                val bot = bars?.bottom ?: @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                v.setPadding(px(10), px(10), px(10), px(10) + bot)
                insets
            }
        })
        slip.animateIn(this)
    }

    private fun slipBody() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Receipt.paper)
        setPadding(px(20), px(6), px(20), px(10))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }

    // ---- confirm -----------------------------------------------------------

    private fun confirmSlip(p: PaisaDb.Pending): LinearLayout {
        val s = slipBody()
        s.addView(line("P A R C H I", 12f, Receipt.inkSoft, bold = true,
            centre = true, tracking = 0.3f, topPad = 8))
        s.addView(rule("═"))
        s.addView(line("YOU PAID", 10.5f, Receipt.inkSoft, tracking = 0.18f))
        s.addView(line(money(p.amountMinor), 38f, Receipt.ink, bold = true, tracking = -0.02f))
        s.addView(line(db.displayName(p.merchant).uppercase(), 13f, Receipt.inkSoft, topPad = 2))

        // Ask for a name only when it is both the first time and the payee is
        // unreadable. Prompting for a shop that already reads fine is noise, and
        // noise in a two-second interaction is what gets an app uninstalled.
        val worthNaming = p.merchant != null &&
            db.isFirstTimePayee(p.merchant) && db.looksOpaque(p.merchant)
        if (worthNaming) {
            s.addView(TextView(this).apply {
                text = "+ FIRST TIME — NAME IT WHILE YOU REMEMBER"
                textSize = 11f
                typeface = Receipt.monoBold
                gravity = Gravity.CENTER
                setTextColor(Receipt.stampAmber)
                minHeight = px(Design.TOUCH_MIN)
                setPadding(0, px(10), 0, px(2))
                setOnClickListener { askName(p.merchant!!) }
            })
        }
        s.addView(rule())
        s.addView(line("WHAT WAS IT FOR?", 10.5f, Receipt.inkSoft, tracking = 0.18f))
        s.addView(tiles(rank(p.merchant)) { c ->
            db.confirmCategory(p.parsedId, p.merchant, c.name)
            typed = StringBuilder(); render()
        })
        s.addView(quiet("NOT A SPEND — SKIP") {
            db.confirmCategory(p.parsedId, null, Categoriser.Category.OTHER.name); render()
        })
        return s
    }

    private fun askName(merchant: String) {
        val input = android.widget.EditText(this).apply {
            hint = "e.g. Shradha Motors"
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setTextColor(Receipt.ink)
            setHintTextColor(Receipt.inkFaint)
            typeface = Receipt.mono
            setPadding(px(16), px(16), px(16), px(16))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Who is this?")
            .setMessage(merchant)
            .setView(input)
            .setPositiveButton("SAVE") { _, _ ->
                db.setAlias(merchant, input.text.toString()); render()
            }
            .setNegativeButton("LATER", null)
            .show()
    }

    // ---- manual ------------------------------------------------------------

    private fun manualSlip(): LinearLayout {
        val s = slipBody()
        s.addView(line("P A R C H I", 12f, Receipt.inkSoft, bold = true,
            centre = true, tracking = 0.3f, topPad = 8))
        s.addView(rule("═"))
        s.addView(line("ENTER AMOUNT", 10.5f, Receipt.inkSoft, tracking = 0.18f))

        val display = line(if (typed.isEmpty()) "0" else typed.toString(),
            38f, Receipt.ink, bold = true, tracking = -0.02f)
        s.addView(display)
        s.addView(line("bank SMS will be merged, not duplicated", 10.5f, Receipt.inkFaint, topPad = 2))
        s.addView(rule())

        val pad = GridLayout(this).apply { columnCount = 3 }
        for (k in listOf("1","2","3","4","5","6","7","8","9","<","0","OK")) {
            pad.addView(keyCap(k) {
                when (k) {
                    "<" -> if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1)
                    "OK" -> {
                        if (typed.isNotEmpty() && typed.toString().toLong() > 0)
                            show(manualCategorySlip(typed.toString().toLong() * 100))
                        return@keyCap
                    }
                    else -> if (typed.length < 7) typed.append(k)   // ₹99,99,999 ceiling
                }
                display.text = if (typed.isEmpty()) "0" else typed.toString()
            })
        }
        s.addView(pad)
        s.addView(quiet("CANCEL") { typed = StringBuilder(); render() })
        return s
    }

    private fun manualCategorySlip(amountMinor: Long): LinearLayout {
        val s = slipBody()
        s.addView(line("P A R C H I", 12f, Receipt.inkSoft, bold = true,
            centre = true, tracking = 0.3f, topPad = 8))
        s.addView(rule("═"))
        s.addView(line("ADDING", 10.5f, Receipt.inkSoft, tracking = 0.18f))
        s.addView(line(money(amountMinor), 38f, Receipt.ink, bold = true, tracking = -0.02f))
        s.addView(rule())
        s.addView(line("WHAT WAS IT FOR?", 10.5f, Receipt.inkSoft, tracking = 0.18f))
        s.addView(tiles(defaults()) { c ->
            db.addManualSpend(amountMinor, c.name, null); typed = StringBuilder(); render()
        })
        s.addView(quiet("SAVE WITHOUT A CATEGORY") {
            db.addManualSpend(amountMinor, null, null); typed = StringBuilder(); render()
        })
        return s
    }

    // ---- summary -----------------------------------------------------------

    private fun summarySlip(): LinearLayout {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val week = startOfDay - 6L * 24 * 60 * 60 * 1000

        val s = slipBody()
        s.addView(line("P A R C H I", 12f, Receipt.inkSoft, bold = true,
            centre = true, tracking = 0.3f, topPad = 8))
        s.addView(rule("═"))
        s.addView(line("SPENT TODAY", 10.5f, Receipt.inkSoft, tracking = 0.18f))
        s.addView(line(money(db.spentSince(startOfDay)), 38f, Receipt.ink,
            bold = true, tracking = -0.02f))
        s.addView(rule("─"))
        s.addView(leaderRow("PAYMENTS", "${db.countSince(startOfDay)}", Receipt.inkSoft, Receipt.inkSoft))
        s.addView(leaderRow("THIS WEEK", money(db.spentSince(week)), Receipt.inkSoft, Receipt.inkSoft))
        s.addView(rule())
        s.addView(action("+ ADD A PAYMENT") { show(manualSlip()) })
        s.addView(quiet("SEE THE FULL RECEIPT") {
            startActivity(
                Intent(this, HomeActivity::class.java)
                    // Its own task, so the app appears in recents. The modal is
                    // excludeFromRecents by design — a two-second interaction has
                    // no business in the recents list — but anything launched from
                    // it inherits that task and disappears too.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
        })
        return s
    }

    // ---- parts -------------------------------------------------------------

    private fun rank(merchant: String?): List<Categoriser.Category> {
        val out = LinkedHashSet<Categoriser.Category>()
        Categoriser.byRule(merchant)?.let(out::add)
        db.favouriteCategories().forEach { n ->
            runCatching { Categoriser.Category.valueOf(n) }.getOrNull()?.let(out::add)
        }
        out.addAll(defaults())
        return out.take(6)
    }

    private fun defaults() = listOf(
        Categoriser.Category.FOOD, Categoriser.Category.TRANSPORT,
        Categoriser.Category.GROCERIES, Categoriser.Category.SHOPPING,
        Categoriser.Category.BILLS, Categoriser.Category.PEOPLE,
    )

    private fun tiles(cats: List<Categoriser.Category>, onPick: (Categoriser.Category) -> Unit) =
        GridLayout(this).apply {
            columnCount = 3
            setPadding(0, px(8), 0, 0)
            for (c in cats) addView(TextView(this@CaptureActivity).apply {
                text = c.label.uppercase()
                textSize = 12f
                typeface = Receipt.mono
                gravity = Gravity.CENTER
                setTextColor(Receipt.ink)
                contentDescription = "Category ${c.label}"
                // A dashed cell, not a rounded chip — same world as the rules.
                background = dashedCell()
                minHeight = px(Design.TOUCH_MIN + 8)
                layoutParams = GridLayout.LayoutParams().apply {
                    width = 0; height = WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(px(3), px(3), px(3), px(3))
                }
                setOnClickListener { onPick(c) }
            })
        }

    private fun dashedCell() = android.graphics.drawable.GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(px(1), Receipt.inkFaint, px(4).toFloat(), px(3).toFloat())
    }

    private fun keyCap(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        textSize = if (label.length > 1) 14f else 19f
        typeface = if (label == "OK") Receipt.monoBold else Receipt.mono
        gravity = Gravity.CENTER
        setTextColor(if (label == "OK") Receipt.stampGreen else Receipt.ink)
        contentDescription = when (label) {
            "<" -> "Delete last digit"; "OK" -> "Confirm amount"; else -> "Digit $label"
        }
        background = dashedCell()
        minHeight = px(Design.TOUCH_MIN + 4)
        layoutParams = GridLayout.LayoutParams().apply {
            width = 0; height = WRAP_CONTENT
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(px(3), px(3), px(3), px(3))
        }
        setOnClickListener { onTap() }
    }

    private fun action(t: String, onTap: () -> Unit) = TextView(this).apply {
        text = t
        textSize = 13f
        typeface = Receipt.monoBold
        gravity = Gravity.CENTER
        setTextColor(Receipt.ink)
        background = dashedCell()
        minHeight = px(Design.TOUCH_MIN)
        setPadding(px(12), px(14), px(12), px(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
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
        setPadding(0, px(16), 0, px(8))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        setOnClickListener { onTap() }
    }
}
