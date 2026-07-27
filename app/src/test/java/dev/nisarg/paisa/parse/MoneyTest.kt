package dev.nisarg.paisa.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test fun `whole rupees`() = assertEquals(123400L, Money.toMinor("1234"))

    @Test fun `two decimal places`() = assertEquals(123450L, Money.toMinor("1234.50"))

    @Test fun `one decimal place means tens of paise`() =
        assertEquals(123450L, Money.toMinor("1234.5"))

    @Test fun `western grouping`() = assertEquals(123450L, Money.toMinor("1,234.50"))

    @Test fun `indian grouping`() = assertEquals(10000000L, Money.toMinor("1,00,000"))

    @Test fun `one rupee fifty`() = assertEquals(150L, Money.toMinor("1.50"))

    @Test fun `zero`() = assertEquals(0L, Money.toMinor("0"))

    @Test fun `rejects three decimal places`() = assertNull(Money.toMinor("12.345"))

    @Test fun `rejects letters`() = assertNull(Money.toMinor("12a"))

    @Test fun `rejects empty`() = assertNull(Money.toMinor(""))

    @Test fun `rejects two decimal points`() = assertNull(Money.toMinor("1.2.3"))

    @Test fun `format pads paise`() = assertEquals("₹1234.05", Money.format(123405L))

    @Test fun `format round amount`() = assertEquals("₹1000.00", Money.format(100000L))
}
