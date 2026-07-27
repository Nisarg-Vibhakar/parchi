package dev.nisarg.paisa.parse

import android.media.RingtoneManager
import dev.nisarg.paisa.ui.Ringer
import dev.nisarg.paisa.work.Caller.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of each ring, tested without a device — a phone would only report
 * that it vibrated, never whether it felt like the right mood.
 */
class RingerTest {

    private fun buzzTime(mood: Mood): Long =
        Ringer.patternFor(mood).filterIndexed { i, _ -> i % 2 == 1 }.sum()

    @Test fun `every mood has a pattern`() {
        for (m in Mood.entries) assertTrue(Ringer.patternFor(m).isNotEmpty())
    }

    /** A waveform must start with a wait, or the first buzz is clipped. */
    @Test fun `every pattern starts silent`() {
        for (m in Mood.entries) assertEquals(0L, Ringer.patternFor(m).first())
    }

    /**
     * Timings alternate silence/buzz starting with silence, so an odd length
     * means the pattern ENDS on a silence. That trailing gap is what separates
     * one ring from the next when the waveform loops — an even-length pattern
     * would buzz straight into its own repeat and drone.
     */
    @Test fun `patterns end on a silence so the loop has a gap`() {
        for (m in Mood.entries) {
            val p = Ringer.patternFor(m)
            assertEquals("$m must end on a silence", 1, p.size % 2)
            assertTrue("$m needs a real gap before repeating", p.last() >= 200L)
        }
    }

    /** A good day taps twice; a blown cycle does not let go. */
    @Test fun `worse moods buzz for longer`() {
        assertTrue(buzzTime(Mood.JOY) < buzzTime(Mood.CALM))
        assertTrue(buzzTime(Mood.CONCERN) < buzzTime(Mood.DOOM))
        assertTrue(buzzTime(Mood.JOY) < buzzTime(Mood.DOOM))
    }

    /** Alarm is rapid rather than long: many short bursts, not one drone. */
    @Test fun `alarm buzzes more often than doom`() {
        val alarmBursts = Ringer.patternFor(Mood.ALARM).size / 2
        val doomBursts = Ringer.patternFor(Mood.DOOM).size / 2
        assertTrue("alarm should be busier", alarmBursts > doomBursts)
    }

    @Test fun `no two adjacent moods feel identical`() {
        val all = Mood.entries.map { Ringer.patternFor(it).toList() }
        assertEquals("every mood should be distinct", all.size, all.toSet().size)
    }

    // ---- tone selection ----------------------------------------------------

    @Test fun `good moods use the gentler notification tone`() {
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, Ringer.toneTypeFor(Mood.JOY))
        assertEquals(RingtoneManager.TYPE_NOTIFICATION, Ringer.toneTypeFor(Mood.CALM))
    }

    /** The alarm tone is the one noise nobody has learned to sleep through. */
    @Test fun `the bad end of the scale uses the alarm tone`() {
        assertEquals(RingtoneManager.TYPE_ALARM, Ringer.toneTypeFor(Mood.ALARM))
        assertEquals(RingtoneManager.TYPE_ALARM, Ringer.toneTypeFor(Mood.DOOM))
    }

    @Test fun `concern rings like an actual phone call`() =
        assertEquals(RingtoneManager.TYPE_RINGTONE, Ringer.toneTypeFor(Mood.CONCERN))

    @Test fun `joy and doom do not sound the same`() =
        assertNotEquals(Ringer.toneTypeFor(Mood.JOY), Ringer.toneTypeFor(Mood.DOOM))
}
