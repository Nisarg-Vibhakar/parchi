package dev.nisarg.paisa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The receipt.
 *
 * A committed aesthetic rather than a neutral one. Expenses *are* receipts, and
 * a receipt's typographic system is genuinely the best way to show money:
 * monospace so digits align in a column, dot leaders so the eye travels from
 * label to figure without a table, ruled sections instead of floating cards.
 *
 * Nothing here is rounded and nothing is a card. That is the point — rounded
 * dark cards are the default look of every finance app, and defaulting is what
 * makes an interface forgettable.
 */
object Receipt {

    // Warm paper rather than neutral grey — a receipt is never blue-black.
    val paper = Color.parseColor("#13120F")
    val paperEdge = Color.parseColor("#0B0B09")
    val ink = Color.parseColor("#EDE8DA")        // 14.6:1 on paper
    val inkSoft = Color.parseColor("#A9A395")    // 7.1:1
    val inkFaint = Color.parseColor("#6E6A5E")   // rules and leaders only
    val stampRed = Color.parseColor("#D4574A")
    val stampGreen = Color.parseColor("#5FA772")
    val stampAmber = Color.parseColor("#C9964A")

    val mono: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    val monoBold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

    fun Context.px(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---- type --------------------------------------------------------------

    fun Context.line(
        text: String,
        size: Float = 13f,
        colour: Int = ink,
        bold: Boolean = false,
        centre: Boolean = false,
        tracking: Float = 0f,
        topPad: Int = 0,
    ) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = if (bold) monoBold else mono
        setTextColor(colour)
        letterSpacing = tracking
        if (centre) gravity = Gravity.CENTER
        setPadding(0, px(topPad), 0, 0)
        fontFeatureSettings = "tnum"
    }

    /**
     * A ruled divider. Drawn as a repeated character rather than a hairline
     * because a receipt's rules are printed, not vector — and it keeps the whole
     * screen on one typographic grid.
     */
    fun Context.rule(char: String = "─", colour: Int = inkFaint) = TextView(this).apply {
        text = char.repeat(200)
        textSize = 12f
        typeface = mono
        setTextColor(colour)
        maxLines = 1
        setPadding(0, px(10), 0, px(10))
    }

    /**
     * `FOOD ................ 7,279.13`
     *
     * Label and figure are separate views so the figure never wraps; the leader
     * fills whatever is between them and is clipped, exactly like print.
     */
    fun Context.leaderRow(
        label: String,
        value: String,
        labelColour: Int = ink,
        valueColour: Int = ink,
        bold: Boolean = false,
        onTap: (() -> Unit)? = null,
    ) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(5), 0, px(5))
        addView(line(label, 13f, labelColour, bold))
        addView(TextView(this@leaderRow).apply {
            text = ".".repeat(200)
            textSize = 13f
            typeface = mono
            setTextColor(inkFaint)
            maxLines = 1
            setPadding(px(6), 0, px(6), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        addView(line(value, 13f, valueColour, bold))
        if (onTap != null) {
            minimumHeight = px(Design.TOUCH_MIN)
            setOnClickListener { onTap() }
        }
    }

    // ---- torn edge ---------------------------------------------------------

    /** The perforated tear at the top and bottom of the roll. */
    class TornEdge(context: Context, private val pointingDown: Boolean) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paper }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(w),
                (9 * resources.displayMetrics.density).toInt()
            )
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val tooth = 11 * d
            val path = Path()
            if (pointingDown) {
                path.moveTo(0f, 0f)
                var x = 0f
                while (x < width + tooth) {
                    path.lineTo(x + tooth / 2, height.toFloat())
                    path.lineTo(x + tooth, 0f)
                    x += tooth
                }
                path.lineTo(width.toFloat(), 0f)
            } else {
                path.moveTo(0f, height.toFloat())
                var x = 0f
                while (x < width + tooth) {
                    path.lineTo(x + tooth / 2, 0f)
                    path.lineTo(x + tooth, height.toFloat())
                    x += tooth
                }
                path.lineTo(width.toFloat(), height.toFloat())
            }
            path.close()
            canvas.drawPath(path, paint)
        }
    }

    // ---- rubber stamp ------------------------------------------------------

    /**
     * The cycle verdict as a stamp: a rotated, double-ruled box, deliberately
     * imperfect. It is the one piece of the screen that is allowed personality,
     * and it carries a real state — never decoration.
     */
    class Stamp(context: Context, text: String, colour: Int) : TextView(context) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colour
            strokeWidth = 2.5f * context.resources.displayMetrics.density
        }
        private val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = colour
            strokeWidth = 1f * context.resources.displayMetrics.density
        }

        init {
            this.text = text
            textSize = 15f
            typeface = monoBold
            setTextColor(colour)
            letterSpacing = 0.16f
            val d = context.resources.displayMetrics.density
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
            rotation = -4.5f          // stamped by hand, not printed
            alpha = 0.92f
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            canvas.drawRect(1.5f * d, 1.5f * d, width - 1.5f * d, height - 1.5f * d, border)
            canvas.drawRect(5f * d, 5f * d, width - 5f * d, height - 5f * d, inner)
            super.onDraw(canvas)
        }
    }

    // ---- the burn meter ----------------------------------------------------

    /**
     * Spend against the cycle, drawn as printed blocks rather than a smooth bar,
     * so it belongs to the same world as the type. The gap marker is where a
     * straight-line spender would stand today.
     */
    class BlockMeter(context: Context) : View(context) {
        private var filled = 0.0
        private var marker = 0.0
        private var tint = stampGreen

        private val on = Paint(Paint.ANTI_ALIAS_FLAG)
        private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkFaint; alpha = 90 }
        private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }

        fun set(spendFraction: Double, timeFraction: Double, colour: Int) {
            filled = spendFraction.coerceIn(0.0, 1.0)
            marker = timeFraction.coerceIn(0.0, 1.0)
            tint = colour
            on.color = colour
            invalidate()
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(
                MeasureSpec.getSize(w),
                (26 * resources.displayMetrics.density).toInt()
            )
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val blocks = 28
            val gap = 2.5f * d
            val bw = (width - gap * (blocks - 1)) / blocks
            val lit = (blocks * filled).toInt()

            for (i in 0 until blocks) {
                val x = i * (bw + gap)
                canvas.drawRect(x, 6 * d, x + bw, height - 6 * d, if (i < lit) on else off)
            }

            val x = (width * marker).toFloat().coerceIn(1f * d, width - 2f * d)
            canvas.drawRect(x - 1f * d, 0f, x + 1f * d, height.toFloat(), pin)
        }
    }

    // ---- layout ------------------------------------------------------------

    fun Context.receiptColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(paper)
        setPadding(px(22), px(4), px(22), px(4))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
    }
}
