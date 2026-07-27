package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.Reconciler.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {

    private val t0 = 1_800_000_000_000L
    private fun hrs(n: Int) = t0 + n * 3_600_000L

    /** balance after the movement, which is how banks quote it */
    private fun r(hour: Int, balance: Long, movement: Long?) =
        Reading(hrs(hour), balance * 100, movement?.times(100))

    @Test fun `a clean run reports nothing`() {
        // 5000 -> pay 500 -> 4500 -> pay 1000 -> 3500
        val res = Reconciler.reconcile(listOf(
            r(0, 5000, null),
            r(1, 4500, -500),
            r(2, 3500, -1000),
        ))
        assertEquals(0, res.gaps.size)
        assertEquals(0L, res.netUnexplainedMinor)
    }

    /**
     * The case this exists for: money left the account and no message explained
     * it — a cash withdrawal, a fee, or a payment under the bank's alert
     * threshold, which is exactly how a ₹1 test payment stayed invisible.
     */
    @Test fun `money leaving unseen is reported`() {
        // 5000 -> pay 500 -> should be 4500, but the bank says 4300
        val res = Reconciler.reconcile(listOf(
            r(0, 5000, null),
            r(1, 4300, -500),
        ))
        assertEquals(1, res.gaps.size)
        assertEquals(20000L, res.netUnexplainedMinor)   // ₹200 unaccounted
        assertEquals(20000L, res.unexplainedOutflowMinor)
    }

    @Test fun `money arriving unseen is reported with the opposite sign`() {
        // 5000 -> pay 500 -> should be 4500, but the bank says 4700
        val res = Reconciler.reconcile(listOf(
            r(0, 5000, null),
            r(1, 4700, -500),
        ))
        assertEquals(-20000L, res.netUnexplainedMinor)
        assertEquals(0L, res.unexplainedOutflowMinor)
    }

    @Test fun `credits reconcile too`() {
        val res = Reconciler.reconcile(listOf(
            r(0, 1000, null),
            r(1, 6000, 5000),
        ))
        assertEquals(0, res.gaps.size)
    }

    /** Paise-level drift from interest and rounding must not become a wall of noise. */
    @Test fun `sub-rupee drift is ignored`() {
        val res = Reconciler.reconcile(listOf(
            Reading(hrs(0), 500_000L, null),
            Reading(hrs(1), 449_950L, -50_000L),   // 50 paise off
        ))
        assertEquals(0, res.gaps.size)
    }

    @Test fun `readings out of order are sorted before comparing`() {
        val res = Reconciler.reconcile(listOf(
            r(2, 3500, -1000),
            r(0, 5000, null),
            r(1, 4500, -500),
        ))
        assertEquals(0, res.gaps.size)
    }

    @Test fun `a single reading proves nothing`() {
        val res = Reconciler.reconcile(listOf(r(0, 5000, null)))
        assertEquals(0, res.gaps.size)
        assertEquals(1, res.readingsUsed)
    }

    @Test fun `gaps carry the window so the user can go and look`() {
        val res = Reconciler.reconcile(listOf(r(0, 5000, null), r(5, 4000, -500)))
        val g = res.gaps.single()
        assertEquals(hrs(0), g.fromAt)
        assertEquals(hrs(5), g.toAt)
        assertTrue(g.unexplainedMinor > 0)
    }

    @Test fun `movement sign follows direction`() {
        assertEquals(-25000L, Reconciler.movementOf(TxnParser.Direction.DEBIT, 25000L))
        assertEquals(25000L, Reconciler.movementOf(TxnParser.Direction.CREDIT, 25000L))
    }

    // ---- sequencing artefacts ----------------------------------------------

    /**
     * Real miss: two SIP debits stamped the same second cannot be ordered, and
     * the wrong guess produced +2,500 immediately followed by -2,500. Real
     * unexplained movement does not reverse on the next reading.
     */
    @Test fun `a cancelling pair is an ordering artefact, not missing money`() {
        // Two debits stamped the same second. Both messages quote the balance
        // after the pair, so the intermediate step is unobservable: the first
        // interval looks 2,500 short and the next looks 2,500 over.
        val res = Reconciler.reconcile(listOf(
            r(0, 10000, null),
            r(1, 5000, -2500),
            r(1, 5000, -2500),
        ))
        assertEquals(0, res.gaps.size)
        assertEquals(0L, res.netUnexplainedMinor)
    }

    /** Two genuine gaps in a row must both survive. */
    @Test fun `real consecutive gaps are not cancelled`() {
        val res = Reconciler.reconcile(listOf(
            r(0, 5000, null),
            r(1, 4300, -500),   // 200 short
            r(2, 3600, -500),   // 200 short again
        ))
        assertEquals(2, res.gaps.size)
        assertEquals(40000L, res.netUnexplainedMinor)
    }
}
