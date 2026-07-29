package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * A keyword must not match the front half of a longer word.
 *
 * The reported one: a restaurant with "hospitality" in its name was filed as a
 * medical expense, because "hospital" is eight characters and the rule allowed
 * any keyword of five or more to match as a substring. Length was never the
 * whole story — the keyword was not colliding with an unrelated fragment, it
 * was a truncation of a different word.
 *
 * This is the same family as `rto` inside "spoRTOpia" and `bar` inside
 * "bankofBARoda", both of which must stay fixed, and it sits next to the cases
 * that have to keep working — prefix-matched brands, plurals, and keywords that
 * only appear once the separators are removed.
 */
class TruncationTest {

    private fun cat(m: String) = Categoriser.byRule(m)

    // ---- the reported bug --------------------------------------------------

    @Test fun `hospitality is not a hospital`() =
        assertNotEquals(Categoriser.Category.HEALTH, cat("SHREE HOSPITALITY LLP"))

    @Test fun `a hospitality group is not a medical expense`() =
        assertNotEquals(Categoriser.Category.HEALTH, cat("MARRIOTT HOSPITALITY"))

    /** The tail must be checked per word — the next word hides the truncation. */
    @Test fun `hospitality followed by another word is still not a hospital`() =
        assertNotEquals(Categoriser.Category.HEALTH, cat("SHREE HOSPITALITY GROUP PVT LTD"))

    @Test fun `an actual hospital still is one`() =
        assertEquals(Categoriser.Category.HEALTH, cat("APOLLO HOSPITAL"))

    /** Plurals are not truncations. */
    @Test fun `medicals is still medical`() =
        assertEquals(Categoriser.Category.HEALTH, cat("APOLLO MEDICALS"))

    /** "-al" is deliberately not treated as a truncation. */
    @Test fun `clinical is still a clinic`() =
        assertEquals(Categoriser.Category.HEALTH, cat("CLINICAL LABS"))

    // ---- the older collisions must stay fixed ------------------------------

    @Test fun `rto does not fire inside sportopia`() =
        assertNotEquals(Categoriser.Category.VEHICLE, cat("SPORTOPIA"))

    @Test fun `bar does not fire inside bank of baroda`() =
        assertNotEquals(Categoriser.Category.FUN, cat("BANK OF BARODA"))

    @Test fun `a real bar is still fun`() =
        assertEquals(Categoriser.Category.FUN, cat("THE BAR"))

    // ---- prefix-matched brands must keep working ---------------------------

    @Test fun `uber still matches its concatenated merchant string`() =
        assertEquals(Categoriser.Category.TRANSPORT, cat("UBERINDIASYSTEM187204"))

    // ---- keywords that only exist once separators are removed --------------

    @Test fun `a keyword spanning separators still matches`() =
        assertEquals(Categoriser.Category.TRANSFER, cat("CREDIT CARD BILL"))
}
