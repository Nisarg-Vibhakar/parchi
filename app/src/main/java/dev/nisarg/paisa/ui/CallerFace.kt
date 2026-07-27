package dev.nisarg.paisa.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.nisarg.paisa.work.Caller

/**
 * The caller's photo.
 *
 * Drawn rather than shipped: a face whose mouth, eyes and brows are computed
 * from the mood, so there are no image assets, nothing to go soft on a large
 * display, and the expression is a function of what you actually spent rather
 * than a picture someone chose.
 *
 * It is deliberately crude. A carefully rendered face would read as a real
 * contact photo, and the whole point is that this is obviously a joke.
 */
class CallerFace(context: Context, private val mood: Caller.Mood) : View(context) {

    private val d = context.resources.displayMetrics.density

    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Receipt.paper
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * d
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Receipt.paper }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint()
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        alpha = 90
    }

    private fun tint() = when (mood) {
        Caller.Mood.JOY -> Receipt.stampGreen
        Caller.Mood.CALM -> Receipt.inkSoft
        Caller.Mood.CONCERN -> Receipt.stampAmber
        Caller.Mood.ALARM -> Receipt.stampRed
        Caller.Mood.DOOM -> Receipt.stampRed
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = (108 * d).toInt()
        setMeasuredDimension(size, size)
    }

    /** 0..1, driven by the activity so the rings actually pulse while ringing. */
    var pulse: Float = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 10 * d

        // Two rings expanding outward and fading, the way a call screen does it.
        for (i in 0..1) {
            val p = ((pulse + i * 0.5f) % 1f)
            ring.alpha = ((1f - p) * 110).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, r + 4 * d + p * 14 * d, ring)
        }
        ring.alpha = 90
        canvas.drawCircle(cx, cy, r, disc)

        val eyeY = cy - r * 0.18f
        val eyeDx = r * 0.34f
        val eyeR = 4.6f * d

        when (mood) {
            // Eyes closed and curved: the only genuinely happy face here.
            Caller.Mood.JOY -> {
                arc(canvas, cx - eyeDx, eyeY, eyeR * 1.5f, up = true)
                arc(canvas, cx + eyeDx, eyeY, eyeR * 1.5f, up = true)
            }
            // Wide open, because being called by the tax department does that.
            Caller.Mood.ALARM, Caller.Mood.DOOM -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR * 1.5f, fill)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR * 1.5f, fill)
                brow(canvas, cx - eyeDx, eyeY - r * 0.30f, r * 0.22f, angry = true)
                brow(canvas, cx + eyeDx, eyeY - r * 0.30f, r * 0.22f, angry = true, mirror = true)
            }
            else -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, fill)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, fill)
            }
        }

        // The mouth carries the mood: smile through flat to a deepening frown.
        val mouthY = cy + r * 0.34f
        val mouthW = r * 0.62f
        val curve = when (mood) {
            Caller.Mood.JOY -> r * 0.34f
            Caller.Mood.CALM -> r * 0.06f
            Caller.Mood.CONCERN -> -r * 0.16f
            Caller.Mood.ALARM -> -r * 0.30f
            Caller.Mood.DOOM -> -r * 0.44f
        }
        val mouth = Path().apply {
            moveTo(cx - mouthW, mouthY)
            quadTo(cx, mouthY + curve * 2, cx + mouthW, mouthY)
        }
        canvas.drawPath(mouth, ink)

        // One bead of sweat once things are properly bad.
        if (mood == Caller.Mood.DOOM) {
            canvas.drawCircle(cx + r * 0.62f, cy - r * 0.34f, 4.5f * d, fill)
        }
    }

    private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, up: Boolean) {
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, if (up) 200f else 20f, 140f, false, ink)
    }

    private fun brow(
        canvas: Canvas, cx: Float, cy: Float, len: Float,
        angry: Boolean, mirror: Boolean = false,
    ) {
        val dy = if (angry) len * 0.45f else 0f
        val from = if (mirror) cx + len else cx - len
        val to = if (mirror) cx - len else cx + len
        canvas.drawLine(from, cy - dy, to, cy + dy, ink)
    }
}
