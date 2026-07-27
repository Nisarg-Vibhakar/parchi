package dev.nisarg.paisa.parse

import dev.nisarg.paisa.parse.TxnParser.Direction
import dev.nisarg.paisa.parse.TxnParser.Instrument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixture corpus.
 *
 * Every string here is a GUESS until real captures land. When the first export
 * comes off the phone, the guessed strings get replaced with observed ones and
 * this file becomes the durable asset of Phase 1: every parser bug ever found,
 * pinned so it cannot come back.
 */
class TxnParserTest {

    private val GPAY = "com.google.android.apps.nbu.paisa.user"

    private fun notif(title: String, body: String = "", pkg: String = GPAY) =
        TxnParser.parse(TxnParser.Input("notification", packageName = pkg, title = title, body = body))

    private fun sms(body: String, sender: String = "AD-HDFCBK") =
        TxnParser.parse(TxnParser.Input("sms", sender = sender, body = body))

    // ---- amount ----------------------------------------------------------

    @Test fun `extracts rupee amount from notification title`() {
        val r = notif("You paid ₹250.00 to Sharma Tea Stall")
        assertEquals(25000L, r.amountMinor)
    }

    @Test fun `extracts amount with comma - the high risk case`() {
        val r = notif("You paid ₹1,250.00 to Rohit")
        assertEquals(125000L, r.amountMinor)
    }

    @Test fun `extracts non round amount`() {
        val r = notif("You paid ₹1.50 to Test")
        assertEquals(150L, r.amountMinor)
    }

    @Test fun `extracts Rs prefix from sms`() {
        val r = sms("Rs.1,234.50 debited from A/c XX1234 on 27-07-26")
        assertEquals(123450L, r.amountMinor)
    }

    @Test fun `extracts INR prefix`() {
        val r = sms("INR 500 debited from your account")
        assertEquals(50000L, r.amountMinor)
    }

    // ---- direction -------------------------------------------------------

    @Test fun `paid is a debit`() {
        assertEquals(Direction.DEBIT, notif("You paid ₹250 to Sharma Tea Stall").direction)
    }

    @Test fun `debited is a debit`() {
        assertEquals(Direction.DEBIT, sms("Rs.250 debited from A/c XX1234").direction)
    }

    @Test fun `received is a credit - must never count as spending`() {
        val r = notif("You received ₹1 from Rohit Sharma")
        assertEquals(Direction.CREDIT, r.direction)
    }

    @Test fun `credited is a credit`() {
        assertEquals(Direction.CREDIT, sms("Rs.5000 credited to A/c XX1234").direction)
    }

    @Test fun `refund resolves to credit even when debit words are present`() {
        val r = sms("Refund of Rs.250 credited to your card ending 1234")
        assertEquals(Direction.CREDIT, r.direction)
    }

    // ---- rejection: not completed spends ---------------------------------

    @Test fun `failed payment is rejected`() {
        val r = notif("Payment of ₹500 to Rohit failed")
        assertEquals("failed", r.rejectedReason)
        assertEquals(0.0, r.confidence, 0.0001)
    }

    @Test fun `cancelled payment is rejected`() {
        assertEquals("cancelled", notif("Your payment of ₹500 was cancelled").rejectedReason)
    }

    @Test fun `payment request is rejected - money did not move`() {
        assertEquals("request", notif("Rohit is requesting ₹500").rejectedReason)
    }

    @Test fun `otp is rejected`() {
        assertEquals("otp", sms("123456 is your OTP for a txn of Rs.500. Do not share.").rejectedReason)
    }

    @Test fun `future debit notice is rejected`() {
        assertEquals("future", sms("Rs.499 will be debited on 01-08 for Netflix").rejectedReason)
    }

    @Test fun `a normal payment is not rejected`() {
        assertNull(notif("You paid ₹250 to Sharma Tea Stall").rejectedReason)
    }

    // ---- merchant --------------------------------------------------------

    @Test fun `merchant after paid-amount-to`() {
        assertEquals("Sharma Tea Stall", notif("You paid ₹250 to Sharma Tea Stall").merchantRaw)
    }

    @Test fun `merchant stops before trailing clause`() {
        assertEquals("Rohit Sharma", notif("You paid ₹250 to Rohit Sharma on 27 Jul").merchantRaw)
    }

    @Test fun `counterparty captured on credit`() {
        assertEquals("Rohit Sharma", notif("You received ₹1 from Rohit Sharma").merchantRaw)
    }

    @Test fun `vpa prefix is stripped`() {
        assertEquals("rahul@okaxis", sms("Rs.250 debited to VPA rahul@okaxis on 27-07").merchantRaw)
    }

    // ---- reference id ----------------------------------------------------

    @Test fun `labelled upi reference`() {
        val r = sms("Rs.250 debited. UPI Ref No 123456789012")
        assertEquals("123456789012", r.refId)
    }

    @Test fun `bare twelve digit rrn`() {
        val r = sms("Rs.250 debited from A/c. 987654321098 is the txn reference")
        assertNotNull(r.refId)
    }

    // ---- instrument ------------------------------------------------------

    @Test fun `card detected from card ending`() {
        assertEquals(Instrument.CARD, sms("Rs.250 spent on card ending 1234").instrument)
    }

    @Test fun `upi detected from gpay package`() {
        assertEquals(Instrument.UPI, notif("You paid ₹250 to Sharma Tea Stall").instrument)
    }

    @Test fun `upi detected from sms text`() {
        assertEquals(Instrument.UPI, sms("Rs.250 debited via UPI").instrument)
    }

    // ---- confidence ------------------------------------------------------

    @Test fun `full parse scores high`() {
        val r = sms("Rs.250.00 debited to VPA rahul@okaxis. UPI Ref No 123456789012")
        assertTrue("expected high confidence, got ${r.confidence}", r.confidence >= 0.9)
    }

    @Test fun `unparseable text scores zero`() {
        val r = notif("Your order has shipped", pkg = "com.amazon.mShop")
        assertEquals(0.0, r.confidence, 0.0001)
    }

    // ---- traceability ----------------------------------------------------

    @Test fun `every successful parse records which rules fired`() {
        val r = notif("You paid ₹250 to Sharma Tea Stall")
        assertTrue(r.matchedRules.any { it.startsWith("amount:") })
        assertTrue(r.matchedRules.any { it.startsWith("direction:") })
        assertTrue(r.matchedRules.any { it.startsWith("merchant:") })
    }

    @Test fun `parser version is stamped on every result`() {
        assertEquals(TxnParser.PARSER_VERSION, notif("You paid ₹1 to X").parserVersion)
    }
}
