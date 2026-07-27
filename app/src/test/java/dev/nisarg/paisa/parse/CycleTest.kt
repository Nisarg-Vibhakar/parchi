package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.Cycle.DAY_MS
import dev.nisarg.paisa.parse.Cycle.Pace
import org.junit.Assert.assertEquals
import org.junit.Test

class CycleTest {

    private val payday = 1_000_000_000_000L
    private val budget = 10_000_00L      // ₹10,000 for easy arithmetic

    private fun state(dayOfCycle: Int, spentRupees: Long, days: Int = 30) = Cycle.State(
        startAt = payday,
        expectedEndAt = payday + days * DAY_MS,
        budgetMinor = budget,
        spentMinor = spentRupees * 100,
        now = payday + dayOfCycle * DAY_MS,
    )

    // ---- framing -----------------------------------------------------------

    @Test fun `payday itself is day one not day zero`() =
        assertEquals(1, state(0, 0).dayNumber)

    @Test fun `day counting`() = assertEquals(11, state(10, 0).dayNumber)

    @Test fun `days left`() = assertEquals(20, state(10, 0).daysLeft)

    @Test fun `cycle length comes from the dates`() =
        assertEquals(31, state(1, 0, days = 31).totalDays)

    // ---- pace --------------------------------------------------------------

    @Test fun `spending exactly on the line is on track`() {
        // a third of the cycle gone, a third of the money gone
        assertEquals(Pace.ON_TRACK, state(10, 3333).pace)
    }

    @Test fun `spending well under the line is ahead`() =
        assertEquals(Pace.AHEAD, state(15, 3000).pace)

    @Test fun `spending over the line but not heading for a wall is behind`() {
        // day 10 of 30, ₹3,800 spent: 14% over the line, projects to ₹11,400 —
        // above budget but inside the tolerance band, so this is a nudge not an alarm
        assertEquals(Pace.BEHIND, state(10, 3800).pace)
    }

    @Test fun `behind is reachable and distinct from overrun`() {
        // guards the algebra: an earlier version made BEHIND unreachable because
        // "runway < days left" is the same test as "spent > on pace"
        assertEquals(Pace.BEHIND, state(10, 3800).pace)
        assertEquals(Pace.OVERRUN, state(10, 4600).pace)
    }

    @Test fun `burning fast enough to run dry early is an overrun`() {
        // half the cycle gone, 90% of the money gone: projects to ₹18,000
        assertEquals(Pace.OVERRUN, state(15, 9000).pace)
    }

    @Test fun `projection is the burn rate held to the end`() {
        // ₹4,000 over 10 days = ₹400/day, over 30 days = ₹12,000
        assertEquals(12000_00L, state(10, 4000).projectedMinor)
    }

    @Test fun `already overspent is an overrun`() =
        assertEquals(Pace.OVERRUN, state(20, 12000).pace)

    // ---- the numbers that change behaviour ---------------------------------

    @Test fun `allowance per remaining day`() {
        // day 10 of 30, ₹4,000 spent -> ₹6,000 over 20 days = ₹300/day
        assertEquals(300_00L, state(10, 4000).allowancePerDayMinor)
    }

    @Test fun `burn rate is per elapsed day`() {
        // ₹4,000 over 10 days
        assertEquals(400_00L, state(10, 4000).burnPerDayMinor)
    }

    @Test fun `burn rate on payday itself does not divide by zero`() =
        assertEquals(500_00L, state(0, 500).burnPerDayMinor)

    @Test fun `runway at the current burn`() {
        // ₹4,000 spent over 10 days = ₹400/day; ₹6,000 left = 15 days
        assertEquals(15, state(10, 4000).runwayDays)
    }

    @Test fun `on-pace figure is the straight line`() =
        assertEquals(5000_00L, state(15, 0).onPaceMinor)

    @Test fun `ahead of pace is signed`() {
        // day 15 of 30 -> ₹5,000 on pace; spent ₹6,000 -> ₹1,000 ahead
        assertEquals(1000_00L, state(15, 6000).aheadOfPaceMinor)
    }

    @Test fun `overspending reports as negative remaining`() =
        assertEquals(-2000_00L, state(25, 12000).remainingMinor)

    // ---- learning the gap --------------------------------------------------

    @Test fun `typical gap is the median of real gaps`() {
        val dates = listOf(
            payday,
            payday - 30 * DAY_MS,
            payday - 61 * DAY_MS,   // 31
            payday - 90 * DAY_MS,   // 29
        )
        assertEquals(30, Cycle.typicalGapDays(dates))
    }

    @Test fun `a bonus payment does not distort the gap`() {
        val dates = listOf(
            payday,
            payday - 2 * DAY_MS,     // 2-day gap: a bonus, must be ignored
            payday - 32 * DAY_MS,
            payday - 62 * DAY_MS,
        )
        assertEquals(30, Cycle.typicalGapDays(dates))
    }

    @Test fun `falls back when there is no history`() =
        assertEquals(30, Cycle.typicalGapDays(listOf(payday)))

    @Test fun `no budget never reports a false alarm`() {
        val s = Cycle.State(payday, payday + 30 * DAY_MS, 0L, 5000_00L, payday + 10 * DAY_MS)
        assertEquals(Pace.ON_TRACK, s.pace)
    }
}
