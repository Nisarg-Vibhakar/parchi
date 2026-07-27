package dev.nisarg.paisa.parse

/**
 * Checks the app against the bank.
 *
 * Every expense app silently assumes it saw everything. This one can prove
 * otherwise: some banks quote a running balance on each alert, so between two
 * readings on the same account the delta *must* equal the transactions captured
 * in between. Whatever is left over is money that moved without the app seeing
 * it — a cash withdrawal, a bank fee, a payment below the alert threshold, or a
 * parser miss.
 *
 * That last one matters most. This is the only mechanism here that can find the
 * app's own blind spots rather than restate what it already believes.
 */
object Reconciler {

    /** One balance-quoting message: what the bank said the account held, and when. */
    data class Reading(
        val at: Long,
        val balanceMinor: Long,
        /** Signed: negative for money leaving. Null when the row is not a movement. */
        val movementMinor: Long?,
    )

    data class Gap(
        val fromAt: Long,
        val toAt: Long,
        val expectedMinor: Long,
        val actualMinor: Long,
    ) {
        /** Positive means more left the account than the app can explain. */
        val unexplainedMinor: Long get() = expectedMinor - actualMinor
    }

    data class Result(
        val gaps: List<Gap>,
        val readingsUsed: Int,
    ) {
        /**
         * Net unexplained movement. Signed on purpose: a positive figure means
         * money left the account unaccounted for, a negative one means money
         * arrived that was never captured.
         */
        val netUnexplainedMinor: Long get() = gaps.sumOf { it.unexplainedMinor }

        val unexplainedOutflowMinor: Long
            get() = gaps.sumOf { it.unexplainedMinor.coerceAtLeast(0) }
    }

    /**
     * @param readings for ONE account, any order. Reconciling across accounts is
     *   meaningless — two balances from different accounts have no relationship.
     * @param toleranceMinor movements smaller than this are ignored, so rounding
     *   and interest paise do not produce a wall of noise.
     */
    fun reconcile(readings: List<Reading>, toleranceMinor: Long = 100L): Result {
        val ordered = readings.sortedBy { it.at }
        if (ordered.size < 2) return Result(emptyList(), ordered.size)

        val gaps = mutableListOf<Gap>()
        for ((a, b) in ordered.zipWithNext()) {
            // What the bank says happened between the two readings.
            val actual = b.balanceMinor - a.balanceMinor
            // What the app can account for: the movement reported by the later
            // message. The earlier reading is the balance AFTER its own movement,
            // so its movement belongs to the previous interval, not this one.
            val expected = b.movementMinor ?: 0L

            val diff = expected - actual
            if (kotlin.math.abs(diff) >= toleranceMinor) {
                gaps += Gap(a.at, b.at, expected, actual)
            }
        }
        return Result(cancelAdjacentPairs(gaps), ordered.size)
    }

    /**
     * Drops adjacent gaps that cancel each other out.
     *
     * When two messages carry the same timestamp to the second — two SIP debits
     * fired together, for instance — no ordering can tell which balance came
     * first, and the wrong guess produces a +X immediately followed by a -X.
     * Real unexplained movement does not reverse itself on the next reading, so
     * a cancelling pair is an artefact of sequencing rather than missing money.
     *
     * Reporting it would train the user to ignore the number, which is worse
     * than not reporting it at all.
     */
    private fun cancelAdjacentPairs(gaps: List<Gap>): List<Gap> {
        val kept = mutableListOf<Gap>()
        var i = 0
        while (i < gaps.size) {
            val a = gaps[i]
            val b = gaps.getOrNull(i + 1)
            if (b != null && a.unexplainedMinor == -b.unexplainedMinor) {
                i += 2      // an ordering artefact, not a gap
                continue
            }
            kept += a
            i++
        }
        return kept
    }

    /**
     * Convenience for the common shape: a debit reduces the balance, so its
     * movement is negative.
     */
    fun movementOf(direction: TxnParser.Direction, amountMinor: Long?): Long? = when {
        amountMinor == null -> null
        direction == TxnParser.Direction.DEBIT -> -amountMinor
        direction == TxnParser.Direction.CREDIT -> amountMinor
        // A self-transfer still moves this account, and the sign depends on which
        // side of it this message describes — the caller supplies that.
        else -> null
    }
}
