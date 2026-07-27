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
