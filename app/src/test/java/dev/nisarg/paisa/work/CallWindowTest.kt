package dev.nisarg.paisa.work

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The call is allowed to ring in the evening and at no other time.
 *
 * This exists because of a real one: a payment at 00:30 woke the device, the
 * 9pm alarm had been sitting undelivered since the phone went to sleep, and the
 * app rang at one in the morning. The alarm layer was fixed too, but it cannot
 * *promise* punctuality — so the guarantee lives in a plain `if`, and this pins
 * it.
 */
class CallWindowTest {

    /** Local wall-clock time today at the given hour. Same timezone both sides. */
    private fun atHour(hour: Int): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 30)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun `rings at nine in the evening`() =
        assertTrue(DailySummary.withinCallingHours(atHour(21)))

    @Test fun `rings at the top of the last allowed hour`() =
        assertTrue(DailySummary.withinCallingHours(atHour(DailySummary.LATEST_HOUR)))

    @Test fun `does not ring at one in the morning`() =
        assertFalse(DailySummary.withinCallingHours(atHour(1)))

    /** The exact shape of the bug: delivered just after midnight. */
    @Test fun `does not ring just after midnight`() =
        assertFalse(DailySummary.withinCallingHours(atHour(0)))

    @Test fun `does not ring during the working day`() {
        for (h in 0 until DailySummary.HOUR) {
            assertFalse("hour $h should be silent", DailySummary.withinCallingHours(atHour(h)))
        }
    }

    @Test fun `the window is the evening, not the small hours`() {
        assertTrue(DailySummary.HOUR in 18..22)
        assertTrue(DailySummary.LATEST_HOUR in DailySummary.HOUR..23)
    }
}
