package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.Buckets.Plan
import dev.nisarg.paisa.parse.Buckets.State
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedBucketsTest {

    private fun plan(label: String, amount: Long, vararg cats: String, period: Int = 1) =
        Plan(label, cats.toSet(), amount * 100, period)

    private fun spend(vararg pairs: Pair<String, Long>) =
        pairs.toMap().mapValues { it.value * 100 }

    // ---- grouping ----------------------------------------------------------

    /** People budget in envelopes, not in taxonomy: "fuel and transport" is one line. */
    @Test fun `a bucket sums every category it covers`() {
        val b = Buckets.evaluate(
            listOf(plan("Fuel + Transport", 2500, "FUEL", "TRANSPORT")),
            spend("FUEL" to 1800, "TRANSPORT" to 400, "FOOD" to 9000),
        ) { 0.5 }.single()
        assertEquals(220000L, b.spentMinor)
        assertEquals(30000L, b.remainingMinor)
    }

    @Test fun `categories outside every bucket are ignored`() {
        val b = Buckets.evaluate(
            listOf(plan("Food", 6000, "FOOD")),
            spend("FOOD" to 3000, "PEOPLE" to 20000),
        ) { 0.5 }.single()
        assertEquals(300000L, b.spentMinor)
    }

    // ---- periods -----------------------------------------------------------

    /**
     * The bimonthly utility bill. Against a monthly bucket it reads as zero, then
     * double — alternating "quiet" and "over" forever. A signal that is always
     * wrong is one you learn to ignore.
     */
    @Test fun `a two-cycle bill is judged across two cycles`() {
        val plans = listOf(plan("Utilities", 4000, "BILLS", period = 2))
        // Nothing yet, a quarter of the way through the two-cycle window.
        val early = Buckets.evaluate(plans, spend()) { 0.25 }.single()
        assertEquals(State.QUIET, early.state)
        // The bill lands late in the window: on plan, not "over".
        val late = Buckets.evaluate(plans, spend("BILLS" to 3900)) { 0.9 }.single()
        assertEquals(State.NORMAL, late.state)
    }

    @Test fun `exceeding the amount is over whatever the period`() {
        val b = Buckets.evaluate(
            listOf(plan("Utilities", 4000, "BILLS", period = 2)),
            spend("BILLS" to 4600),
        ) { 0.3 }.single()
        assertEquals(State.OVER, b.state)
    }

    // ---- pace --------------------------------------------------------------

    @Test fun `spending in step with the period is normal`() {
        val b = Buckets.evaluate(listOf(plan("Food", 6000, "FOOD")),
            spend("FOOD" to 3000)) { 0.5 }.single()
        assertEquals(State.NORMAL, b.state)
    }

    @Test fun `well ahead of the clock is running hot`() {
        val b = Buckets.evaluate(listOf(plan("Food", 6000, "FOOD")),
            spend("FOOD" to 4800)) { 0.3 }.single()
        assertEquals(State.RUNNING_HOT, b.state)
    }

    @Test fun `a fifth spent on day three is not an alarm`() {
        val b = Buckets.evaluate(listOf(plan("Food", 6000, "FOOD")),
            spend("FOOD" to 1200)) { 0.1 }.single()
        assertEquals(State.NORMAL, b.state)
    }

    @Test fun `an unset amount reports no baseline rather than instant failure`() {
        val b = Buckets.evaluate(listOf(plan("Health", 0, "HEALTH")),
            spend("HEALTH" to 4770)) { 0.5 }.single()
        assertEquals(State.NO_BASELINE, b.state)
    }

    @Test fun `buckets are ordered by size so rent leads`() {
        val list = Buckets.evaluate(listOf(
            plan("Subscriptions", 500, "SUBSCRIPTIONS"),
            plan("Rent", 11000, "RENT"),
            plan("Food", 6000, "FOOD"),
        ), spend()) { 0.5 }
        assertEquals(listOf("Rent", "Food", "Subscriptions"), list.map { it.plan.label })
    }
}
