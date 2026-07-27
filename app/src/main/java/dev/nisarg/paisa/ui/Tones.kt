package dev.nisarg.paisa.ui

import dev.nisarg.paisa.work.Caller
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The fallback ringtones, generated rather than played from a file.
 *
 * The real recordings in res/raw are what normally rings; these exist for when
 * one will not open — a missing resource, a codec the device dislikes. Synthesis
 * needs no files and cannot fail on a decoder, so it is the one layer that is
 * always available, and it still gives each mood its own melody rather than
 * dropping everyone onto the same stock beep.
 *
 * The motifs are deliberately cheap and a bit rubbish — square-ish tones with a
 * fast decay, like a mid-2000s feature phone. A polished orchestral sting would
 * be worse. This should sound like a phone that is about to tell you off.
 */
object Tones {

    const val SAMPLE_RATE = 44_100

    /** One note: a frequency in hertz, a length, and the silence that follows. */
    data class Note(val hz: Double, val ms: Int, val gapMs: Int = 0)

    /**
     * JOY — a bright major arpeggio that resolves upward. The only motif here
     * that goes anywhere nice.
     */
    private val JOY = listOf(
        Note(523.25, 90, 20),   // C5
        Note(659.25, 90, 20),   // E5
        Note(783.99, 90, 20),   // G5
        Note(1046.50, 200, 900), // C6, held, then a long pause
    )

    /** CALM — the classic two-note double ring. Neutral to the point of dull. */
    private val CALM = listOf(
        Note(587.33, 140, 60),  // D5
        Note(493.88, 140, 700), // B4
    )

    /**
     * CONCERN — a minor third that repeats slightly too often. Not alarming,
     * just persistently not-quite-right, like someone clearing their throat.
     */
    private val CONCERN = listOf(
        Note(440.00, 130, 40),  // A4
        Note(523.25, 130, 40),  // C5
        Note(440.00, 130, 500),
    )

    /**
     * ALARM — a two-tone siren. Rapid, high, and built from the interval every
     * emergency vehicle uses, because it works.
     */
    private val ALARM = listOf(
        Note(880.00, 110, 10),  // A5
        Note(1174.66, 110, 10), // D6
        Note(880.00, 110, 10),
        Note(1174.66, 110, 300),
    )

    /**
     * DOOM — a low, slow descent. Minor, unhurried, and it does not resolve.
     * It is not trying to alert you; it is announcing an outcome.
     */
    private val DOOM = listOf(
        Note(146.83, 380, 60),  // D3
        Note(138.59, 380, 60),  // C#3
        Note(110.00, 620, 700), // A2, held
    )

    fun motifFor(mood: Caller.Mood): List<Note> = when (mood) {
        Caller.Mood.JOY -> JOY
        Caller.Mood.CALM -> CALM
        Caller.Mood.CONCERN -> CONCERN
        Caller.Mood.ALARM -> ALARM
        Caller.Mood.DOOM -> DOOM
    }

    /** Total loop length, so a caller can reason about the pattern. */
    fun durationMs(mood: Caller.Mood): Int =
        motifFor(mood).sumOf { it.ms + it.gapMs }

    /**
     * Renders a motif to 16-bit mono PCM.
     *
     * A little odd-harmonic shaping gives it the thin, plasticky character of a
     * cheap phone speaker — a pure sine sounds like a hearing test, which is
     * unsettling in the wrong way. Each note gets an exponential decay and a
     * short fade-in, because an abruptly started sample clicks.
     */
    fun render(mood: Caller.Mood): ShortArray {
        val motif = motifFor(mood)
        val total = motif.sumOf { samples(it.ms) + samples(it.gapMs) }
        val out = ShortArray(total)
        var at = 0

        for (note in motif) {
            val n = samples(note.ms)
            for (i in 0 until n) {
                val t = i.toDouble() / SAMPLE_RATE
                val phase = 2 * PI * note.hz * t

                // Fundamental plus a quieter third and fifth harmonic: square-ish
                // without the harshness of a true square wave.
                var v = sin(phase) + 0.28 * sin(3 * phase) + 0.12 * sin(5 * phase)
                v /= 1.4

                val decay = exp(-3.2 * i.toDouble() / n)
                val fadeIn = (i / (SAMPLE_RATE * 0.004)).coerceAtMost(1.0)
                val amp = 0.42 * decay * fadeIn

                out[at + i] = (v * amp * Short.MAX_VALUE).toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
            at += n + samples(note.gapMs)   // the gap stays zeroed: silence
        }
        return out
    }

    private fun samples(ms: Int) = ms * SAMPLE_RATE / 1000
}
