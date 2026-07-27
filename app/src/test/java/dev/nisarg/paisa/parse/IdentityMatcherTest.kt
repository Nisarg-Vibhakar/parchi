package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Merging identities is the easiest place in this app to cause silent damage:
 * join two different people and every figure after it is wrong with no symptom.
 *
 * These tests are therefore weighted toward what must NOT be suggested. A missed
 * merge costs nothing; a wrong one costs trust in the whole ledger.
 */
class IdentityMatcherTest {

    @Test fun `handle stem drops the bank's counter`() =
        assertEquals("sameerrao", IdentityMatcher.handleStem("sameer.rao-1@okaxis"))

    @Test fun `plain names have no stem`() =
        assertNull(IdentityMatcher.handleStem("RAO SAMEER"))

    @Test fun `very short handles are ignored as too weak to match on`() =
        assertNull(IdentityMatcher.handleStem("q11@ybl"))

    @Test fun `name key is order independent`() =
        assertEquals(IdentityMatcher.nameKey("RAO SAMEER"), IdentityMatcher.nameKey("SAMEER RAO"))

    @Test fun `handles are not name keys`() = assertNull(IdentityMatcher.nameKey("x.y@okaxis"))

    @Test fun `single words are too ambiguous to key`() =
        assertNull(IdentityMatcher.nameKey("SAMEER"))

    // ---- what it should find ----------------------------------------------

    @Test fun `a handle and the matching name are suggested`() {
        val c = IdentityMatcher.suggest(listOf("sameer.rao-1@okaxis", "RAO SAMEER")).single()
        assertTrue(setOf(c.a, c.b) == setOf("sameer.rao-1@okaxis", "RAO SAMEER"))
    }

    /** The real case: the handle misspells the name, as handles usually do. */
    @Test fun `a misspelt handle still matches the name`() {
        val s = IdentityMatcher.suggest(
            listOf("rahulshrma2308-1@okaxis", "SHARMA RAHUL"))
        assertEquals(1, s.size)
    }

    @Test fun `the same handle with different suffixes is the strongest match`() {
        val c = IdentityMatcher.suggest(
            listOf("sameer.rao-1@okaxis", "sameer.rao-2@okicici")).single()
        assertTrue(c.strength > 0.9)
    }

    // ---- what it must never suggest ---------------------------------------

    @Test fun `two different people are left alone`() =
        assertEquals(0, IdentityMatcher.suggest(listOf("rahul.sharma@okaxis", "RAO SAMEER")).size)

    @Test fun `merchant QR handles never match a person`() =
        assertEquals(0, IdentityMatcher.suggest(listOf("q11223344@ybl", "RAO SAMEER")).size)

    @Test fun `a shared first name is not enough`() =
        assertEquals(0, IdentityMatcher.suggest(listOf("sameer@okaxis", "SAMEER PATEL")).size)

    @Test fun `shops are not merged with each other`() =
        assertEquals(0, IdentityMatcher.suggest(
            listOf("SUNRISE PETROLEUM", "MEHTA FUEL SERVICES")).size)

    @Test fun `nothing to compare yields nothing`() =
        assertEquals(0, IdentityMatcher.suggest(listOf("RAO SAMEER")).size)

    @Test fun `every suggestion carries its evidence`() {
        val c = IdentityMatcher.suggest(listOf("sameer.rao-1@okaxis", "RAO SAMEER")).single()
        assertTrue("a suggestion with no reason cannot be judged", c.reason.isNotBlank())
    }
}
