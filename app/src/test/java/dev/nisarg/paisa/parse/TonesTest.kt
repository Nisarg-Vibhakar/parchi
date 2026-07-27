package dev.nisarg.paisa.parse

import dev.nisarg.paisa.ui.Tones
import dev.nisarg.paisa.work.Caller.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ringtones are generated, so they can be tested as maths rather than
 * listened to — which also means a bad edit cannot silently ship a click, a
 * clipped sample, or a tone at the wrong pitch.
 */
class TonesTest {

    @Test fun `every mood has a motif`() {
        for (m in Mood.entries) assertTrue(Tones.motifFor(m).isNotEmpty())
    }

    /** Each motif must end in silence or the loop runs into itself. */
    @Test fun `every motif ends with a gap`() {
        for (m in Mood.entries) {
            assertTrue("$m needs a trailing gap", Tones.motifFor(m).last().gapMs >= 200)
        }
    }

    /** A ring nobody can wait through is a ring nobody answers. */
    @Test fun `loops are between half a second and three seconds`() {
        for (m in Mood.entries) {
            val d = Tones.durationMs(m)
            assertTrue("$m loop is ${d}ms", d in 500..3000)
        }
    }

    @Test fun `doom is slower than alarm`() =
        assertTrue(Tones.durationMs(Mood.DOOM) > Tones.durationMs(Mood.ALARM))

    /** Doom sits low and ominous; alarm sits high and urgent. */
    @Test fun `doom is pitched below alarm`() {
        val doom = Tones.motifFor(Mood.DOOM).minOf { it.hz }
        val alarm = Tones.motifFor(Mood.ALARM).minOf { it.hz }
        assertTrue("doom $doom should be well below alarm $alarm", doom < alarm / 3)
    }

    /** Joy resolves upward — the only motif here that goes somewhere nice. */
    @Test fun `joy rises`() {
        val notes = Tones.motifFor(Mood.JOY).map { it.hz }
        assertEquals(notes.sorted(), notes)
    }

    /** Doom descends and never resolves. */
    @Test fun `doom falls`() {
        val notes = Tones.motifFor(Mood.DOOM).map { it.hz }
        assertEquals(notes.sortedDescending(), notes)
    }

    // ---- the rendered audio ------------------------------------------------

    @Test fun `render produces the right number of samples`() {
        for (m in Mood.entries) {
            val expected = Tones.durationMs(m) * Tones.SAMPLE_RATE / 1000
            assertTrue("$m length off", kotlin.math.abs(Tones.render(m).size - expected) <= 10)
        }
    }

    /** A sample that starts at full amplitude clicks audibly. */
    @Test fun `every render fades in rather than clicking`() {
        for (m in Mood.entries) {
            val pcm = Tones.render(m)
            assertEquals("$m starts with a click", 0, pcm.first().toInt())
        }
    }

    /** Clipping would turn a tone into a buzz. */
    @Test fun `nothing clips`() {
        for (m in Mood.entries) {
            val peak = Tones.render(m).maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue("$m peaks at $peak", peak < Short.MAX_VALUE * 0.95)
            assertTrue("$m is inaudibly quiet at $peak", peak > Short.MAX_VALUE * 0.15)
        }
    }

    /** The trailing gap must bereal silence, not a fading tail. */
    @Test fun `the loop gap is actually silent`() {
        for (m in Mood.entries) {
            val pcm = Tones.render(m)
            assertEquals("$m ends noisy", 0, pcm.last().toInt())
        }
    }

    /** Every mood must have a distinct recording, or two moods sound alike. */
    @Test fun `every mood maps to its own clip`() {
        val ids = Mood.entries.map { dev.nisarg.paisa.ui.Ringer.clipFor(it) }
        assertEquals("clips must be distinct", ids.size, ids.toSet().size)
        for (id in ids) assertTrue("missing raw resource", id != 0)
    }
}
