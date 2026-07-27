package dev.nisarg.paisa.parse

/**
 * A pay cycle: payday to payday.
 *
 * The calendar month is an accident. Nobody thinks "it is the 27th" — they think
 * "payday was three weeks ago and there is a week to go". Every number in this
 * app is framed against that instead, which is why the cycle is a first-class
 * object rather than a date filter.
 *
 * Pure functions over timestamps: no Android, no database, fully unit tested.
 */
object Cycle {

    const val DAY_MS = 24L * 60 * 60 * 1000

    enum class Pace {
        /** Spending slower than the cycle can afford. */
        AHEAD,
        /** Within a tolerance band of the straight-line rate. */
        ON_TRACK,
        /** Faster than the cycle can afford, but the money will last. */
        BEHIND,
        /** At this rate the money runs out before the next payday. */
        OVERRUN,
    }

    data class State(
        val startAt: Long,
        val expectedEndAt: Long,
        val budgetMinor: Long,
        val spentMinor: Long,
        val now: Long,
    ) {
        val totalDays: Int get() = ((expectedEndAt - startAt) / DAY_MS).toInt().coerceAtLeast(1)
        val elapsedDays: Int get() = ((now - startAt) / DAY_MS).toInt().coerceIn(0, totalDays)
        /** Day 1 is payday itself — "day 0 of 31" reads as a bug to a human. */
        val dayNumber: Int get() = (elapsedDays + 1).coerceAtMost(totalDays)
        val daysLeft: Int get() = (totalDays - elapsedDays).coerceAtLeast(0)

        val remainingMinor: Long get() = budgetMinor - spentMinor
        val overspent: Boolean get() = remainingMinor < 0

        /** Fraction of the cycle's time that has passed. */
        val timeFraction: Double
            get() = if (totalDays == 0) 1.0 else elapsedDays.toDouble() / totalDays

        /** Fraction of the money that has gone. */
        val spendFraction: Double
            get() = if (budgetMinor <= 0) 0.0 else spentMinor.toDouble() / budgetMinor

        /** What a straight-line spender would have spent by now. */
        val onPaceMinor: Long
            get() = (budgetMinor * timeFraction).toLong()

        /** Positive means spending faster than the cycle affords. */
        val aheadOfPaceMinor: Long get() = spentMinor - onPaceMinor

        /** Actual daily burn so far. Uses days elapsed, minimum one. */
        val burnPerDayMinor: Long
            get() = spentMinor / elapsedDays.coerceAtLeast(1)

        /** What the cycle can afford per day, over its whole length. */
        val budgetPerDayMinor: Long
            get() = if (totalDays == 0) 0 else budgetMinor / totalDays

        /**
         * What is left to spend per remaining day. This is the number that
         * actually changes behaviour — a total does not.
         */
        val allowancePerDayMinor: Long
            get() = if (daysLeft <= 0) remainingMinor else remainingMinor / daysLeft

        /** Days the remaining money lasts at the current burn rate. */
        val runwayDays: Int
            get() {
                val burn = burnPerDayMinor
                if (burn <= 0) return Int.MAX_VALUE
                return (remainingMinor / burn).toInt().coerceAtLeast(0)
            }

        /** Where this cycle lands if the current burn rate holds to the end. */
        val projectedMinor: Long get() = burnPerDayMinor * totalDays

        /**
         * Note on the thresholds: "runway shorter than days left" cannot be used
         * to mean overrun, because it reduces algebraically to "spent > on pace"
         * — the same test as BEHIND, which would make BEHIND unreachable. So
         * overrun is judged on the projected total instead, with a tolerance band
         * so an ordinary bad week is not reported as a crisis.
         */
        val pace: Pace
            get() {
                if (budgetMinor <= 0) return Pace.ON_TRACK
                if (remainingMinor < 0) return Pace.OVERRUN
                if (projectedMinor > budgetMinor * 1.15) return Pace.OVERRUN
                val ratio = if (onPaceMinor <= 0) 0.0
                else spentMinor.toDouble() / onPaceMinor
                return when {
                    ratio <= 0.9 -> Pace.AHEAD
                    ratio <= 1.1 -> Pace.ON_TRACK
                    else -> Pace.BEHIND
                }
            }
    }

    /**
     * The usual gap between paydays, learned from history rather than assumed.
     * Salary dates drift — the 30th, then the 2nd, then the 31st — so the median
     * gap is used, which a single late month cannot distort.
     */
    fun typicalGapDays(salaryDatesNewestFirst: List<Long>, fallback: Int = 30): Int {
        if (salaryDatesNewestFirst.size < 2) return fallback
        val gaps = salaryDatesNewestFirst
            .zipWithNext { newer, older -> ((newer - older) / DAY_MS).toInt() }
            .filter { it in 20..45 }        // discard bonuses and arrears
            .sorted()
        if (gaps.isEmpty()) return fallback
        return gaps[gaps.size / 2]
    }
}
