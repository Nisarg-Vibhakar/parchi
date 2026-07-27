package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.TxnParser.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixtures taken from the first real capture (2,805 bank SMS, 2026-07-27).
 *
 * Every string here is a shape that actually occurs on this phone, with account
 * numbers and references altered. These are the formats parser v1 got wrong —
 * pinned so they cannot regress.
 */
class RealCorpusTest {

    private fun sms(body: String) =
        TxnParser.parse(TxnParser.Input("sms", sender = "AD-HDFCBK-S", body = body))

    // ---- HDFC UPI: 1089 of 2419 rows, and v1 missed every one -------------

    private val HDFC_SENT = """
        Sent Rs.765.00
        From HDFC Bank A/C *9021
        To Shree Rasoi Kathiyawadi
        On 26/07/26
        Ref 900000000010
        Not You?
        Call 18002586161/SMS BLOCK UPI to 7308080808
    """.trimIndent()

    @Test fun `bare Sent is a debit`() =
        assertEquals(Direction.DEBIT, sms(HDFC_SENT).direction)

    @Test fun `HDFC sent amount`() =
        assertEquals(76500L, sms(HDFC_SENT).amountMinor)

    @Test fun `HDFC sent merchant`() =
        assertEquals("Shree Rasoi Kathiyawadi", sms(HDFC_SENT).merchantRaw)

    @Test fun `HDFC merchant with all caps name`() {
        val r = sms("Sent Rs.2500.00\nFrom HDFC Bank A/C *9021\nTo SUNRISE PETROLEUM\nOn 26/07/26\nRef 900000000011")
        assertEquals("SUNRISE PETROLEUM", r.merchantRaw)
        assertEquals(250000L, r.amountMinor)
    }

    // ---- Bank of Baroda: Dr./Cr. abbreviations ----------------------------

    private val BOB_DR = "Rs.55.00 Dr. from A/C XXXXXX9877 and Cr. to q11223344@ybl. " +
        "Ref:900000000012. AvlBal:Rs2450.60(2026:07:27 11:00:04). Not you? Call 18005700/5000-BOB"

    @Test fun `Dr from A slash C is a debit`() =
        assertEquals(Direction.DEBIT, sms(BOB_DR).direction)

    @Test fun `BOB amount is the transaction not the balance`() =
        assertEquals(5500L, sms(BOB_DR).amountMinor)

    @Test fun `BOB counterparty is the Cr to payee`() =
        assertEquals("q11223344@ybl", sms(BOB_DR).merchantRaw)

    // ---- Bank of Baroda: SIP / investment transfer ------------------------

    private val BOB_TRANSFER =
        "Rs.2500 transferred from A/c ...9123 to:ACHDR/NSEClearin. " +
        "Total Bal:Rs.3120.4CR. Avlbl Amt:Rs.3120.4(15-07-2026 08:14:43) - Bank of Baroda"

    @Test fun `transferred from A slash c is a debit`() =
        assertEquals(Direction.DEBIT, sms(BOB_TRANSFER).direction)

    @Test fun `colon form merchant is extracted`() =
        assertEquals("ACHDR/NSEClearin", sms(BOB_TRANSFER).merchantRaw)

    // ---- Credit card bill payment: NEITHER spending NOR income ------------

    @Test fun `card bill payment is a self transfer not income`() {
        val r = sms(
            "HDFC Bank Cardmember, Online Payment of Rs.18750 vide Ref# 900BALAAAMI9AAA " +
                "was credited to your card ending 4411 On 22/JUL/2026"
        )
        assertEquals(Direction.SELF_TRANSFER, r.direction)
        assertEquals(1875000L, r.amountMinor)
    }

    @Test fun `uppercase card payment is also a self transfer`() {
        val r = sms(
            "DEAR HDFCBANK CARDMEMBER, PAYMENT OF Rs. 8059.00 RECEIVED TOWARDS YOUR " +
                "CREDIT CARD ENDING WITH 5150 ON 22-7-2026.YOUR AVAILABLE LIMIT IS RS. 167127.63"
        )
        assertEquals(Direction.SELF_TRANSFER, r.direction)
    }

    @Test fun `a refund to a card is still a real credit`() {
        val r = sms("Refund of Rs.250.00 was credited to your card ending 5150 on 22-07-26")
        assertEquals(Direction.CREDIT, r.direction)
    }

    // ---- Genuine incoming money -------------------------------------------

    @Test fun `credit alert is a credit`() {
        val r = sms(
            "Credit Alert!\nRs.7600.00 credited to HDFC Bank A/c XX9021 on 22-07-26 " +
                "from VPA someone@okaxis (UPI 620364647426)"
        )
        assertEquals(Direction.CREDIT, r.direction)
        assertEquals(760000L, r.amountMinor)
    }

    // ---- Merchant POS ------------------------------------------------------

    @Test fun `payment done successfully at merchant is a debit`() {
        val r = sms("Payment of Rs. 95 has been done successfully at Tandoor Junction (Nehrunagar). " +
            "Remaining balance: Rs.0.00 - Petpooja")
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals(9500L, r.amountMinor)
    }

    // ---- Things that must stay rejected -----------------------------------

    @Test fun `jio otp stays rejected`() {
        val r = sms("123456 is your OTP for a txn of Rs.500. Do not share with anyone.")
        assertEquals("otp", r.rejectedReason)
    }

    // ---- UPI handles must survive whole ------------------------------------

    /**
     * Real miss: the full stop inside a handle truncated it, so four different
     * shops billing through the Vyapar app all became one payee called "vyapar".
     */
    @Test fun `upi handle with a dot is captured whole`() {
        val r = sms("Rs.19961.00 Dr. from A/C XXXXXX9877 and Cr. to " +
            "vyapar.900000000001@hdfcbank. Ref:900000000013. AvlBal:Rs31890.25")
        assertEquals("vyapar.900000000001@hdfcbank", r.merchantRaw)
        assertEquals(1996100L, r.amountMinor)
    }

    @Test fun `two shops on the same handle prefix stay distinct`() {
        val a = sms("Rs.560.00 Dr. from A/C XXXXXX9877 and Cr. to vyapar.900000000002@hdfcbank. Ref:1")
        val b = sms("Rs.100.00 Dr. from A/C XXXXXX9877 and Cr. to vyapar.900000000003@hdfcbank. Ref:2")
        assertEquals("vyapar.900000000002@hdfcbank", a.merchantRaw)
        assertEquals("vyapar.900000000003@hdfcbank", b.merchantRaw)
    }

    @Test fun `paytm handle with a dot survives too`() {
        val r = sms("Rs.300.00 Dr. from A/C XXXXXX9877 and Cr. to paytm.d99887766554@pty. Ref:123")
        assertEquals("paytm.d99887766554@pty", r.merchantRaw)
    }

    @Test fun `hyphenated handles still work`() {
        val r = sms("Rs.246.00 Dr. from A/C XXXXXX9877 and Cr. to paytm-11223344@ptybl. Ref:126")
        assertEquals("paytm-11223344@ptybl", r.merchantRaw)
    }

    @Test fun `a plain name is not mistaken for a handle`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Shree Rasoi Kathiyawadi\nOn 26/07/26")
        assertEquals("Shree Rasoi Kathiyawadi", r.merchantRaw)
    }

    // ---- payments the bank does not name -----------------------------------

    /**
     * Real gap: HDFC names no recipient for net-banking transfers, so six
     * ₹25,000 payments — 84% of the unfiled value — had a null payee and were
     * invisible to a filing screen that groups by payee.
     */
    @Test fun `net banking transfer falls back to the channel`() {
        val r = sms("Amt Deducted! Rs.25000 from your HDFC Bank A/c XX9021 for Money " +
            "Transfer via HDFC Bank Online Banking. Not you?Call 18002586161")
        assertEquals(Direction.DEBIT, r.direction)
        assertEquals(2500000L, r.amountMinor)
        assertEquals("Money Transfer via HDFC Bank Online Banking", r.merchantRaw)
    }

    /** A real payee still wins over the channel fallback. */
    @Test fun `a named payee is preferred to the channel`() {
        val r = sms("Sent Rs.765.00\nFrom HDFC Bank A/C *9021\nTo Shree Rasoi Kathiyawadi\nOn 26/07/26")
        assertEquals("Shree Rasoi Kathiyawadi", r.merchantRaw)
    }
}
