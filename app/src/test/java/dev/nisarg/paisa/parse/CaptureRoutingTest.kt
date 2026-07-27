package dev.nisarg.paisa.parse

import dev.nisarg.paisa.ui.CaptureRouting
import dev.nisarg.paisa.ui.CaptureRouting.Slip
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The back-tap gesture and the app icon fire the identical launcher intent, so
 * the slip that appears is decided entirely by this table. It used to be decided
 * by guessing at lock state, which got it wrong in the one case the whole app
 * exists for — see the regression test at the bottom.
 */
class CaptureRoutingTest {

    @Test fun `an uncategorised spend is the confirm slip`() {
        assertEquals(Slip.CONFIRM, CaptureRouting.decide(hasPending = true))
    }

    /**
     * Nothing to confirm: today's figure, and the keypad one tap away on it. Not
     * the keypad itself — arriving on a live number field puts the cursor before
     * the answer, and the figure is what you opened it to see.
     */
    @Test fun `nothing pending is the summary`() {
        assertEquals(Slip.SUMMARY, CaptureRouting.decide(hasPending = false))
    }

    /**
     * Regression, fd2649b. The routing guessed the gesture from lock state and
     * screen-on, so a phone that was unlocked and awake — which is exactly what
     * a phone is the moment you finish paying — was read as an icon tap and sent
     * to the full receipt. The slip never appeared.
     *
     * There is no honest signal separating a gesture from an icon tap, so
     * nothing outside this table may influence it. Whatever the phone is doing,
     * a launcher intent produces a slip.
     */
    @Test fun `the whole table, exhaustively`() {
        val expected = mapOf(
            true to Slip.CONFIRM,
            false to Slip.SUMMARY,
        )
        for ((hasPending, slip) in expected) {
            assertEquals("hasPending=$hasPending", slip, CaptureRouting.decide(hasPending))
        }
        assertEquals("every input is covered", 2, expected.size)
    }
}
