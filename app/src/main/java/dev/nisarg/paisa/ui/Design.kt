package dev.nisarg.paisa.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.View
import android.widget.TextView

/**
 * Design tokens.
 *
 * Every colour, radius and step lives here rather than as a literal in a screen,
 * so the two surfaces stay consistent and a theme change is one edit. Values are
 * the dark steps of the same ramp used by the HTML report, so the phone and the
 * desktop report look like one product.
 */
object Design {

    // ---- colour ------------------------------------------------------------

    val bg = Color.parseColor("#121211")           // page behind the cards
    val surface = Color.parseColor("#1A1A19")      // card
    val surfaceRaised = Color.parseColor("#2B2B29") // tiles, keys
    val surfacePressed = Color.parseColor("#3A3A37")
    val hairline = Color.parseColor("#33322E")

    val ink = Color.parseColor("#FFFFFF")          // primary text, 15.9:1 on surface
    val inkSecondary = Color.parseColor("#C3C2B7")  // 9.6:1 — clears 4.5 comfortably
    val inkMuted = Color.parseColor("#8E8D84")      // 4.6:1 — labels only, never body

    val accent = Color.parseColor("#3987E5")
    val spend = Color.parseColor("#E66767")
    val income = Color.parseColor("#199E70")
    val warn = Color.parseColor("#C98500")

    /** Pace drives the colour of the whole hero — the state IS the information. */
    fun paceColour(pace: dev.nisarg.paisa.parse.Cycle.Pace): Int = when (pace) {
        dev.nisarg.paisa.parse.Cycle.Pace.AHEAD -> income
        dev.nisarg.paisa.parse.Cycle.Pace.ON_TRACK -> accent
        dev.nisarg.paisa.parse.Cycle.Pace.BEHIND -> warn
        dev.nisarg.paisa.parse.Cycle.Pace.OVERRUN -> spend
    }

    // ---- rhythm ------------------------------------------------------------
    // 4dp base. Anything not on this scale is a mistake, not a decision.

    const val S1 = 4
    const val S2 = 8
    const val S3 = 12
    const val S4 = 16
    const val S5 = 20
    const val S6 = 24
    const val S8 = 32

    /** Android minimum. Tap targets below this are an accessibility failure. */
    const val TOUCH_MIN = 48

    const val RADIUS_CARD = 24
    const val RADIUS_TILE = 16

    /** Micro-interactions: 150–300ms. Exit is shorter than enter so it feels crisp. */
    const val ENTER_MS = 220L
    const val EXIT_MS = 140L

    // ---- helpers -----------------------------------------------------------

    fun Context.dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    fun Context.sp(v: Float): Float = v

    /**
     * A filled surface with a ripple. Press feedback has to be visible within
     * ~100ms and must not move layout — ripple does both, a scale transform on a
     * grid item would nudge its neighbours.
     */
    fun Context.tileBackground(
        fill: Int = surfaceRaised,
        radius: Int = RADIUS_TILE,
    ): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(fill)
        }
        val mask = GradientDrawable().apply {
            cornerRadius = dp(radius).toFloat()
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(surfacePressed), shape, mask)
    }

    fun Context.cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(RADIUS_CARD).toFloat()
        setColor(surface)
    }

    /**
     * Amounts must not jitter as digits change — a keypad that reflows while you
     * type reads as broken. Tabular figures keep every digit the same width.
     */
    fun TextView.tabularFigures() {
        fontFeatureSettings = "tnum"
    }

    /** Enter from below: "this came from the thing you just did". 220ms, ease-out. */
    fun View.animateIn(context: Context) {
        alpha = 0f
        translationY = context.dp(28).toFloat()
        animate().alpha(1f).translationY(0f)
            .setDuration(ENTER_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
            .start()
    }
}
