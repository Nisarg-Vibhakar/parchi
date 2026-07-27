package dev.nisarg.paisa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.nisarg.paisa.parse.Cycle

/**
 * The burn bar.
 *
 * Two facts on one line: how much of the money is gone (the fill), and how much
 * *should* be gone by today (the marker). The gap between them is the entire
 * story of the cycle, readable without reading a number — which is the point,
 * because this gets looked at for about a second at a time.
 *
 * Deliberately not a pie or a donut. Those answer "what is the split"; this
 * answers "am I going to make it", which is the question actually being asked.
 */
class BurnBar(context: Context) : View(context) {

    private var fill = 0.0          // fraction of budget spent
    private var marker = 0.0        // fraction of the cycle elapsed
    private var tint = Design.accent

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.surfaceRaised }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Design.ink }

    fun set(state: Cycle.State) {
        // Clamp the fill but not the meaning: an overspend still reads as full,
        // and the colour is what says it went past the end.
        fill = state.spendFraction.coerceIn(0.0, 1.0)
        marker = state.timeFraction.coerceIn(0.0, 1.0)
        tint = Design.paceColour(state.pace)
        bar.color = tint
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = (18 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val r = 5 * d
        val barTop = 3 * d
        val barBottom = height - 3 * d

        canvas.drawRoundRect(RectF(0f, barTop, width.toFloat(), barBottom), r, r, track)

        val fillWidth = (width * fill).toFloat()
        if (fillWidth > 0f) {
            canvas.drawRoundRect(
                RectF(0f, barTop, fillWidth.coerceAtLeast(2 * d), barBottom), r, r, bar
            )
        }

        // Where a straight-line spender would be today. Drawn full height and in
        // ink so it reads as a reference line, never as more data.
        val x = (width * marker).toFloat().coerceIn(1.5f * d, width - 1.5f * d)
        canvas.drawRoundRect(
            RectF(x - 1.5f * d, 0f, x + 1.5f * d, height.toFloat()), 1.5f * d, 1.5f * d, pin
        )
    }
}
