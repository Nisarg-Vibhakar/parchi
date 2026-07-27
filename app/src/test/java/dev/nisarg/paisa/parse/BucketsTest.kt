package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.Buckets.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BucketsTest {

    private fun cycle(vararg pairs: Pair<String, Long>) =
        pairs.toMap().mapValues { it.value * 100 }

    @Test fun `typical is the median of completed cycles`() {
        val b = Buckets.build(
            listOf(cycle("FOOD" to 4000), cycle("FOOD" to 5000), cycle("FOOD" to 4500)),
            cycle("FOOD" to 2000),
        ).single()
        assertEquals(450000L, b.typicalMinor)
        assertEquals(200000L, b.spentMinor)
    }

    /**
     * The reason for a median. One bike purchase would drag a mean upward
     * permanently and raise the bar to a level that can never be exceeded —
     * which is the same as having no bar at all.
     */
    @Test fun `one exceptional cycle does not move the baseline`() {
        val b = Buckets.build(
            listOf(cycle("SHOPPING" to 3000), cycle("SHOPPING" to 90000),
                   cycle("SHOPPING" to 3500), cycle("SHOPPING" to 3200)),
            cycle("SHOPPING" to 3100),
        ).single()
        assertTrue("median should stay near the ordinary cycles, was ${b.typicalMinor}",
            b.typicalMinor in 320000L..350000L)
    }

    @Test fun `a category absent from a cycle counts as a real zero`() {
        val b = Buckets.build(
            listOf(cycle("FUEL" to 3000), cycle(), cycle("FUEL" to 3000)),
            cycle("FUEL" to 1000),
        ).first { it.category == "FUEL" }
        assertEquals(300000L, b.typicalMinor)   // median of 3000, 0, 3000
    }

    @Test fun `no baseline below three cycles`() {
        val b = Buckets.build(listOf(cycle("FOOD" to 4000), cycle("FOOD" to 4000)), cycle())
        assertEquals(0, b.size)
    }

    // ---- state, judged against how far through the cycle we are -------------

    private fun bucket(typical: Long, spent: Long) =
        Buckets.Bucket("FOOD", typical * 100, spent * 100, 4)

    @Test fun `spending in step with the cycle is normal`() =
        assertEquals(State.NORMAL, bucket(4000, 2000).state(0.5))

    /**
     * The point of comparing against elapsed time: a fifth of the usual amount on
     * day 3 is fine, and calling it "over" would make the signal worthless.
     */
    @Test fun `a fifth spent on day three is not an alarm`() =
        assertEquals(State.NORMAL, bucket(4000, 800).state(0.1))

    @Test fun `well ahead of the clock is running hot`() =
        assertEquals(State.RUNNING_HOT, bucket(4000, 3200).state(0.3))

    @Test fun `past the usual total is over regardless of the clock`() =
        assertEquals(State.OVER, bucket(4000, 4500).state(0.2))

    @Test fun `well behind the clock is quiet`() =
        assertEquals(State.QUIET, bucket(4000, 400).state(0.6))

    @Test fun `a category with no history reports no baseline`() =
        assertEquals(State.NO_BASELINE, bucket(0, 500).state(0.5))

    // ---- median ------------------------------------------------------------

    @Test fun `median of an odd count`() = assertEquals(5L, Buckets.median(listOf(1, 5, 9)))

    @Test fun `median of an even count averages the middle`() =
        assertEquals(4L, Buckets.median(listOf(2, 3, 5, 9)))

    @Test fun `median of nothing is zero`() = assertEquals(0L, Buckets.median(emptyList()))
}
