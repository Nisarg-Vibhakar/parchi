package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which payees are worth asking about.
 *
 * The prompt fires only for payees a human cannot read. Asking about a shop
 * whose name is already plain is noise, and noise inside a two-second
 * interaction is what gets an app uninstalled.
 */
class OpaquePayeeTest {

    /** Mirrors PaisaDb.looksOpaque, kept here so the rule itself is testable. */
    private fun opaque(m: String?): Boolean {
        if (m.isNullOrBlank()) return false
        if (m.contains("@")) return true
        val digits = m.count { it.isDigit() }
        return digits >= 4 || m.length <= 4
    }

    @Test fun `upi handles are opaque`() {
        assertEquals(true, opaque("vyapar.900000000001@hdfcbank"))
        assertEquals(true, opaque("q11223344@ybl"))
        assertEquals(true, opaque("paytm-11223344@ptybl"))
    }

    @Test fun `merchant QR codes with long digit strings are opaque`() =
        assertEquals(true, opaque("BHARATPE09888213945"))

    @Test fun `readable shop names are left alone`() {
        assertEquals(false, opaque("Shree Rasoi Kathiyawadi"))
        assertEquals(false, opaque("SUNRISE PETROLEUM"))
        assertEquals(false, opaque("DMART AVENUE SUPERMART"))
    }

    @Test fun `a person's name needs no prompt`() =
        assertEquals(false, opaque("RAO SAMEER"))

    @Test fun `very short labels are opaque`() = assertEquals(true, opaque("CheQ"))

    @Test fun `blank is never prompted`() = assertEquals(false, opaque(""))
}
