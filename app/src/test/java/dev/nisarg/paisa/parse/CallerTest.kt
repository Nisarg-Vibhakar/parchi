package dev.nisarg.paisa.parse

import dev.nisarg.paisa.work.Caller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallerTest {

    @Test fun `the caller changes from one day to the next`() =
        assertNotEquals(Caller.forDay(100).name, Caller.forDay(101).name)

    /** The same day must not change its mind about who is calling. */
    @Test fun `the same day always gives the same caller`() =
        assertEquals(Caller.forDay(20260).name, Caller.forDay(20260).name)

    @Test fun `the whole roster is used before repeating`() {
        val seen = (0 until Caller.size).map { Caller.forDay(it.toLong()).name }.toSet()
        assertEquals(Caller.size, seen.size)
    }

    @Test fun `it wraps around cleanly`() =
        assertEquals(Caller.forDay(0).name, Caller.forDay(Caller.size.toLong()).name)

    /** Negative epoch days must not crash the modulo. */
    @Test fun `dates before the epoch still resolve`() {
        assertTrue(Caller.forDay(-3).name.isNotBlank())
    }

    @Test fun `every caller has a line, or the joke does not land`() {
        for (d in 0 until Caller.size) {
            val p = Caller.forDay(d.toLong())
            assertTrue(p.name.isNotBlank())
            assertTrue(p.line.isNotBlank())
        }
    }
}
