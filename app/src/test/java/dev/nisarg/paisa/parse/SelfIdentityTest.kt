package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.TxnParser.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Identity lives in the `self_identities` table, never in this parser. These
 * tests pass it in the way the app does — as data.
 */
class SelfIdentityTest {

    private val SELF = setOf("MEHTA ARJUN", "ARJUN MEHTA", "arjun.mehta@okhdfcbank")

    private fun sms(body: String, self: Set<String> = SELF) =
        TxnParser.parse(TxnParser.Input("sms", sender = "AD-HDFCBK-S", body = body, selfIdentities = self))

    @Test fun `payment to own name is a self transfer`() {
        val r = sms("Sent Rs.25000.00\nFrom HDFC Bank A/C *9021\nTo MEHTA ARJUN\nOn 26/07/26")
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `payment to own upi id is a self transfer`() {
        val r = sms("Rs.5000.00 Dr. from A/C XXXXXX9877 and Cr. to arjun.mehta@okhdfcbank. Ref:1269")
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `name order does not matter`() {
        val r = sms("Sent Rs.100.00\nFrom HDFC Bank A/C *9021\nTo ARJUN MEHTA\nOn 26/07/26")
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `punctuation and case do not matter`() {
        val r = sms("Sent Rs.100.00\nFrom HDFC Bank A/C *9021\nTo Mehta-Arjun\nOn 26/07/26")
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `someone else is still a real spend`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Shree Rasoi Kathiyawadi\nOn 26/07/26")
        assertEquals(Direction.DEBIT, r.direction)
    }

    @Test fun `a different Nisarg is not the user`() {
        val r = sms("Sent Rs.500.00\nFrom HDFC Bank A/C *9021\nTo ARJUN PATEL\nOn 26/07/26")
        assertEquals(Direction.DEBIT, r.direction)
    }

    @Test fun `with no identities configured nothing is reclassified`() {
        val r = sms("Sent Rs.25000.00\nFrom HDFC Bank A/C *9021\nTo MEHTA ARJUN\nOn 26/07/26", emptySet())
        assertEquals(Direction.DEBIT, r.direction)
    }
}
