package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.TxnParser.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures from the v2 run's leftover "unknown" bucket — 327 rows carrying
 * ₹63 lakh of phantom volume, more than every real debit combined.
 */
class PromoAndEdgeCaseTest {

    private fun sms(body: String) =
        TxnParser.parse(TxnParser.Input("sms", sender = "AD-HDFCBK-S", body = body))

    // ---- marketing must not look like money -------------------------------

    @Test fun `loan offer is promotional`() {
        val r = sms("HDFC Bank Credit Card xx3335 Update: Zero Processing Fee Offer on " +
            "Loan of Rs.679000. Only via PAYZAPP. https://1.hdfc.bank.in/HDFCBK/s/d6dkdeKo T&C")
        assertEquals("promotional", r.rejectedReason)
    }

    @Test fun `credit limit increase is promotional`() {
        val r = sms("Dear Customer, great news! Your HDFC Bank Credit Card limit is now " +
            "increased to Rs.125000. Flexibility you need. Click:https://1.hdfc.bank.in/x")
        assertEquals("promotional", r.rejectedReason)
    }

    @Test fun `food delivery advert is promotional`() {
        val r = sms("Taco Bell Flavor Fiesta! Everything at just Rs.149* today, only on " +
            "Swiggy! Don't miss out, order now: https://vc1.in/TACBEL/x T&C")
        assertEquals("promotional", r.rejectedReason)
    }

    // ---- but a real receipt that carries a link must survive ---------------

    @Test fun `jio bill receipt with a link is still a debit`() {
        val r = sms("Payment of Rs. 73.76 has been received on 22-Jul-26\nJio Number : 9000000009\n" +
            "Payment Mode : Credit Card\nSetup JioAutoPay - http://tiny.jio.com/dregjiopay")
        assertNull(r.rejectedReason)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals(7376L, r.amountMinor)
    }

    @Test fun `autopay receipt ending in TnC is still a debit`() {
        val r = sms("AutoPay (E-mandate) Success! For HOTSTAR  Txn Amt:INR499.00 " +
            "Dt:05/07/2026 Via:HDFC Bank CC 3335 SI Hub ID: Xv8SM0CK7M TnC")
        assertNull(r.rejectedReason)
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals(49900L, r.amountMinor)
    }

    // ---- income -----------------------------------------------------------

    @Test fun `salary deposit is a credit`() {
        val r = sms("Update! INR 68,450.00 deposited in HDFC Bank A/c XX9021 on 07-JUL-26 " +
            "for A2AINT01 -EXAMPLE SOFTWARE PRIVATE LIMITED -Salary -SALARY.Avl bal INR 1,42,610.55")
        assertEquals(Direction.CREDIT, r.direction)
        assertEquals(6845000L, r.amountMinor)
    }

    // ---- double counting --------------------------------------------------

    @Test fun `wallet top-up is a self transfer not a spend`() {
        val r = sms("Your one20hub wallet is successfully loaded with Rs.95.00. " +
            "Your wallet balance is Rs.95.00 - Petpooja")
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `fund house SIP confirmation is rejected as a duplicate of the bank debit`() {
        val r = sms("Dear Investor, Your SIP under folio XXXXXX5055 in Bajaj Finserv Large and " +
            "Mid Cap Fund for Rs. 2500.00 is processed and 203.193 units at NAV 12.303 are allotted")
        assertEquals("third_party_confirmation", r.rejectedReason)
    }

    @Test fun `broker balance statement is not a transaction`() {
        val r = sms("ZERODHABROKINGLIMITED on 18-07-2026 reported your Fund bal Rs.0.230 & " +
            "Securities bal 0.000. This excludes your Bank, DP & PMS bal with the broker-NSE")
        assertEquals("statement", r.rejectedReason)
    }

    @Test fun `debit balance reminder is not a transaction`() {
        val r = sms("Dear Customer, Kindly clear the debit balance of Rs.4413/- in your " +
            "account N433425, to avoid liquidation of your holdings")
        assertEquals("reminder", r.rejectedReason)
    }

    // ---- anti-fraud footer must never become the merchant -----------------

    /**
     * The helpline in the anti-fraud footer must never become the payee — it
     * mis-attributed ₹3.6 lakh in the first corpus.
     *
     * This message names no recipient at all, so the payee now falls back to the
     * channel. That is deliberately not null: with a null payee these payments
     * were invisible to the filing screen, which groups by payee, and they were
     * 84% of the unfiled value.
     */
    @Test fun `helpline number in the footer is not a merchant`() {
        val r = sms("Amt Deducted! Rs.25000 from your HDFC Bank A/c XX9021 for Money Transfer " +
            "via HDFC Bank Online Banking. Not you?Call 18002586161/SMS BLOCK OB to 7308080808")
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals(2500000L, r.amountMinor)
        assertEquals("Money Transfer via HDFC Bank Online Banking", r.merchantRaw)
    }

    @Test fun `real merchant still wins over the footer`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Shree Rasoi Kathiyawadi\n" +
            "On 26/07/26\nRef 900000000010\nNot You?\nCall 18002586161/SMS BLOCK UPI to 7308080808")
        assertEquals("Shree Rasoi Kathiyawadi", r.merchantRaw)
    }
}
