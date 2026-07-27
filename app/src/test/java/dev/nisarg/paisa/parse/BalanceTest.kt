package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Account and balance extraction — the inputs to reconciliation.
 *
 * Between two balance readings on the same account, the delta must equal the
 * transactions captured in between. Anything left over is money that moved
 * without the app seeing it: a cash withdrawal, a bank fee, or a payment below
 * the bank's alert threshold. It is the only way to know whether the totals are
 * complete rather than merely self-consistent.
 */
class BalanceTest {

    private fun sms(body: String) =
        TxnParser.parse(TxnParser.Input("sms", sender = "JK-BOBSMS-S", body = body))

    @Test fun `BOB quotes a balance`() {
        val r = sms("Rs.55.00 Dr. from A/C XXXXXX9877 and Cr. to q11223344@ybl. " +
            "Ref:900000000012. AvlBal:Rs2450.60(2026:07:27 11:00:04)")
        assertEquals(245060L, r.balanceMinor)
        assertEquals("9877", r.accountTail)
    }

    @Test fun `the balance is not mistaken for the amount`() {
        val r = sms("Rs.55.00 Dr. from A/C XXXXXX9877 and Cr. to x@ybl. AvlBal:Rs2450.60")
        assertEquals(5500L, r.amountMinor)
        assertEquals(245060L, r.balanceMinor)
    }

    @Test fun `BOB transfer wording quotes a balance too`() {
        val r = sms("Rs.2500 transferred from A/c ...9877 to:ACHDR/NSEClearin. " +
            "Total Bal:Rs.3120.4CR. Avlbl Amt:Rs.3120.4(15-07-2026 08:14:43)")
        assertEquals(312040L, r.balanceMinor)
        assertEquals("9877", r.accountTail)
    }

    @Test fun `HDFC sends no balance and that is fine`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Some Shop\nOn 26/07/26")
        assertNull(r.balanceMinor)
        assertEquals("9021", r.accountTail)
    }

    @Test fun `a card is its own account for reconciliation`() {
        val r = sms("Spent Rs.147 On HDFC Bank Card 5150 At SWIGGY FOOD On 2026-07-27")
        assertEquals("5150", r.accountTail)
    }

    @Test fun `commas in a balance survive`() {
        val r = sms("Rs.100 Dr. from A/C XXXXXX9877. AvlBal:Rs1,47,320.55")
        assertEquals(14732055L, r.balanceMinor)
    }

    // ---- when the money actually moved -------------------------------------

    /**
     * Real miss: two SIP debits seconds apart arrived as SMS in the wrong order,
     * and sequencing by delivery time produced a phantom +2,500/-2,500 pair of
     * reconciliation gaps. Each message stamps its own instant; use it.
     */
    @Test fun `balance carries the bank's own timestamp`() {
        val r = sms("Rs.55.00 Dr. from A/C XXXXXX9877 and Cr. to x@ybl. " +
            "AvlBal:Rs2450.60(2026:07:27 11:00:04)")
        val cal = java.util.Calendar.getInstance().apply {
            clear(); set(2026, 6, 27, 11, 0, 4)
        }
        assertEquals(cal.timeInMillis, r.balanceAtMillis)
    }

    @Test fun `the day-first stamp format is understood too`() {
        val r = sms("Rs.2500 transferred from A/c ...9877 to:X. " +
            "Avlbl Amt:Rs.3120.4(15-07-2026 08:14:43)")
        val cal = java.util.Calendar.getInstance().apply {
            clear(); set(2026, 6, 15, 8, 14, 43)
        }
        assertEquals(cal.timeInMillis, r.balanceAtMillis)
    }

    @Test fun `no balance means no stamp`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Some Shop")
        assertNull(r.balanceAtMillis)
    }
}
