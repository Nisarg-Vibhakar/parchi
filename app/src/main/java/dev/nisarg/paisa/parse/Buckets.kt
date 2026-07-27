package dev.nisarg.paisa.parse

/**
 * Per-category allowances, derived rather than declared.
 *
 * A budget you type into an app is a number you invented on a hopeful afternoon
 * and will ignore by the second week. A budget taken from what you actually
 * spent, cycle after cycle, is a fact — and being over it means something
 * changed, which is the only signal worth a notification.
 *
 * Uses the median of completed cycles, not the mean: one bike purchase or one
 * wedding would drag a mean upward permanently and quietly raise the bar to a
 * level that can never be exceeded, which is the same as having no bar at all.
 */
object Buckets {

    /** Below this many completed cycles there is no honest baseline to quote. */
    const val MIN_CYCLES = 3

    data class Bucket(
        val category: String,
        val typicalMinor: Long,
        val spentMinor: Long,
        val cyclesObserved: Int,
    ) {
        val overMinor: Long get() = spentMinor - typicalMinor

        /** Fraction of the usual amount spent so far. 1.0 is exactly typical. */
        val ratio: Double
            get() = if (typicalMinor <= 0) 0.0 else spentMinor.toDouble() / typicalMinor

        /**
         * Compared like-for-like against how far through the cycle we are, so a
         * category is not called "over" on day 3 for spending a fifth of its
         * usual amount.
         */
        fun state(cycleFraction: Double): State {
            if (typicalMinor <= 0) return State.NO_BASELINE
            val expected = cycleFraction.coerceIn(0.0, 1.0)
            return when {
                ratio > 1.0 -> State.OVER
                ratio > expected + 0.15 -> State.RUNNING_HOT
                ratio < expected - 0.15 -> State.QUIET
                else -> State.NORMAL
            }
        }
    }

    enum class State { NO_BASELINE, QUIET, NORMAL, RUNNING_HOT, OVER }

    /**
     * A bucket the user set, rather than one derived from history.
     *
     * Three things the derived version could not express, all of which came
     * straight from how a real person described their own budget:
     *
     * GROUPS — people think "fuel and transport" as one envelope, not two lines.
     *
     * PERIODS — a utility bill arriving every second month against a monthly
     * bucket reads as zero, then double. It would alternate between "quiet" and
     * "over" forever, and a signal that is always wrong is one you learn to
     * ignore. A bucket spanning N cycles is compared across N cycles.
     *
     * INTENT — the median only knows what you did. You may know what you mean to
     * do, and that is a different number.
     */
    data class Plan(
        val label: String,
        val categories: Set<String>,
        val amountMinor: Long,
        /** 1 = every cycle. 2 = a bill that arrives every second cycle. */
        val periodCycles: Int = 1,
    )

    data class PlannedBucket(
        val plan: Plan,
        /** Spend across the whole period window, not just this cycle. */
        val spentMinor: Long,
        /** How far through the period we are, 0..1. */
        val periodFraction: Double,
    ) {
        val remainingMinor: Long get() = plan.amountMinor - spentMinor
        val ratio: Double
            get() = if (plan.amountMinor <= 0) 0.0
            else spentMinor.toDouble() / plan.amountMinor

        val state: State
            get() = when {
                plan.amountMinor <= 0 -> State.NO_BASELINE
                ratio > 1.0 -> State.OVER
                ratio > periodFraction + 0.15 -> State.RUNNING_HOT
                ratio < periodFraction - 0.15 -> State.QUIET
                else -> State.NORMAL
            }
    }

    /**
     * @param spendByCategory totals across the bucket's whole period window.
     */
    fun evaluate(
        plans: List<Plan>,
        spendByCategory: Map<String, Long>,
        periodFraction: (Plan) -> Double,
    ): List<PlannedBucket> = plans.map { plan ->
        PlannedBucket(
            plan = plan,
            spentMinor = plan.categories.sumOf { spendByCategory[it] ?: 0L },
            periodFraction = periodFraction(plan).coerceIn(0.0, 1.0),
        )
    }.sortedByDescending { it.plan.amountMinor }

    /**
     * @param historyByCycle per-category totals for each COMPLETED cycle, newest
     *   first. The current cycle is excluded by the caller — including a
     *   part-finished cycle would drag every baseline down.
     */
    fun build(
        historyByCycle: List<Map<String, Long>>,
        currentCycle: Map<String, Long>,
    ): List<Bucket> {
        val categories = (historyByCycle.flatMap { it.keys } + currentCycle.keys).toSet()
        return categories.mapNotNull { category ->
            // A cycle where the category did not appear is a real zero, not a
            // missing reading — you genuinely spent nothing on it that month.
            val amounts = historyByCycle.map { it[category] ?: 0L }
            if (amounts.size < MIN_CYCLES) return@mapNotNull null
            Bucket(
                category = category,
                typicalMinor = median(amounts),
                spentMinor = currentCycle[category] ?: 0L,
                cyclesObserved = amounts.size,
            )
        }.sortedByDescending { it.spentMinor }
    }

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
